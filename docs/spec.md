# Task: sbt-call-graph

**Date:** 2026-03-03
**Status:** spec (phases 1–6 complete)

---

## Goal

Give LLMs (and developers) the ability to find call paths between methods in a Scala project via `sbtn` commands, so they can read only the relevant code instead of loading entire files.

## Users

- **Primary:** LLM (Claude Code) — invokes `sbtn` tasks, receives JSON, reads only the relevant methods
- **Secondary:** developer — uses the same commands in the console

---

## Acceptance Criteria

- [x] `sbtn "graphPath A B"` returns valid JSON with a path from A to B (or `{"found": false}`)
- [x] `sbtn "graphVia V"` returns the neighbourhood of vertex V
- [x] JSON response includes for each method: full name (`id`), file, line number
- [x] First call after compilation completes in < 10 sec (indexing)
- [x] Subsequent call (cache hit) completes in < 500 ms
- [x] After `compile`, the cache is invalidated automatically (via compileAnalysisFile mtime)
- [x] Query with a non-existent vertex returns `{"found": false}`, not a stack trace
- [x] If no path exists — `{"found": false}`
- [x] `maxDepth=20`, `maxPaths=100` by default; configurable via task arguments
- [x] When the limit is reached — `"truncated": true` in the response
- [x] Cycles do not cause infinite search (simple paths only)
- [x] Parallel `sbtn` calls do not corrupt the cache
- [x] `sbtn "graphIndex"` returns diagnostics: node/edge counts, timestamp

## Out of Scope (v1)

- HTTP server / REST API
- Incremental indexing
- Supporting multiple graphs simultaneously
- UI/TUI for humans
- Graph metrics (fan-in, centrality, etc.)

## Backlog (v2)

- Annotations/tags on vertices (`@api`, `@db`, `@rest`) — set manually
- `transparent` vertices — excluded from paths (pass-through transit nodes)
- Cardinality filtering (automatically remove noisy hubs)

---

## Architecture

### Stack

| Layer               | Tool                                                                                    |
|---------------------|-----------------------------------------------------------------------------------------|
| Semantic analysis   | Scala compiler + `semanticdb-scalac` (already enabled via sbt-scalafix)                |
| Artifacts           | `target/scala-2.13/meta/**/*.semanticdb` — generated on every `compile`                |
| Reading artifacts   | `scalameta` — reads `.semanticdb` protobuf, provides `TextDocuments`                   |
| Call extraction     | Walk `SymbolOccurrence` references to METHOD symbols; caller = nearest definition above |
| In-memory graph     | `Map[String, Set[String]]` (out) + `Map[String, Set[String]]` (in) + `Map[String, NodeMeta]` |
| Path search         | Custom DFS with visited set + depth/paths counter                                       |
| JSON                | Hand-rolled serialization (no dependencies)                                             |
| Output              | `target/call-graph/N.json` (path printed to stdout)                                    |
| Interface           | SBT tasks, invoked via `sbtn`                                                           |

### How SemanticDB provides the call graph

`.semanticdb` files contain for each source file: symbol definitions + all symbol occurrences with positions. Via scalameta's protobuf API:

1. Parse `TextDocuments` from each `.semanticdb` file
2. Build a `Map[String, SymbolInformation.Kind]` to identify METHOD symbols
3. For each file, walk `SymbolOccurrence` entries with `Role.REFERENCE`
4. If the referenced symbol is a METHOD, find the enclosing method definition (nearest definition above by line)
5. Record `caller -> callee` edge

No additional plugins required — scalafix already enables `.semanticdb` generation.

### Caching

Three-level per-file cache in `GraphLoader`:
1. `docsCache` — parsed `TextDocuments` protobuf, keyed by file mtime
2. `endLineCache` — scalameta-parsed end lines, keyed by source file mtime
3. `contribCache` — per-file `FileContrib(meta, edges, kinds)`, keyed by `.semanticdb` file mtime

`CallGraphState` uses the mtime of SBT's `compileAnalysisFile` as a single stamp to detect recompilation. On a warm cache with 1 changed file out of 660+, reload takes ~50ms instead of 6-9s.

---

## Trade-offs

- **`var` instead of SBT `AttributeKey`** — non-standard but much simpler. `AttributeKey` requires threading state through all tasks. For an isolated plugin the risk is minimal.
- **Custom DFS instead of JGraphT** — zero dependencies, full control over early-exit and limits. Sufficient for sparse project graphs. JGraphT can be added later if complex algorithms (centrality, isomorphism) are needed.
- **SemanticDB instead of scg-cli** — no external plugin required, works with any Scala version that supports semanticdb-scalac. Downside: had to implement reference walking ourselves.
- **Output to file, not stdout** — more reliable for LLM consumption (stdout in SBT is polluted with `[info]` prefixes and ANSI codes).
- **Universal `GraphResult`** — both path queries and neighbourhood queries return `(nodes, edges, truncated)`. Simplifies output code and makes the API consistent.
