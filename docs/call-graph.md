# call-graph — Claude Skill Guide

Use the `sbt-call-graph` plugin to navigate the call graph of any Scala project when you need to understand how methods relate without reading entire files.

The plugin loads SemanticDB from **all enabled modules** automatically — no need to switch projects.

---

## When to use

**User mentions a single method or class**
→ Run `graphVia` to see what calls it and what it calls.
> "Why is `QueryEngine#viaVertex` returning empty results?"
> → `graphVia io/github/twopit/callgraph/QueryEngine.viaVertex().` shows the neighbourhood of that method.

**User mentions multiple methods or asks about data/control flow**
→ Run `graphPath` to find how methods reach each other. Accepts 2 or more vertices — paths are found between all pairs.
> "How does the graph loading flow into the query engine?"
> → `graphPath io/github/twopit/callgraph/GraphLoader.load(+1). io/github/twopit/callgraph/QueryEngine.viaVertex().`

**User wants to refactor or split a component**
→ Run `graphVia` on each candidate method. The number of edges pointing in (fan-in) shows how many callers depend on it; edges pointing out (fan-out) show how much it owns.

**Module structure is unknown**
→ Run `graphIndex` for scale (node/edge counts), then `graphVia` on the entry point.

**FQN is unknown / vertex was not found**
→ Run `graphSearch` with a class or method name substring to find the correct FQN.
> `graphSearch GraphLoader` returns all matching vertices with their IDs.

**Analysing cross-module coupling**
→ Run `graphModule` with a path prefix to see all call edges that cross the module boundary.
> `graphModule modules/analyzer` shows what the analyzer module calls outside itself and who calls into it.

---

## How to find the FQN

SemanticDB symbol format: `package/ClassOrObject#method().`

| Element        | Separator | Example                                    |
|----------------|-----------|--------------------------------------------|
| Package        | `/`       | `io/github/twopit/callgraph/`          |
| Object         | `.`       | `GraphLoader.`                             |
| Class / Trait  | `#`       | `QueryEngine#`                             |
| Method         | `().`     | `viaVertex().`                             |

Full example: `io/github/twopit/callgraph/QueryEngine.viaVertex().`

**If the exact FQN is unknown:**
1. Run `graphSearch <name>` — returns all vertices whose FQN or displayName contains the substring.
2. Pick the `id` from the matching entry and use it in `graphVia` / `graphPath`.
3. If `graphSearch` returns nothing — `Grep` the source for the class name to confirm the package, then compose the FQN from the table above.

---

## Commands

All commands are run inside the SBT shell on the module with the plugin enabled:

```
# check graph is loaded (node/edge counts)
myModule/graphIndex

# search for a vertex by class/method name (use when FQN is unknown)
myModule/graphSearch GraphLoader
myModule/graphSearch GraphLoader --maxResults 20

# neighbourhood of a method (default --depth 2 in both directions)
myModule/graphVia io/github/twopit/callgraph/QueryEngine.viaVertex().

# asymmetric depth: 3 hops for callers, 1 hop for callees
myModule/graphVia io/github/twopit/callgraph/QueryEngine.viaVertex(). --depthIn 3 --depthOut 1

# deeper exploration, same depth in both directions
myModule/graphVia io/github/twopit/callgraph/QueryEngine.viaVertex(). --depth 4

# path between two methods
myModule/graphPath io/github/twopit/callgraph/GraphLoader.load(+1). io/github/twopit/callgraph/CallGraphState.getOrLoad().

# path among 3+ methods (finds paths between all pairs)
myModule/graphPath A B C --maxDepth 15 --maxPaths 50

# cross-module coupling: all call edges crossing a module boundary
myModule/graphModule modules/analyzer
myModule/graphModule modules/plugin
```

### Output formats

All query commands (`graphPath`, `graphVia`) support `--format`:

```
myModule/graphVia io/github/twopit/callgraph/QueryEngine.viaVertex(). --format html
myModule/graphPath A B --format md
```

| Format   | Flag             | Description                              |
|----------|------------------|------------------------------------------|
| JSON     | (default)        | Machine-readable nodes + edges           |
| HTML     | `--format html`  | Interactive graph with pan/zoom/collapse  |
| Markdown | `--format md`    | Mermaid flowchart for embedding in docs   |
| DOT      | `--format dot`   | Graphviz DOT for external rendering       |

### Filtering

Use `--filterOut` to exclude nodes matching regex patterns (comma-separated):

