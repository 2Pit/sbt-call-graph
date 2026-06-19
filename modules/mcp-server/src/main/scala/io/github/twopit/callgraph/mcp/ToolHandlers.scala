package io.github.twopit.callgraph.mcp

import io.github.twopit.callgraph._
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, Tool}
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.json.McpJsonMapper

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.function.BiFunction
import scala.collection.JavaConverters._
import scala.util.matching.Regex

/** Builds the five SyncToolSpecifications wrapped around a GraphService.
  *
  * Large responses are written to `outputDir/N.json` and the inline reply is a short summary;
  * small responses are returned inline. `graphIndex` is always inline.
  */
object ToolHandlers {

  /** Auto-mode threshold: responses with a rendered JSON below this many bytes are returned
    * inline; larger ones are written to disk and replaced by a short summary.
    */
  private[mcp] val AutoInlineThresholdBytes = 8192

  /** How many node IDs to include in the file-mode summary as a quick "is this the right
    * result?" preview.
    */
  private[mcp] val PreviewNodeLimit = 10

  def all(
      service: GraphService,
      jsonMapper: McpJsonMapper,
      root: Path,
  ): java.util.List[SyncToolSpecification] = {
    val mk = new ToolBuilder(jsonMapper, root)
    List(
      mk.toolForceInline("graphIndex", Descriptions.graphIndex, Schemas.graphIndex) { req =>
        val (graph, st) = service.getGraph(Args.worktreeOpt(req.arguments()))
        JsonOutput.renderIndex(graph, st.message, st.notCompiled, st.emptyGraph)
      },
      mk.tool("graphSearch", Descriptions.graphSearch, Schemas.graphSearch) { req =>
        val args       = req.arguments()
        val query      = Args.str(args, "query")
        val maxResults = Args.int(args, "maxResults", 10)
        val (graph, _) = service.getGraph(Args.worktreeOpt(args))
        val matches    = QueryEngine.search(graph, query, maxResults)
        val json       = JsonOutput.renderSearchResult(matches, query, graph)
        ToolResult(
          fullJson = json,
          nodeCount = matches.size,
          edgeCount = 0,
          found = None,
          truncated = None,
          previewNodeIds = matches.take(PreviewNodeLimit),
        )
      },
      mk.tool("graphVia", Descriptions.graphVia, Schemas.graphVia) { req =>
        val args        = req.arguments()
        val vertex      = Args.str(args, "vertex")
        val depthIn     = Args.int(args, "depthIn", 2)
        val depthOut    = Args.int(args, "depthOut", 2)
        val filter      = Args.regexes(args, "filterOut")
        val (graph, st) = service.getGraph(Args.worktreeOpt(args))
        val result      = QueryEngine.viaVertex(graph, vertex, depthIn, depthOut)
        val gr          = result.getOrElse(GraphResult.empty)
        val (fNodes, fEdges) = applyFilter(gr.nodes, gr.edges, filter)
        val json = JsonOutput.renderViaResult(result, vertex, depthIn, depthOut, st.unusable, graph, filter)
        ToolResult(
          fullJson = json,
          nodeCount = fNodes.size,
          edgeCount = fEdges.size,
          found = Some(fNodes.nonEmpty),
          truncated = Some(gr.truncated),
          previewNodeIds = fNodes.take(PreviewNodeLimit),
        )
      },
      mk.tool("graphPath", Descriptions.graphPath, Schemas.graphPath) { req =>
        val args        = req.arguments()
        val vertices    = Args.strList(args, "vertices")
        val maxDepth    = Args.int(args, "maxDepth", 8)
        val maxPaths    = Args.int(args, "maxPaths", 5)
        val filter      = Args.regexes(args, "filterOut")
        val (graph, st) = service.getGraph(Args.worktreeOpt(args))
        val result      = QueryEngine.pathsAmong(graph, vertices, maxDepth, maxPaths)
        val (fNodes, fEdges) = applyFilter(result.nodes, result.edges, filter)
        val json = JsonOutput.renderPathResult(result, vertices, st.unusable, graph, filter)
        ToolResult(
          fullJson = json,
          nodeCount = fNodes.size,
          edgeCount = fEdges.size,
          found = Some(fNodes.nonEmpty),
          truncated = Some(result.truncated),
          previewNodeIds = fNodes.take(PreviewNodeLimit),
        )
      },
      mk.tool("graphModule", Descriptions.graphModule, Schemas.graphModule) { req =>
        val prefix     = Args.str(req.arguments(), "prefix")
        val (graph, _) = service.getGraph(Args.worktreeOpt(req.arguments()))
        val result     = ModuleQuery.moduleEdges(graph, prefix)
        val json       = JsonOutput.renderModuleResult(result, prefix, graph)
        val previewIds = (result.outgoing.map(_.srcId) ++ result.incoming.map(_.tgtId)).distinct
        ToolResult(
          fullJson = json,
          nodeCount = previewIds.size,
          edgeCount = result.outgoing.size + result.incoming.size,
          found = Some(result.outgoing.nonEmpty || result.incoming.nonEmpty),
          truncated = None,
          previewNodeIds = previewIds.take(PreviewNodeLimit),
        )
      },
    ).asJava
  }

