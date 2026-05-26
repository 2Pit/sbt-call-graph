package io.github.twopit.callgraph.mcp

import io.github.twopit.callgraph._
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, Tool}
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.json.McpJsonMapper

import java.util.function.BiFunction
import scala.collection.JavaConverters._
import scala.util.matching.Regex

/** Builds the five SyncToolSpecifications wrapped around a GraphService. */
object ToolHandlers {

  def all(service: GraphService, jsonMapper: McpJsonMapper): java.util.List[SyncToolSpecification] = {
    val mk = new ToolBuilder(jsonMapper)
    List(
      mk.tool("graphIndex", Descriptions.graphIndex, Schemas.graphIndex) { _ =>
        val (graph, st) = service.getGraph()
        JsonOutput.renderIndex(graph, st.message, st.notCompiled, st.emptyGraph)
      },
      mk.tool("graphSearch", Descriptions.graphSearch, Schemas.graphSearch) { req =>
        val args       = req.arguments()
        val query      = Args.str(args, "query")
        val maxResults = Args.int(args, "maxResults", 10)
        val (graph, _) = service.getGraph()
        val matches    = QueryEngine.search(graph, query, maxResults)
        JsonOutput.renderSearchResult(matches, query, graph)
      },
      mk.tool("graphVia", Descriptions.graphVia, Schemas.graphVia) { req =>
        val args        = req.arguments()
        val vertex      = Args.str(args, "vertex")
        val depthIn     = Args.int(args, "depthIn", 2)
        val depthOut    = Args.int(args, "depthOut", 2)
        val filter      = Args.regexes(args, "filterOut")
        val (graph, st) = service.getGraph()
        val result      = QueryEngine.viaVertex(graph, vertex, depthIn, depthOut)
        JsonOutput.renderViaResult(result, vertex, depthIn, depthOut, st.unusable, graph, filter)
      },
      mk.tool("graphPath", Descriptions.graphPath, Schemas.graphPath) { req =>
        val args        = req.arguments()
        val vertices    = Args.strList(args, "vertices")
        val maxDepth    = Args.int(args, "maxDepth", 8)
        val maxPaths    = Args.int(args, "maxPaths", 5)
        val filter      = Args.regexes(args, "filterOut")
        val (graph, st) = service.getGraph()
        val result      = QueryEngine.pathsAmong(graph, vertices, maxDepth, maxPaths)
        JsonOutput.renderPathResult(result, vertices, st.unusable, graph, filter)
      },
      mk.tool("graphModule", Descriptions.graphModule, Schemas.graphModule) { req =>
        val prefix     = Args.str(req.arguments(), "prefix")
        val (graph, _) = service.getGraph()
        val result     = ModuleQuery.moduleEdges(graph, prefix)
        JsonOutput.renderModuleResult(result, prefix, graph)
      },
    ).asJava
  }
}

/** Builds a SyncToolSpecification with a uniform handler wrapper that turns
  * `ToolArgError` and uncaught exceptions into `isError=true` results.
  */
private[mcp] final class ToolBuilder(jsonMapper: McpJsonMapper) {

  def tool(name: String, description: String, schema: String)(body: CallToolRequest => String): SyncToolSpecification =
    SyncToolSpecification
      .builder()
      .tool(
        Tool
          .builder()
          .name(name)
          .description(description)
          .inputSchema(jsonMapper, schema)
          .build()
      )
      .callHandler(handler(body))
      .build()

  private def handler(
      body: CallToolRequest => String
  ): BiFunction[McpSyncServerExchange, CallToolRequest, CallToolResult] =
    new BiFunction[McpSyncServerExchange, CallToolRequest, CallToolResult] {
      override def apply(_ex: McpSyncServerExchange, req: CallToolRequest): CallToolResult =
        try
          CallToolResult.builder().addTextContent(body(req)).isError(false).build()
        catch {
          case e: ToolArgError =>
            CallToolResult.builder().addTextContent(s"argument error: ${e.getMessage}").isError(true).build()
          case e: Throwable =>
            System.err.println(s"[call-graph-mcp] tool ${req.name()} failed: ${e.getMessage}")
            e.printStackTrace(System.err)
            CallToolResult
              .builder()
              .addTextContent(s"${e.getClass.getSimpleName}: ${e.getMessage}")
              .isError(true)
              .build()
        }
    }
}

