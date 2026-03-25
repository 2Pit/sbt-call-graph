# CLAUDE.md — sbt-call-graph

SBT plugin + standalone analyzer for building and querying call graphs of Scala projects via SemanticDB.

---

## Project Structure

```
sbt-graph-exporter/
  build.sbt                          <- root build (2 modules: analyzer, plugin)
  project/
    build.properties                 <- sbt 1.10.7
    plugins.sbt                      <- sbt-scalafmt
  modules/
    analyzer/                        <- standalone Scala 2.12 library (core)
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
    plugin/                          <- SBT plugin Scala 2.12
      src/main/scala/
        CallGraphPlugin.scala    <- AutoPlugin with graphPath/graphVia/graphSearch/graphModule/graphIndex tasks
      src/sbt-test/
        call-graph/basic/            <- scripted test
  docs/
    call-graph.md                    <- Claude Skill guide (usage reference)
    spec.md                          <- original requirements and architecture
    plan.md                          <- implementation plan with status
    usage.md                         <- user-facing usage guide
```

---

## Build & Run

```sh
# compile everything
sbtn compile

# run tests
sbtn "analyzer/test"

# publish both modules locally
sbtn "analyzer/publishLocal; plugin/publishLocal"

# scripted tests (publishes analyzer first)
sbtn "analyzer/publishLocal; plugin/scripted"

# standalone CLI (demo HTML graph)
sbtn "analyzer/run demo graph-demo.html"
```

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
