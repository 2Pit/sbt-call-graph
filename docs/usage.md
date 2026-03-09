# sbt-graph-explorer — Usage Guide

SBT plugin for building a call graph of a Scala project and navigating it via `sbtn` commands.

---

## Setup

### 1. Add to your project

`project/plugins.sbt`:

```scala
addSbtPlugin("me.peter" % "sbt-graph-explorer" % "<version>")
```

`build.sbt` — enable on the module you want to analyze:

```scala
lazy val myModule = project
  .enablePlugins(GraphExplorerPlugin)
```

### 2. Enable SemanticDB

SemanticDB must be enabled for the compiler to produce `.semanticdb` files. If you use scalafix, it's already enabled. Otherwise:

```scala
semanticdbEnabled := true
```

Then compile:

```
compile
```

---

## Commands

### `graphIndex` — graph diagnostics

Shows node and edge counts for the current cached graph.

```
myModule/graphIndex
```

---

### `graphVia <vertex>` — neighbourhood

Shows what calls a method and what it calls. Returns all reachable nodes within BFS depth and the induced subgraph edges between them.

```
myModule/graphVia com/example/MyClass#myMethod().
myModule/graphVia com/example/MyClass#myMethod(). --depth 3
myModule/graphVia com/example/MyClass#myMethod(). --depthIn 3 --depthOut 1
```

---

### `graphPath <v1> <v2> [<v3>...]` — paths between methods

DFS search for all paths between the given vertices. Accepts 2 or more vertices — paths are found between all forward pairs.

```
myModule/graphPath com/example/A#foo(). com/example/B#bar().
myModule/graphPath A B C --maxDepth 15 --maxPaths 50
```

Defaults: `maxDepth=20`, `maxPaths=100`.

---

### `graphSearch <query>` — find vertices by name

Case-sensitive substring search on FQN and displayName.

```
myModule/graphSearch MyClassName
myModule/graphSearch MyClassName --maxResults 20
```

---

### `graphModule <path-prefix>` — cross-module coupling

Shows all call edges that cross the boundary of a module identified by file path prefix.

```
myModule/graphModule com/example/submodule
```

---

## Output Formats

All query commands support `--format`:

```
myModule/graphVia com/example/A#foo(). --format html
myModule/graphPath A B --format md
```

| Format   | Flag             | Description                              |
|----------|------------------|------------------------------------------|
| JSON     | (default)        | Machine-readable nodes + edges + readHints |
| HTML     | `--format html`  | Interactive graph with pan/zoom/collapse  |
| Markdown | `--format md`    | Mermaid flowchart for embedding in docs   |
| DOT      | `--format dot`   | Graphviz DOT for external rendering       |

---

## Filtering

Use `--filterOut` to exclude nodes matching regex patterns (comma-separated):

```
myModule/graphVia com/example/A#foo(). --filterOut "com/example/util/.*,com/example/logging/.*"
```

---

## FQN Format

The plugin uses SemanticDB symbol format:

| Element       | Separator | Example            |
|---------------|-----------|--------------------|
| Package       | `/`       | `com/example/`     |
| Object        | `.`       | `MyObject.`        |
| Class / Trait  | `#`       | `MyClass#`         |
| Method        | `().`     | `myMethod().`      |

Full example: `com/example/MyClass#myMethod().`

**How to find the exact FQN:**

1. Run `graphSearch <name>` — returns all vertices matching the substring
2. Pick the `id` from the result and use it in `graphVia` / `graphPath`

**Notes:**

- `val` fields of traits/classes also appear in the graph as methods (SemanticDB represents them this way)
- `endLine == startLine` for single-line definitions (fields, abstract methods)
- `startLine` and `endLine` in JSON output are 1-based (human-readable)

---

## Caching

The graph is loaded on the first invocation of any task and cached in memory within the SBT daemon. After `compile`, the cache is invalidated automatically via the `compileAnalysisFile` mtime. Only files that changed are re-processed (three-level per-file cache).

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
- `truncated` — `true` if `--maxPaths` limit was hit

Results are written to `target/call-graph/N.{json,html,dot,md}` (N auto-increments, never overwritten). The file path is printed to stdout.