```
myModule/graphVia io/github/twopit/callgraph/QueryEngine.viaVertex(). --filterOut "io/github/twopit/callgraph/Output.*"
```

Each command triggers incremental compilation automatically before querying.

**Important:** the result file path is printed to stdout (last line). Timing diagnostics are only visible at debug log level (`set logLevel := Level.Debug`).

---

## Compile errors

If the project fails to compile, the plugin **still runs the query** against the last successfully compiled graph and sets `"compileError": true` in the result. Always check for this flag — the graph may be stale.

```json
{
  "query":        { "vertex": "...", "depthIn": 2, "depthOut": 2 },
  "found":        true,
  "truncated":    false,
  "nodes":        [ ... ],
  "edges":        [ ... ],
  "compileError": true
}
```

---

## Reading the result

Result file: `target/call-graph/N.json` (N increments each call, never overwritten)

### Unified output format (graphVia / graphPath)

Both `graphVia` and `graphPath` return the same structure — a flat list of nodes and edges:

```json
{
  "query":     { "vertex": "io/github/twopit/callgraph/QueryEngine.viaVertex().", "depthIn": 2, "depthOut": 2 },
  "found":     true,
  "truncated": false,
  "nodes": [
    { "id": "io/github/twopit/callgraph/QueryEngine.viaVertex().", "displayName": "viaVertex", "file": "modules/analyzer/.../QueryEngine.scala", "startLine": 40, "endLine": 55 },
    { "id": "io/github/twopit/callgraph/QueryEngine.search().", "displayName": "search", "file": "...", "startLine": 59, "endLine": 65 },
    ...
  ],
  "edges": [
    { "from": "io/github/twopit/callgraph/QueryEngine.viaVertex().", "to": "io/github/twopit/callgraph/QueryEngine.search()." },
    ...
  ]
}
```

- `nodes` — all vertices in the result subgraph, sorted by `(file, startLine)`
- `edges` — directed call edges between nodes (`from` calls `to`)
- `found` — `true` if any nodes were returned
- `truncated` — `true` if `--maxPaths` limit was hit (graphPath only)
- `readHints` — source file ranges to read, grouped by file; ranges within 10 lines of each other are merged

For `graphPath`, the query field contains `"vertices"` instead of `"vertex"`:
```json
{ "query": { "vertices": ["A", "B", "C"] }, ... }
```

**To read relevant source**, use `readHints` instead of reading each node individually:
```json
"readHints": [
  { "file": "modules/analyzer/.../QueryEngine.scala",
    "ranges": [ {"start": 40, "end": 65}, {"start": 110, "end": 130} ] }
]
```
```
Read(hint.file, offset = range.start - 1, limit = range.end - range.start + 1)
```

### graphSearch response

```json
{
  "query":   "GraphLoader",
  "count":   2,
  "matches": [
    { "id": "io/github/twopit/callgraph/GraphLoader.", "displayName": "GraphLoader", "file": "modules/analyzer/.../GraphLoader.scala", "startLine": 8, "endLine": 8 },
    { "id": "io/github/twopit/callgraph/GraphLoader.load(+1).", "displayName": "load", "file": "...", "startLine": 15, "endLine": 15 }
  ]
}
```

Use the `id` from a match as the vertex argument in `graphVia` or `graphPath`.

### graphModule response

```json
{
  "query": { "prefix": "modules/analyzer" },
  "outgoing": [
    { "from": { "id": "io/github/twopit/callgraph/GraphLoader.load(+1).", ... },
      "to":   { "id": "scala/meta/internal/semanticdb/TextDocuments.parseFrom().", ... } },
    ...
  ],
  "incoming": [
    { "from": { "id": "io/github/twopit/callgraph/CallGraphPlugin.graphViaTask().", ... },
      "to":   { "id": "io/github/twopit/callgraph/CallGraphState.getOrLoad().", ... } },
    ...
  ]
}
```

- `outgoing` — calls leaving the module (what this module depends on externally)
- `incoming` — calls entering the module (what calls into this module from outside)
- Only edges where both endpoints are known in the graph are included (stdlib/library calls excluded)

---

## Limitations

- **`graphSearch` is case-sensitive** — use the exact casing of the class/method name
- **Method-level only** — inheritance and type relationships are not in the graph
- **`val` fields** appear as nodes with `endLine == startLine`
- **Implicit conversions and for-comprehension** may be partially missing
- **DFS path order** — `graphPath` results are not sorted by length
- **`pathsAmong` direction** — paths are searched from earlier vertices to later ones in the argument list (forward direction only); reverse paths require swapping the order
