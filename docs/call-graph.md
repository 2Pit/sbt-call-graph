# call-graph — Claude Skill Guide

Use the `sbt-graph-explorer` plugin to navigate the call graph of `blank-slate-server` when you need to understand how methods relate without reading entire files.

---

## When to use

**User mentions a single method or class**
→ Run `graphVia` to see what calls it and what it calls.
> "Why is `SessionLive#close` sometimes not invoked?"
> → `graphVia sreo/session/SessionLive#close().` shows who is responsible for calling it.

**User mentions multiple methods or asks about data/control flow**
→ Run `graphPath` to find how one method reaches another.
> "How does the API request get to the database?"
> → `graphPath sreo/ws/RoutesLive#sessionRoute(). sreo/session/SessionRepository#close().`

**User wants to refactor or split a component**
→ Run `graphVia` on each candidate method. The size of `in` (fan-in) shows how many callers depend on it; `out` (fan-out) shows how much it owns.

**Module structure is unknown**
→ Run `graphIndex` for scale (node/edge counts), then `graphVia` on the entry point.

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
1. `Grep` the source for the class/object name to confirm the package
2. Compose the FQN from the rules above
3. If "vertex not found" — run `graphIndex`, then search the result file:
```sh
grep '"displayName".*close' blank-slate-server/srs-study-ws/target/call-graph/$(ls -t blank-slate-server/srs-study-ws/target/call-graph/ | head -1)
```
The `id` field of the matching entry is the correct FQN.

---

## Commands

All commands are run inside the `blank-slate-server` sbt shell:

```
# check graph is loaded (node/edge counts)
studyWs/graphIndex

# neighbourhood of a method (default --depth 2)
studyWs/graphVia sreo/session/SessionLive#close().

# deeper exploration
studyWs/graphVia sreo/session/SessionLive#close(). --depth 4

# path between two methods
studyWs/graphPath sreo/session/SessionLive#closeOnResult(). sreo/session/SessionLive#closeSession().

# with limits
studyWs/graphPath A B --maxDepth 15 --maxPaths 50
```

Each command triggers incremental compilation automatically before querying.

**Important:** pipe through `tail -5` to suppress sbt log noise — only the result file path matters:
```sh
cd blank-slate-server && sbtn "studyWs/graphVia sreo/session/SessionLive#close()." 2>&1 | tail -5
```

---

## Reading the result

Result file: `blank-slate-server/srs-study-ws/target/call-graph/N.json` (N increments each call, never overwritten)

### graphVia response

```json
{
  "query":  { "vertex": "sreo/session/SessionLive#close().", "depthIn": 2, "depthOut": 2 },
  "vertex": { "id": "...", "displayName": "close", "file": "srs-study-ws/.../SessionsLive.scala", "startLine": 105, "endLine": 110 },
  "in":  [ { "id": "...", "displayName": "closeOnResult", "file": "...", "startLine": 113, "endLine": 120, "depth": 1 }, ... ],
  "out": [ { "id": "...", "displayName": "closeSession",  "file": "...", "startLine": 92,  "endLine": 98,  "depth": 1 }, ... ]
}
```

- `in` — methods that call the queried vertex (callers), sorted by `(depth, file, startLine)`
- `out` — methods the queried vertex calls (callees), same sort order
- `depth` on each node = number of BFS hops from the queried vertex

**To read a specific method's source:**
```
Read("blank-slate-server/" + node.file, offset = node.startLine - 1, limit = node.endLine - node.startLine + 1)
```

### graphPath response

Each path is an ordered list of nodes — read them in sequence to follow the call chain. Paths are not guaranteed to be in any particular order; set `--maxPaths` to limit output volume.

---

## Limitations

- **Exact FQN only** — no partial/fuzzy search
- **Method-level only** — inheritance and type relationships are not in the graph
- **`val` fields** appear as nodes with `endLine == startLine`
- **Implicit conversions and for-comprehension** may be partially missing