private[mcp] object Schemas {

  val graphIndex: String =
    """{
      |  "type": "object",
      |  "properties": {},
      |  "additionalProperties": false
      |}""".stripMargin

  val graphSearch: String =
    """{
      |  "type": "object",
      |  "properties": {
      |    "query":      { "type": "string", "description": "Substring of FQN or displayName (case-sensitive)." },
      |    "maxResults": { "type": "integer", "description": "Maximum matches to return. Default kept low because graphSearch is intentionally noisy — even a unique class name typically matches 40+ vertices (vals, lambdas, inner methods).", "default": 10 }
      |  },
      |  "required": ["query"],
      |  "additionalProperties": false
      |}""".stripMargin

  val graphVia: String =
    """{
      |  "type": "object",
      |  "properties": {
      |    "vertex":    { "type": "string", "description": "FQN of the method to centre the neighbourhood on." },
      |    "depthIn":   { "type": "integer", "default": 2, "description": "BFS hops backward (callers)." },
      |    "depthOut":  { "type": "integer", "default": 2, "description": "BFS hops forward (callees)." },
      |    "filterOut": { "type": "array", "items": { "type": "string" }, "description": "Regexes; matching node IDs are excluded." }
      |  },
      |  "required": ["vertex"],
      |  "additionalProperties": false
      |}""".stripMargin

  val graphPath: String =
    """{
      |  "type": "object",
      |  "properties": {
      |    "vertices":  { "type": "array", "items": { "type": "string" }, "minItems": 1, "description": "FQNs to connect; paths are searched between consecutive prefix pairs. With a single vertex, returns an empty result." },
      |    "maxDepth":  { "type": "integer", "default": 8,  "description": "Maximum DFS depth per path. If you get no paths, raise to 15–20." },
      |    "maxPaths":  { "type": "integer", "default": 5,  "description": "Maximum number of paths collected. If `truncated: true` and you need more, raise to 20–100." },
      |    "filterOut": { "type": "array", "items": { "type": "string" }, "description": "Regexes; matching node IDs are excluded." }
      |  },
      |  "required": ["vertices"],
      |  "additionalProperties": false
      |}""".stripMargin

  val graphModule: String =
    """{
      |  "type": "object",
      |  "properties": {
      |    "prefix": { "type": "string", "description": "File-path substring identifying the module. Edges where one side is inside and the other outside are returned." }
      |  },
      |  "required": ["prefix"],
      |  "additionalProperties": false
      |}""".stripMargin
}

private[mcp] object Descriptions {

  val graphIndex: String =
    """Diagnostics: node count, edge count, load status. Cheap (<200B response).
      |Call this first to confirm the graph is loaded before issuing other queries.
      |  If `notCompiled` is true → run `sbt compile` to generate .semanticdb files.
      |  If `emptyGraph` is true  → files present but no METHOD symbols extracted.
      |
      |FQN FORMAT (used by every other graph* tool):
      |  package separator:  /            e.g. sreo/session/
      |  object:             .            e.g. SessionLive.
      |  class / trait:      #            e.g. SessionLive#
      |  method:             ().          e.g. close().
      |Full example: sreo/session/SessionLive#close().""".stripMargin

  val graphSearch: String =
    """Search for vertices (methods/vals/classes) whose SemanticDB FQN or displayName contains `query` (case-sensitive).
      |Default maxResults=10 — kept low because the graph indexes every val, lambda, and inner method, so even a unique class name typically returns 40+ matches.
      |
      |WHEN TO USE:
      |  - You need the FQN of a specific method but don't know its package yet.
      |
      |DO NOT USE FOR:
      |  - "Where is class X defined?"     → use Grep (faster, exact).
      |  - "Find all usages of class X."   → use Grep.
      |  - Browsing what's in a package    → use Grep / Glob on the file tree.
      |
      |AFTER CALLING, filter with jq before reading the raw result — most matches are noise:
      |  jq '.matches[] | select(.displayName == "exactName")'   # exact name only
      |  jq '.matches[] | .id'                                    # FQN-only list (10× smaller)
      |  jq '.matches[] | select(.id | endswith("()."))'          # methods only, drop vals
      |
      |For goto-definition / find-references use Metals MCP — this tool is for the call graph.""".stripMargin

  val graphVia: String =
    """Neighbourhood (callers + callees) of `vertex` up to depthIn/depthOut hops.
      |Returns nodes, edges, and `readHints` — file ranges to pass to Read.
      |
      |DEFAULTS: depthIn=2, depthOut=2 (both directions, 2 hops). Tune for context economy:
      |  callers-only (fan-in analysis):  depthOut=0
      |  callees-only:                    depthIn=0
      |  immediate neighbours only:       depthIn=1, depthOut=1
      |  deep exploration:                depthIn=3+ / depthOut=3+   (response size grows fast)
      |
      |If the result is big, narrow with filterOut (regexes on node IDs):
      |  filterOut=["sreo/db/.*", "sreo/tkl/.*"]
      |
      |WHEN TO USE:
      |  - "What calls X" / fan-in analysis before refactoring or splitting a component.
      |  - "What does X call" / understanding a transitive dependency tree without reading.
      |
      |DO NOT USE FOR:
      |  - You're already Reading the file containing X — its body shows immediate callees with code.
      |  - You want one specific caller you can grep by name.
      |
      |AFTER CALLING, scan names without reading bodies:
      |  jq '.nodes[] | .displayName'                  # just method names
      |  jq '.edges[]'                                  # raw call edges
      |  jq '.readHints[] | {file, ranges}'             # which source ranges Read should fetch
      |
      |FQN format: sreo/session/SessionLive#close(). — get FQNs from graphSearch if unknown.""".stripMargin

  val graphPath: String =
    """Directed call paths connecting the given `vertices` (≥2 FQNs).
      |Returns nodes, edges, `truncated` flag, and `readHints`.
      |
      |DEFAULTS (kept gentle): maxDepth=8, maxPaths=5.
      |  - If no paths found: raise maxDepth (8 → 15 → 20).
      |  - If `truncated: true` and you need more: raise maxPaths (5 → 20 → 100).
      |
      |DIRECTION MATTERS: paths are searched forward, from earlier vertices to later ones in
      |the argument list. To get reverse paths, swap the order.
      |
      |WHEN TO USE:
      |  - "How does an HTTP request reach the DB?" — two known endpoints, want the chain.
      |  - Confirming a suspected wiring path exists.
      |
      |DO NOT USE FOR:
      |  - Endpoints are 1–2 hops apart and already visible in a Read.
      |  - You only know one endpoint → use graphVia instead.
      |
      |Narrow large neighbourhoods with filterOut=["pkg/.*"] before searching.
      |AFTER CALLING:
      |  jq '.edges[]'                       # the path edges
      |  jq '.nodes[] | .displayName'        # methods on the path
      |  jq '.truncated'                     # was the search cut short
      |
      |FQN format: sreo/session/SessionLive#close().""".stripMargin

  val graphModule: String =
    """Call edges that cross the boundary of the module identified by `prefix`
      |(matched as a substring against each node's source file path).
      |Returns `outgoing` (calls from inside out) and `incoming` (calls from outside in).
      |
      |WHEN TO USE:
      |  - "How coupled is module X to the rest" — before splitting or merging modules.
      |  - Auditing what crosses an architectural boundary.
      |
      |DO NOT USE FOR:
      |  - You already know the few callers/callees — graphVia is cheaper and more precise.
      |  - The "module" is one class — use graphVia on the class anchor.
      |
      |For large modules this can be hundreds of edges. Extract just what you need:
      |  jq '.outgoing[] | .to.displayName' | sort -u
      |  jq '.incoming  | length'
      |  jq '.outgoing[] | select(.to.id | startswith("sreo/db/"))'""".stripMargin
}

