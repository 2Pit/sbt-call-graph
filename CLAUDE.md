# CLAUDE.md — sbt-call-graph

Standalone analyzer + MCP server for building and querying method-level call graphs of Scala
projects via SemanticDB. (A former SBT plugin was removed — querying is now done through the MCP
server, not sbt tasks.)

---

## Project Structure

```
sbt-graph-exporter/
  build.sbt                          <- root build (2 modules: analyzer, mcp-server)
  project/
    build.properties                 <- sbt 1.12.11
    plugins.sbt                      <- scalafmt, dynver, assembly, buildinfo
  modules/
    analyzer/                        <- standalone Scala 2.13 library (core)
      src/main/scala/io/github/twopit/callgraph/
        model.scala                  <- NodeMeta, LoadedGraph, GraphResult
        GraphLoader.scala            <- SemanticDB -> (out, in, meta) maps
        QueryEngine.scala            <- pathAtoB / pathsAmong / viaVertex
        CallGraphState.scala         <- @volatile var + mtime invalidation
        JsonOutput.scala             <- JSON serialization with readHints
        DotOutput.scala              <- Graphviz DOT output
        HtmlOutput.scala             <- interactive HTML graph (viz.js)
        MermaidOutput.scala          <- Mermaid flowchart output
        Main.scala                   <- CLI: stats / path / via / demo
      src/test/scala/                <- unit tests (MUnit)
    mcp-server/                      <- MCP server Scala 2.13 (depends on analyzer)
      src/main/scala/io/github/twopit/callgraph/mcp/
        Main.scala               <- entry point, stdio transport, McpServer wiring
        GraphService.scala       <- semanticdb-dir discovery + mtime stamp -> CallGraphState
        ToolHandlers.scala       <- five SyncToolSpecifications (graphIndex/Search/Via/Path/Module)
  docs/
    call-graph.md                    <- Claude Skill guide (usage reference)
    spec.md                          <- original requirements and architecture
    plan.md                          <- implementation plan with status
    usage.md                         <- user-facing usage guide
```

Scala 2.13.18 / sbt 1.12.11 — kept in lockstep with the `blank-slate-server` backend so the
analyzer compiles against the same toolchain it analyses. (The version was previously pinned to
2.12 only because sbt plugins must be 2.12; that constraint is gone with the plugin.)

---

## Build & Run

```sh
# compile everything
sbt compile

# run tests
sbt "analyzer/test"
sbt "mcpServer/test"

# publish the analyzer locally (only consumer is the mcp-server, via project dep)
sbt "analyzer/publishLocal"

# standalone CLI (demo HTML graph)
sbt "analyzer/run demo graph-demo.html"

# build MCP-server fat-jar (-> modules/mcp-server/target/scala-2.13/call-graph-mcp.jar)
sbt "mcpServer/assembly"
```

The MCP server is wired into the monorepo via `utils/mcp/mcp.json`, which runs the assembled jar
at `modules/mcp-server/target/scala-2.13/call-graph-mcp.jar`. After any change that affects the
server, re-run `mcpServer/assembly` and reconnect the call-graph MCP (a stale jar is loaded until
the MCP process restarts).

---

## MCP Tools

Five tools: `graphIndex` (diagnostics), `graphSearch`, `graphVia`, `graphPath`, `graphModule`.

Every tool takes a **required** `worktree` selector — there is no default:

- `worktree: "."` queries the **main checkout**.
- `worktree: "<name>"` queries the worktree at `.worktrees/<name>/` in isolation (its overflow
  files land in that worktree's `target/call-graph`).

Requiring an explicit choice prevents silently serving a stale main-checkout graph while you are
working inside a worktree. Omitting `worktree` (or passing `""`) is an error.

---

## Key Design Decisions

- **SemanticDB as data source** — `.semanticdb` files are generated during `compile` by `semanticdb-scalac`. No additional plugins required beyond what scalafix already provides.
- **Edge extraction** — via `SymbolOccurrence.Role.REFERENCE` on `Kind.METHOD` in `.semanticdb` (no AST walk over `Term.Apply`). The caller is the nearest method definition above by line number.
- **Vertex FQN** — SemanticDB format, e.g.: `io/github/twopit/callgraph/GraphLoader.load(+1).`
- **startLine** — 0-based internally (as stored in SemanticDB protobuf); 1-based in JSON output.
- **endLine** — parsed separately from `.scala` source via scalameta; falls back to startLine if source is unavailable.
- **Caching** — three-level cache in GraphLoader (protobuf docs, scalameta endLines, per-file contributions); mtime-based invalidation via `compileAnalysisFile`.
- **Output** — writes JSON/HTML/DOT/Mermaid to `target/call-graph/N.{json,html,dot,md}` (N auto-increments). The file path is printed to stdout.
- **Universal result type** — `GraphResult(nodes, edges, truncated)` used by both `pathAtoB`/`pathsAmong` and `viaVertex`. All output formats consume the same structure.

---

## Output Formats

All query commands support `--format json|html|md|dot`:

| Format   | Extension | Description                              |
|----------|-----------|------------------------------------------|
| JSON     | `.json`   | Machine-readable nodes + edges + readHints |
| HTML     | `.html`   | Interactive graph with pan/zoom/collapse  |
| Markdown | `.md`     | Mermaid flowchart                        |
| DOT      | `.dot`    | Graphviz DOT                             |

---

## JSON Output Format

```json
{
  "query":     { "vertices": ["A", "B"] },
  "found":     true,
  "truncated": false,
  "nodes":     [ { "id": "...", "displayName": "bar", "file": "...", "startLine": 42, "endLine": 55 } ],
  "edges":     [ { "from": "...", "to": "..." } ],
  "readHints": [ { "file": "...", "ranges": [ { "start": 40, "end": 60 } ] } ]
}
```

---

## MCP Output Mode

To keep agent context lean, the MCP server diverts large tool responses to disk by default:

- **auto** (default) — responses < 8 KB are returned inline; larger responses are written to `<root>/target/call-graph/N.json` and the inline reply is replaced by `{ file, found, truncated, nodes, edges, previewNodes, readHints, note }`.
- **inline** — every tool returns the full JSON (escape hatch for known-small queries).
- **file** — every tool writes to disk (escape hatch for known-large queries).
- `graphIndex` is always inline regardless of `mode`.

Files are named monotonically via `OutputCounter`. Cleanup: `sbt clean` of the root project, or `rm -rf target/call-graph`.

Knobs live in `ToolHandlers.scala`:
- `AutoInlineThresholdBytes = 8192`
- `PreviewNodeLimit = 10`
</content>
</invoke>