  /** Drop nodes (and edges touching them) whose IDs match any of `filterOut`. Mirrors what
    * `JsonOutput.renderGraphResult` does internally, so the summary counts match the file content.
    */
  private def applyFilter(
      nodes: Seq[String],
      edges: Seq[(String, String)],
      filterOut: Seq[Regex],
  ): (Seq[String], Seq[(String, String)]) =
    if (filterOut.isEmpty) (nodes, edges)
    else {
      val hidden = (id: String) => filterOut.exists(_.findFirstIn(id).isDefined)
      val fn     = nodes.filterNot(hidden)
      val fnSet  = fn.toSet
      val fe     = edges.filter { case (s, t) => fnSet(s) && fnSet(t) }
      (fn, fe)
    }
}

/** What the body of a tool returns: the full JSON the agent would have seen in the old
  * inline-everything mode, plus the metadata needed to build a short summary if the
  * response is large enough to be diverted to disk.
  */
private[mcp] final case class ToolResult(
    fullJson: String,
    nodeCount: Int,
    edgeCount: Int,
    found: Option[Boolean],
    truncated: Option[Boolean],
    previewNodeIds: Seq[String],
)

private[mcp] sealed trait OutputMode
private[mcp] object OutputMode {
  case object Auto   extends OutputMode
  case object Inline extends OutputMode
  case object File   extends OutputMode

  def parse(s: String): OutputMode = s match {
    case "auto"   => Auto
    case "inline" => Inline
    case "file"   => File
    case other    => throw new ToolArgError(s"mode must be one of auto|inline|file, got $other")
  }
}

/** Builds a SyncToolSpecification with a uniform handler wrapper that:
  *   - parses the optional `mode` arg (auto/inline/file)
  *   - decides inline vs file output based on the rendered JSON size
  *   - turns ToolArgError and uncaught exceptions into isError=true results
  */
/** Resolves the on-disk overflow directory for large responses, per the optional `worktree`
  * arg, so a worktree query's files land in that worktree's `target/call-graph` — never the
  * main checkout's.
  */
private[mcp] object OutputPaths {
  def callGraphDir(root: Path, worktree: Option[String]): Path = worktree match {
    case Some(name) => root.resolve(".worktrees").resolve(name).resolve("target").resolve("call-graph")
    case None       => root.resolve("target").resolve("call-graph")
  }
}

