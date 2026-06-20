# call-graph — Claude Skill Guide

Use the `call-graph` MCP server to navigate the call graph of any Scala project when you need to understand how methods relate without reading entire files.

The server loads SemanticDB from the target project automatically — no SBT tasks required.

---

## When to use

**User mentions a single method or class**
→ Run `graphVia` to see what calls it and what it calls.
> "Why is `QueryEngine#viaVertex` returning empty results?"
> → `graphVia vertex="io/github/twopit/callgraph/QueryEngine.viaVertex()." worktree="."` shows the neighbourhood of that method.

**User mentions multiple methods or asks about data/control flow**
→ Run `graphPath` to find how methods reach each other. Accepts 2 or more vertices — paths are found between all pairs.
> "How does the graph loading flow into the query engine?"
> → `graphPath vertices=["io/github/twopit/callgraph/GraphLoader.load(+1).", "io/github/twopit/callgraph/QueryEngine.viaVertex()."] worktree="."`

**User wants to refactor or split a component**
→ Run `graphVia` on each candidate method. The number of edges pointing in (fan-in) shows how many callers depend on it; edges pointing out (fan-out) show how much it owns.

**Module structure is unknown**
→ Run `graphIndex` for scale (node/edge counts), then `graphVia` on the entry point.

**FQN is unknown / vertex was not found**
→ Run `graphSearch` with a class or method name substring to find the correct FQN.
> `graphSearch query="GraphLoader" worktree="."` returns all matching vertices with their IDs.

**Analysing cross-module coupling**
→ Run `graphModule` with a path prefix to see all call edges that cross the module boundary.
> `graphModule prefix="modules/analyzer" worktree="."` shows what the analyzer module calls outside itself and who calls into it.

---

## Worktree parameter

Every tool requires a `worktree` argument — there is no default:

- `worktree: "."` — query the **main checkout**
- `worktree: "<name>"` — query the worktree at `.worktrees/<name>/` in isolation; overflow files land in that worktree's `target/call-graph/`

Omitting `worktree` (or passing `""`) is an error. Always pass `worktree: "."` when working in the main checkout, and `worktree: "<slug>"` when working in a named worktree.

The graph for a worktree only exists after that worktree has been compiled (`sbt compile`) so its `.semanticdb` is present; otherwise `graphIndex worktree="<name>"` reports `notCompiled`.

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
1. Run `graphSearch query="<name>" worktree="."` — returns all vertices whose FQN or displayName contains the substring.
2. Pick the `id` from the matching entry and use it in `graphVia` / `graphPath`.
3. If `graphSearch` returns nothing — `Grep` the source for the class name to confirm the package, then compose the FQN from the table above.

---

## Tools

```
# check graph is loaded (node/edge counts)
graphIndex  worktree="."

# search for a vertex by class/method name (use when FQN is unknown)
graphSearch  query="GraphLoader"  worktree="."
graphSearch  query="GraphLoader"  worktree="."  maxResults=20

# neighbourhood of a method (default depth 2 in both directions)
graphVia  vertex="io/github/twopit/callgraph/QueryEngine.viaVertex()."  worktree="."

# asymmetric depth: 3 hops for callers, 1 hop for callees
graphVia  vertex="io/github/twopit/callgraph/QueryEngine.viaVertex()."  worktree="."  depthIn=3  depthOut=1

# deeper exploration, same depth in both directions
graphVia  vertex="io/github/twopit/callgraph/QueryEngine.viaVertex()."  worktree="."  depth=4

# path between two methods
graphPath  vertices=["io/github/twopit/callgraph/GraphLoader.load(+1).", "io/github/twopit/callgraph/CallGraphState.getOrLoad()."]  worktree="."

# path among 3+ methods (finds paths between all pairs)
graphPath  vertices=["A", "B", "C"]  worktree="."  maxDepth=15  maxPaths=50

# cross-module coupling: all call edges crossing a module boundary
graphModule  prefix="modules/analyzer"  worktree="."

# query a named worktree instead of the main checkout
graphVia  vertex="sreo/study/StudySessionService#start()."  worktree="BS2026-1234"
```

### Output formats

`graphVia` and `graphPath` support a `format` argument:

| Format   | Value            | Description                              |
|----------|------------------|------------------------------------------|
| JSON     | (default)        | Machine-readable nodes + edges           |
| HTML     | `html`           | Interactive graph with pan/zoom/collapse  |
| Markdown | `md`             | Mermaid flowchart for embedding in docs   |
| DOT      | `dot`            | Graphviz DOT for external rendering       |

### Filtering

Use `filterOut` to exclude nodes matching regex patterns (comma-separated):

```
graphVia  vertex="io/github/twopit/callgraph/QueryEngine.viaVertex()."  worktree="."  filterOut="io/github/twopit/callgraph/Output.*"
```

---

## Compile errors

If the project fails to compile, the server **still runs the query** against the last successfully compiled graph and sets `"compileError": true` in the result. Always check for this flag — the graph may be stale.

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
    { "from": { "id": "io/github/twopit/callgraph/Main.main().", ... },
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