final class ToolArgError(msg: String) extends RuntimeException(msg)

/** Argument extraction from the Map<String, Object> the SDK hands us. Permissive on
  * numeric types because JSON-RPC may deliver Integer, Long, or Double.
  */
private[mcp] object Args {

  def str(args: java.util.Map[String, AnyRef], key: String): String =
    Option(args).flatMap(a => Option(a.get(key))) match {
      case Some(s: String) if s.nonEmpty => s
      case Some(other)                   => throw new ToolArgError(s"$key must be a non-empty string, got $other")
      case None                          => throw new ToolArgError(s"$key is required")
    }

  def int(args: java.util.Map[String, AnyRef], key: String, default: Int): Int =
    Option(args).flatMap(a => Option(a.get(key))) match {
      case None            => default
      case Some(n: Number) => n.intValue()
      case Some(s: String) =>
        try s.toInt
        catch { case _: NumberFormatException => throw new ToolArgError(s"$key must be int") }
      case Some(other) => throw new ToolArgError(s"$key must be int, got $other")
    }

  def strList(args: java.util.Map[String, AnyRef], key: String): Seq[String] =
    Option(args).flatMap(a => Option(a.get(key))) match {
      case None => throw new ToolArgError(s"$key is required")
      case Some(list: java.util.List[_]) =>
        val seq = list.asScala.toList.collect { case s: String => s }
        if (seq.isEmpty) throw new ToolArgError(s"$key must be a non-empty array of strings")
        else seq
      case Some(other) => throw new ToolArgError(s"$key must be an array of strings, got $other")
    }

  def regexes(args: java.util.Map[String, AnyRef], key: String): Seq[Regex] =
    Option(args).flatMap(a => Option(a.get(key))) match {
      case None => Nil
      case Some(list: java.util.List[_]) =>
        val items = list.asScala.toList
        if (items.exists(!_.isInstanceOf[String]))
          throw new ToolArgError(s"$key must be an array of regex strings, got non-string item")
        items.collect { case s: String => s.r }
      case Some(other) => throw new ToolArgError(s"$key must be an array of regex strings, got $other")
    }
}