private[mcp] final class ToolBuilder(jsonMapper: McpJsonMapper, root: Path) {

  /** Standard tool: subject to the output-mode policy. */
  def tool(name: String, description: String, schema: String)(
      body: CallToolRequest => ToolResult
  ): SyncToolSpecification = build(name, description, schema, body, forceInline = false)

  /** Tool that is always returned inline regardless of mode (used for cheap diagnostics
    * like graphIndex where the response is tiny by construction).
    */
  def toolForceInline(name: String, description: String, schema: String)(
      body: CallToolRequest => String
  ): SyncToolSpecification =
    build(
      name,
      description,
      schema,
      req =>
        ToolResult(body(req), nodeCount = 0, edgeCount = 0, found = None, truncated = None, previewNodeIds = Nil),
      forceInline = true,
    )

  private def build(
      name: String,
      description: String,
      schema: String,
      body: CallToolRequest => ToolResult,
      forceInline: Boolean,
  ): SyncToolSpecification =
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
      .callHandler(handler(name, body, forceInline))
      .build()

  private def handler(
      name: String,
      body: CallToolRequest => ToolResult,
      forceInline: Boolean,
  ): BiFunction[McpSyncServerExchange, CallToolRequest, CallToolResult] =
    new BiFunction[McpSyncServerExchange, CallToolRequest, CallToolResult] {
      override def apply(_ex: McpSyncServerExchange, req: CallToolRequest): CallToolResult =
        try {
          val mode      = if (forceInline) OutputMode.Inline else Args.mode(req.arguments())
          val r         = body(req)
          val outputDir = OutputPaths.callGraphDir(root, Args.worktreeOpt(req.arguments()))
          val text      = ToolOutput.render(name, r, mode, outputDir)
          CallToolResult.builder().addTextContent(text).isError(false).build()
        } catch {
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

/** Decides inline-vs-file and renders the agent-facing text. */
private[mcp] object ToolOutput {

  def render(toolName: String, r: ToolResult, mode: OutputMode, outputDir: Path): String =
    mode match {
      case OutputMode.Inline => r.fullJson
      case OutputMode.File   => writeFileAndSummary(toolName, r, outputDir)
      case OutputMode.Auto =>
        if (r.fullJson.getBytes(StandardCharsets.UTF_8).length < ToolHandlers.AutoInlineThresholdBytes) r.fullJson
        else writeFileAndSummary(toolName, r, outputDir)
    }

  private def writeFileAndSummary(toolName: String, r: ToolResult, outputDir: Path): String = {
    val file = JsonOutput.nextOutputFile(outputDir)
    Files.createDirectories(file.getParent)
    Files.write(file, r.fullJson.getBytes(StandardCharsets.UTF_8))
    summaryJson(toolName, r, file.toString)
  }

  private def summaryJson(toolName: String, r: ToolResult, filePath: String): String = {
    val fields = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    fields += ("file"  -> jstr(filePath))
    r.found.foreach(v => fields += ("found" -> v.toString))
    r.truncated.foreach(v => fields += ("truncated" -> v.toString))
    fields += ("nodes" -> r.nodeCount.toString)
    fields += ("edges" -> r.edgeCount.toString)
    fields += ("previewNodes" -> jarr(r.previewNodeIds.map(jstr)))
    fields += ("readHints" -> jarr(readHintsFor(toolName, filePath).map(jstr)))
    fields += ("note" -> jstr("response written to disk; read with jq <file>. Pass mode=\"inline\" to inline."))
    fields.map { case (k, v) => s"  ${jstr(k)}: $v" }.mkString("{\n", ",\n", "\n}")
  }

  /** Per-tool jq one-liners parameterised on the actual file path. Kept short — three at most. */
  private def readHintsFor(toolName: String, file: String): Seq[String] = toolName match {
    case "graphSearch" =>
      Seq(
        s"jq -r '.matches[] | .id' $file",
        s"""jq '.matches[] | select(.displayName == "exactName")' $file""",
      )
    case "graphVia" =>
      Seq(
        s"jq -r '.nodes[] | .displayName' $file",
        s"jq '.edges[]' $file",
        s"jq '.readHints[]' $file",
      )
    case "graphPath" =>
      Seq(
        s"jq '.edges[]' $file",
        s"jq -r '.nodes[] | .displayName' $file",
        s"jq '.truncated' $file",
      )
    case "graphModule" =>
      Seq(
        s"jq -r '.outgoing[] | .to.displayName' $file",
        s"jq '.incoming  | length' $file",
        s"jq '.outgoing | length' $file",
      )
    case _ => Nil
  }

  private def jstr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  private def jarr(items: Seq[String]): String =
    if (items.isEmpty) "[]" else items.mkString("[", ", ", "]")
}

private[mcp] object Schemas {

  // `mode` snippet is shared by every tool that supports the policy (i.e. all except graphIndex).
  private val modeProp: String =
    """    "mode": {
      |      "type": "string",
      |      "enum": ["auto", "inline", "file"],
      |      "default": "auto",
      |      "description": "auto (default): inline if response < 8KB, else write target/call-graph/N.json and return a summary. inline: always return full JSON. file: always write to disk."
      |    }""".stripMargin

  // `worktree` snippet is shared by all tools (incl. graphIndex). Default = main checkout only.
  private val worktreeProp: String =
    """    "worktree": {
      |      "type": "string",
      |      "description": "Optional git worktree name under .worktrees/. Default: serve the MAIN checkout only — sibling worktrees are excluded so their semanticdb never contaminates the graph. Set to a worktree name to query THAT worktree in isolation (and write overflow files to its own target/call-graph)."
      |    }""".stripMargin

  val graphIndex: String =
    s"""{
       |  "type": "object",
       |  "properties": {
       |$worktreeProp
       |  },
       |  "additionalProperties": false
       |}""".stripMargin

  val graphSearch: String =
    s"""{
       |  "type": "object",
       |  "properties": {
       |    "query":      { "type": "string", "description": "Substring of FQN or displayName (case-sensitive)." },
       |    "maxResults": { "type": "integer", "description": "Maximum matches to return. Default kept low because graphSearch is intentionally noisy — even a unique class name typically matches 40+ vertices (vals, lambdas, inner methods).", "default": 10 },
       |$modeProp,
       |$worktreeProp
       |  },
       |  "required": ["query"],
       |  "additionalProperties": false
       |}""".stripMargin

  val graphVia: String =
    s"""{
       |  "type": "object",
       |  "properties": {
       |    "vertex":    { "type": "string", "description": "FQN of the method to centre the neighbourhood on." },
       |    "depthIn":   { "type": "integer", "default": 2, "description": "BFS hops backward (callers)." },
       |    "depthOut":  { "type": "integer", "default": 2, "description": "BFS hops forward (callees)." },
       |    "filterOut": { "type": "array", "items": { "type": "string" }, "description": "Regexes; matching node IDs are excluded." },
       |$modeProp,
       |$worktreeProp
       |  },
       |  "required": ["vertex"],
       |  "additionalProperties": false
       |}""".stripMargin

  val graphPath: String =
    s"""{
       |  "type": "object",
       |  "properties": {
       |    "vertices":  { "type": "array", "items": { "type": "string" }, "minItems": 1, "description": "FQNs to connect; paths are searched between consecutive prefix pairs. With a single vertex, returns an empty result." },
       |    "maxDepth":  { "type": "integer", "default": 8,  "description": "Maximum DFS depth per path. If you get no paths, raise to 15–20." },
       |    "maxPaths":  { "type": "integer", "default": 5,  "description": "Maximum number of paths collected. If `truncated: true` and you need more, raise to 20–100." },
       |    "filterOut": { "type": "array", "items": { "type": "string" }, "description": "Regexes; matching node IDs are excluded." },
       |$modeProp,
       |$worktreeProp
       |  },
       |  "required": ["vertices"],
       |  "additionalProperties": false
       |}""".stripMargin

  val graphModule: String =
    s"""{
       |  "type": "object",
       |  "properties": {
       |    "prefix": { "type": "string", "description": "File-path substring identifying the module. Edges where one side is inside and the other outside are returned." },
       |$modeProp,
       |$worktreeProp
       |  },
       |  "required": ["prefix"],
       |  "additionalProperties": false
       |}""".stripMargin
}

private[mcp] object Descriptions {

  val graphIndex: String =
    """Diagnostics: node count, edge count, load status. Cheap (<200B response, always inline).
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

  // Block of text appended to every tool that supports the output-mode policy.
  private val outputModeFooter: String =
    """OUTPUT MODE:
      |  Small responses (<8KB) come back inline as JSON.
      |  Large responses are written to target/call-graph/N.json and the reply contains:
      |    file, found, truncated, nodes, edges, previewNodes (first 10 IDs), readHints (jq one-liners).
      |  Read large results with the readHints — never paste the whole file into context.
      |  Override with mode: "inline" (force inline) or mode: "file" (force disk).
      |  The directory is cleaned by `sbt clean`.""".stripMargin

  val graphSearch: String =
    s"""Search for vertices (methods/vals/classes) whose SemanticDB FQN or displayName contains `query` (case-sensitive).
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
       |jq recipes against the JSON (works inline or on the on-disk file when large):
       |  jq '.matches[] | select(.displayName == "exactName")' <file>
       |  jq -r '.matches[] | .id' <file>
       |  jq '.matches[] | select(.id | endswith("()."))' <file>
       |
       |For goto-definition / find-references use Metals MCP — this tool is for the call graph.
       |
       |$outputModeFooter""".stripMargin

  val graphVia: String =
    s"""Neighbourhood (callers + callees) of `vertex` up to depthIn/depthOut hops.
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
       |jq recipes against the JSON (works inline or on the on-disk file when large):
       |  jq -r '.nodes[] | .displayName' <file>      # method names only
       |  jq '.edges[]' <file>                        # raw call edges
       |  jq '.readHints[] | {file, ranges}' <file>   # source ranges for Read
       |
       |FQN format: sreo/session/SessionLive#close(). — get FQNs from graphSearch if unknown.
       |
       |$outputModeFooter""".stripMargin

  val graphPath: String =
    s"""Directed call paths connecting the given `vertices` (≥2 FQNs).
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
       |
       |jq recipes against the JSON (works inline or on the on-disk file when large):
       |  jq '.edges[]' <file>                  # the path edges
       |  jq -r '.nodes[] | .displayName' <file>  # methods on the path
       |  jq '.truncated' <file>                # was the search cut short
       |
       |FQN format: sreo/session/SessionLive#close().
       |
       |$outputModeFooter""".stripMargin

  val graphModule: String =
    s"""Call edges that cross the boundary of the module identified by `prefix`
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
       |  jq -r '.outgoing[] | .to.displayName' <file> | sort -u
       |  jq '.incoming  | length' <file>
       |  jq '.outgoing[] | select(.to.id | startswith("sreo/db/"))' <file>
       |
       |$outputModeFooter""".stripMargin
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

  /** Parse the optional `worktree` arg. Must be a bare directory name (no separators / `..`)
    * so it can only ever resolve under `.worktrees/`. Empty / absent => None (main checkout).
    */
  def worktreeOpt(args: java.util.Map[String, AnyRef]): Option[String] =
    Option(args).flatMap(a => Option(a.get("worktree"))) match {
      case None | Some("")               => None
      case Some(s: String) =>
        if (s.contains("/") || s.contains("\\") || s.contains(".."))
          throw new ToolArgError(s"worktree must be a bare directory name under .worktrees/, got '$s'")
        Some(s)
      case Some(other) => throw new ToolArgError(s"worktree must be a string, got $other")
    }

  /** Parse the optional `mode` arg controlling inline vs file output. Default Auto. */
  def mode(args: java.util.Map[String, AnyRef]): OutputMode =
    Option(args).flatMap(a => Option(a.get("mode"))) match {
      case None                          => OutputMode.Auto
      case Some(s: String) if s.nonEmpty => OutputMode.parse(s)
      case Some(other)                   => throw new ToolArgError(s"mode must be a string, got $other")
    }
}
