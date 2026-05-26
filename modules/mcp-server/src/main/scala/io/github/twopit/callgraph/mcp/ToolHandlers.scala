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
        val maxResults = Args.int(args, "maxResults", 50)
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
        val maxDepth    = Args.int(args, "maxDepth", 20)
        val maxPaths    = Args.int(args, "maxPaths", 100)
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
      |    "maxResults": { "type": "integer", "description": "Maximum matches to return.", "default": 50 }
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
      |    "maxDepth":  { "type": "integer", "default": 20, "description": "Maximum DFS depth per path." },
      |    "maxPaths":  { "type": "integer", "default": 100, "description": "Maximum number of paths collected." },
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
    """Return diagnostics for the call graph: node count, edge count, and load status.
      |Use this first to confirm the graph is loaded before issuing other queries.
      |If `notCompiled` is true, run `sbt compile` to generate .semanticdb files.
      |If `emptyGraph` is true, files are present but no METHOD symbols were extracted.""".stripMargin

  val graphSearch: String =
    """Search for vertices (methods) whose SemanticDB FQN or short displayName contains `query` (case-sensitive).
      |Returns matches with file/line metadata. FQN format example:
      |  io/github/twopit/callgraph/GraphLoader.load().
      |For richer navigation (goto-definition / find-references) use Metals MCP; for path/neighbourhood/coupling queries use the other graph* tools.""".stripMargin

  val graphVia: String =
    """Show the neighbourhood (callers + callees) of `vertex` up to depthIn/depthOut hops.
      |Returns nodes, edges, and `readHints` — file ranges Claude can pass to the Read tool to inspect the methods involved.
      |FQN format example: io/github/twopit/callgraph/GraphLoader.load().
      |Use this for "what calls X and what does X call" style questions; use graphPath for connecting two known endpoints.""".stripMargin

  val graphPath: String =
    """Find directed call paths connecting the given `vertices` (≥2 FQNs).
      |Returns nodes, edges, `truncated`, and `readHints`.
      |FQN format example: io/github/twopit/callgraph/GraphLoader.load().
      |Use this when you have two known endpoints; use graphVia when you only have one endpoint and want a neighbourhood.""".stripMargin

  val graphModule: String =
    """Return call edges that cross the boundary of the module identified by `prefix`.
      |`prefix` is matched as a substring against each node's source file path.
      |Returns two lists, `outgoing` (calls from inside out) and `incoming` (calls from outside in), each with from/to node metadata.
      |Use this to understand how a package or module couples with the rest of the codebase.""".stripMargin
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
