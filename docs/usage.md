# sbt-call-graph — Usage Guide

Analyzer + MCP server for building a call graph of a Scala project and querying it via MCP tool calls.

---

## Setup

### 1. Build the MCP server jar

```sh
cd utils/sbt-graph-exporter
sbt mcpServer/assembly
# produces modules/mcp-server/target/scala-2.13/call-graph-mcp.jar
```

### 2. Register with Claude Code

Add to `.mcp.json` or `~/.claude.json`:

```json
{
  "mcpServers": {
    "call-graph": {
      "command": "java",
      "args": [
        "-jar", "/abs/path/to/call-graph-mcp.jar",
        "--root", "/abs/path/to/your/workspace"
      ]
    }
  }
}
```

### 3. Enable SemanticDB in the target project

SemanticDB must be enabled for the compiler to produce `.semanticdb` files. If you use scalafix, it's already enabled. Otherwise add to `build.sbt`:

```scala
semanticdbEnabled := true
```

Then compile the target project:

```sh
sbt compile
```

---

## Worktree parameter

Every tool requires a **`worktree`** argument — there is no default:

- `worktree: "."` — query the **main checkout**
- `worktree: "<name>"` — query the worktree at `.worktrees/<name>/` in isolation

Omitting `worktree` (or passing `""`) is an error. This prevents silently serving a stale main-checkout graph while working inside a worktree.

---

## Tools

### `graphIndex` — graph diagnostics

Shows node and edge counts for the current cached graph.

```
graphIndex  worktree="."
```

---

### `graphVia` — neighbourhood

Shows what calls a method and what it calls. Returns all reachable nodes within BFS depth and the induced subgraph edges between them.

```
graphVia  vertex="com/example/MyClass#myMethod()."  worktree="."
graphVia  vertex="com/example/MyClass#myMethod()."  worktree="."  depth=3
graphVia  vertex="com/example/MyClass#myMethod()."  worktree="."  depthIn=3  depthOut=1
```

---

### `graphPath` — paths between methods

DFS search for all paths between the given vertices. Accepts 2 or more vertices — paths are found between all forward pairs.

```
graphPath  vertices=["com/example/A#foo().", "com/example/B#bar()."]  worktree="."
graphPath  vertices=["A", "B", "C"]  worktree="."  maxDepth=15  maxPaths=50
```

Defaults: `maxDepth=20`, `maxPaths=100`.

---

### `graphSearch` — find vertices by name

Case-sensitive substring search on FQN and displayName.

```
graphSearch  query="MyClassName"  worktree="."
graphSearch  query="MyClassName"  worktree="."  maxResults=20
```

---

### `graphModule` — cross-module coupling

Shows all call edges that cross the boundary of a module identified by file path prefix.

```
graphModule  prefix="com/example/submodule"  worktree="."
```

---

## Output Formats

`graphVia` and `graphPath` support a `format` argument:

| Format   | Value            | Description                              |
|----------|------------------|------------------------------------------|
| JSON     | (default)        | Machine-readable nodes + edges + readHints |
| HTML     | `html`           | Interactive graph with pan/zoom/collapse  |
| Markdown | `md`             | Mermaid flowchart for embedding in docs   |
| DOT      | `dot`            | Graphviz DOT for external rendering       |

---

## Filtering

Use `filterOut` to exclude nodes matching regex patterns (comma-separated):

```
graphVia  vertex="com/example/A#foo()."  worktree="."  filterOut="com/example/util/.*,com/example/logging/.*"
```

---

## FQN Format

Uses SemanticDB symbol format:

| Element       | Separator | Example            |
|---------------|-----------|--------------------|
| Package       | `/`       | `com/example/`     |
| Object        | `.`       | `MyObject.`        |
| Class / Trait  | `#`       | `MyClass#`         |
| Method        | `().`     | `myMethod().`      |

Full example: `com/example/MyClass#myMethod().`

**How to find the exact FQN:**

1. Run `graphSearch query="<name>" worktree="."` — returns all vertices matching the substring
2. Pick the `id` from the result and use it in `graphVia` / `graphPath`

**Notes:**

- `val` fields of traits/classes also appear in the graph as methods (SemanticDB represents them this way)
- `endLine == startLine` for single-line definitions (fields, abstract methods)
- `startLine` and `endLine` in JSON output are 1-based (human-readable)

---

## Caching

The graph is loaded on the first invocation of any tool and cached in memory within the MCP server process. After `compile`, the cache is invalidated automatically via the `compileAnalysisFile` mtime. Only files that changed are re-processed (three-level per-file cache).

---

## JSON Output

Both `graphVia` and `graphPath` return the same structure:

```json
{
  "query":     { "vertex": "com/example/A#foo().", "depthIn": 2, "depthOut": 2 },
  "found":     true,
  "truncated": false,
  "nodes": [
    { "id": "...", "displayName": "foo", "file": "src/.../A.scala", "startLine": 10, "endLine": 25 }
  ],
  "edges": [
    { "from": "...", "to": "..." }
  ],
  "readHints": [
    { "file": "src/.../A.scala", "ranges": [{ "start": 10, "end": 25 }] }
  ]
}
```

- `readHints` groups nodes by file and merges line ranges within 10 lines of each other
- `found` — `true` if any nodes were returned
- `truncated` — `true` if `maxPaths` limit was hit

Results for large responses are written to `target/call-graph/N.{json,html,dot,md}` (N auto-increments, never overwritten). The file path is included in the MCP reply summary.
