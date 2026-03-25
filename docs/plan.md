# Development Plan: sbt-call-graph

**Date:** 2026-03-04

Implementation steps in execution order.

---

## Step 0 — Phase 0: SemanticDB exploration (prerequisite)

**Goal:** confirm that method-level calls can be extracted from `.semanticdb` files.

- [x] Write minimal `Main.scala` in `analyzer/`: read one `.semanticdb` file, print all symbols and occurrences
- [x] Run on a real project's `target/scala-2.13/meta/`
- [x] Confirm that `SymbolOccurrence.Role.REFERENCE` on METHOD symbols yields real method FQNs
- [x] Record: vertex FQN format, line numbering (0 or 1), coverage (implicit calls, for-comprehension, etc.)

**Done when:** for a known caller/callee pair, the edge is present in the output.

---

## Step 1 — GraphLoader

- [x] Recursively find all `*.semanticdb` in a given directory
- [x] For each: parse `TextDocuments` protobuf, walk symbol occurrences
- [x] Extract `(callerFQN, calleeFQN)` pairs from METHOD references
- [x] Build `out: Map[String, Set[String]]` and `in: Map[String, Set[String]]`
- [x] Collect `meta: Map[String, NodeMeta]` (file, startLine, endLine, displayName)
- [x] Warning if `out` is empty after loading
- [x] Three-level per-file caching (docs, endLines, contributions)

---

## Step 2 — QueryEngine

- [x] `pathAtoB(graph, from, to, maxDepth, maxPaths)` — DFS with visited set, early-exit on maxPaths
- [x] `pathsAmong(graph, vertices, maxDepth, maxPaths)` — multi-vertex path search (N DFS runs with multi-target)
- [x] `viaVertex(graph, v, depthIn, depthOut)` — BFS neighbourhood + induced subgraph edges
- [x] `search(graph, query, maxResults)` — substring search on FQN/displayName
- [x] `moduleEdges(graph, pathPrefix)` — cross-module boundary edges
- [x] Universal `GraphResult(nodes, edges, truncated)` return type

---

## Step 3 — CallGraphState

- [x] `@volatile var` with `synchronized` writes
- [x] `compileAnalysisFile` mtime as invalidation stamp (single stat() instead of walking all .semanticdb files)

---

## Step 4 — Output

- [x] `JsonOutput` — nodes, edges, readHints (merged file ranges)
- [x] `DotOutput` — Graphviz DOT with subgraph clusters per class
- [x] `HtmlOutput` — interactive HTML with viz.js, svg-pan-zoom, collapse/expand, node selection
- [x] `MermaidOutput` — Mermaid flowchart

---

## Step 5 — Main CLI

- [x] `run demo [outfile]` — self-contained demo HTML graph
- [x] `run <dir>` — print stats
- [x] `run <dir> path A B` — find paths, write JSON
- [x] `run <dir> via V` — neighbourhood, write JSON
- [x] `run <dir> search Q` — search vertices
- [x] `run <dir> module P` — cross-module edges

---

## Step 6 — SBT Plugin

- [x] `AutoPlugin` with `trigger = noTrigger`
- [x] Tasks: `graphPath`, `graphVia`, `graphSearch`, `graphModule`, `graphIndex`
- [x] `--format json|html|md|dot` flag on all query tasks
- [x] `--filterOut` regex filter
- [x] Timing diagnostics at debug log level

---

## Step 7 — Scripted test

- [x] Minimal test project with caller/callee classes
- [x] Scripted test: compile → graphPath → verify output file exists

---

## Step 8 — Integration

- [x] `publishLocal` and enable plugin on target project
- [x] Verify all tasks on real codebase

---

## Step 11 — For-comprehension / synthetic edges

- [x] Read `doc.synthetics` in `GraphLoader`, extract METHOD symbols by recursive walk of `Synthetic.tree`
- [x] Handle `SelectTree`, `ApplyTree`, `FunctionTree` synthetics
- [x] Override dispatch edges via `overriddenSymbols`

---

## Current Status

| Step | Status |
|------|--------|
| 0 — Phase 0 exploration | done |
| 1 — GraphLoader | done |
| 2 — QueryEngine | done |
| 3 — CallGraphState | done |
| 4 — Output (JSON, DOT, HTML, Mermaid) | done |
| 5 — Main CLI | done |
| 6 — SBT Plugin | done |
| 7 — Scripted test | done |
| 8 — Integration | done |
| 11 — Synthetics (for-comprehension edges) | done |
