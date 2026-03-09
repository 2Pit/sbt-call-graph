# call-graph — Claude Skill Guide

Use the `sbt-graph-explorer` plugin to navigate the call graph of `blank-slate-server` when you need to understand how methods relate without reading entire files.

The plugin loads SemanticDB from **all modules** (`srs-study-ws`, `srs-common`, etc.) automatically — no need to switch projects.

---

## When to use

**User mentions a single method or class**
→ Run `graphVia` to see what calls it and what it calls.
> "Why is `SessionLive#close` sometimes not invoked?"
> → `graphVia sreo/session/SessionLive#close().` shows the neighbourhood of that method.

**User mentions multiple methods or asks about data/control flow**
→ Run `graphPath` to find how methods reach each other. Accepts 2 or more vertices — paths are found between all pairs.
> "How does the API request get to the database?"
> → `graphPath sreo/ws/RoutesLive#sessionRoute(). sreo/session/SessionRepository#close().`

**User wants to refactor or split a component**
→ Run `graphVia` on each candidate method. The number of edges pointing in (fan-in) shows how many callers depend on it; edges pointing out (fan-out) show how much it owns.

**Module structure is unknown**
→ Run `graphIndex` for scale (node/edge counts), then `graphVia` on the entry point.

**FQN is unknown / vertex was not found**
→ Run `graphSearch` with a class or method name substring to find the correct FQN.
> `graphSearch ScheduleRepositoryLive` returns all matching vertices with their IDs.

**Analysing cross-module coupling**
→ Run `graphModule` with a path prefix to see all call edges that cross the module boundary.
> `graphModule db/db-learning` shows what db-learning calls outside itself and who calls into it.

---

## How to find the FQN

SemanticDB symbol format: `package/ClassOrObject#method().`

| Element        | Separator | Example              |
|----------------|-----------|----------------------|
| Package        | `/`       | `sreo/session/`      |
| Object         | `.`       | `SessionLive.`       |
| Class / Trait  | `#`       | `SessionLive#`       |
| Method         | `().`     | `close().`           |

Full example: `sreo/session/SessionLive#close().`

**If the exact FQN is unknown:**
1. Run `graphSearch <name>` — returns all vertices whose FQN or displayName contains the substring.
2. Pick the `id` from the matching entry and use it in `graphVia` / `graphPath`.
3. If `graphSearch` returns nothing — `Grep` the source for the class name to confirm the package, then compose the FQN from the table above.

---

## Commands

All commands are run inside the `blank-slate-server` sbt shell:

```
# check graph is loaded (node/edge counts)
studyWs/graphIndex

# search for a vertex by class/method name (use when FQN is unknown)
studyWs/graphSearch ScheduleRepositoryLive
studyWs/graphSearch ScheduleRepositoryLive --maxResults 20

# neighbourhood of a method (default --depth 2 in both directions)
studyWs/graphVia sreo/session/SessionLive#close().

# asymmetric depth: 3 hops for callers, 1 hop for callees
studyWs/graphVia sreo/session/SessionLive#close(). --depthIn 3 --depthOut 1

# deeper exploration, same depth in both directions
studyWs/graphVia sreo/session/SessionLive#close(). --depth 4

# path between two methods
studyWs/graphPath sreo/session/SessionLive#closeOnResult(). sreo/session/SessionLive#closeSession().

# path among 3+ methods (finds paths between all pairs)
studyWs/graphPath A B C --maxDepth 15 --maxPaths 50

# cross-module coupling: all call edges crossing a module boundary
studyWs/graphModule db/db-learning
studyWs/graphModule db/db-reporting
```

### Output formats

All query commands (`graphPath`, `graphVia`) support `--format`:

```
studyWs/graphVia sreo/session/SessionLive#close(). --format html
studyWs/graphPath A B --format md
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
studyWs/graphVia sreo/session/SessionLive#close(). --filterOut "sreo/db/.*,sreo/tkl/.*"
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

Result file: `blank-slate-server/srs-study-ws/target/call-graph/N.json` (N increments each call, never overwritten)

### Unified output format (graphVia / graphPath)

Both `graphVia` and `graphPath` return the same structure — a flat list of nodes and edges:

```json
{
  "query":     { "vertex": "sreo/session/SessionLive#close().", "depthIn": 2, "depthOut": 2 },
  "found":     true,
  "truncated": false,
  "nodes": [
    { "id": "sreo/session/SessionLive#close().", "displayName": "close", "file": "srs-study-ws/.../SessionLive.scala", "startLine": 105, "endLine": 110 },
    { "id": "sreo/session/SessionLive#closeSession().", "displayName": "closeSession", "file": "...", "startLine": 92, "endLine": 98 },
    ...
  ],
  "edges": [
    { "s": "sreo/session/SessionLive#close().", "t": "sreo/session/SessionLive#closeSession()." },
    ...
  ]
}
```

- `nodes` — all vertices in the result subgraph, sorted by `(file, startLine)`
- `edges` — directed call edges between nodes (`s` calls `t`)
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
  { "file": "srs-study-ws/.../SessionLive.scala",
    "ranges": [ {"start": 92, "end": 120}, {"start": 145, "end": 160} ] }
]
```
```
Read("blank-slate-server/" + hint.file, offset = range.start - 1, limit = range.end - range.start + 1)
```

### graphSearch response

```json
{
  "query":   "ScheduleRepositoryLive",
  "count":   2,
  "matches": [
    { "id": "sreo/db/repository/ScheduleRepositoryLive#.", "displayName": "ScheduleRepositoryLive", "file": "db/db-learning/.../ScheduleRepositoryLive.scala", "startLine": 12, "endLine": 12 },
    { "id": "sreo/db/repository/ScheduleRepositoryLayer#.", "displayName": "ScheduleRepositoryLayer", "file": "...", "startLine": 5, "endLine": 5 }
  ]
}
```

Use the `id` from a match as the vertex argument in `graphVia` or `graphPath`.

### graphModule response

```json
{
  "query": { "prefix": "db/db-learning" },
  "outgoing": [
    { "from": { "id": "sreo/db/repository/ScheduleRepositoryLive#scheduleForUser().", ... },
      "to":   { "id": "sreo/db/query/PartnerQ.getByUser().", ... } },
    ...
  ],
  "incoming": [
    { "from": { "id": "sreo/session/SessionLive#open().", ... },
      "to":   { "id": "sreo/db/repository/ScheduleRepositoryLive#find().", ... } },
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
