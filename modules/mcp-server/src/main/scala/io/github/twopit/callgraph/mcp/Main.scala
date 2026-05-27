package io.github.twopit.callgraph.mcp

import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper}
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities

import java.nio.file.{Path, Paths}
import java.util.concurrent.CountDownLatch

/** MCP server entry point. Stdio transport; logs to stderr only.
  *
  *   --root <path>              workspace root (default: cwd)
  *   --semanticdb-dir <path>    explicit SemanticDB meta directory (repeatable, optional)
  */
object Main {

  def main(args: Array[String]): Unit = {
    val opts = parseArgs(args.toList)
    System.err.println(
      s"[call-graph-mcp] v${BuildVersion.version} starting (root=${opts.root}, dirs=${opts.semanticdbDirs.size})"
    )

    val jsonMapper: McpJsonMapper = McpJsonDefaults.getMapper
    val transport                 = new StdioServerTransportProvider(jsonMapper)
    val service                   = new GraphService(opts.root, opts.semanticdbDirs)
    val outputDir                 = opts.root.resolve("target").resolve("call-graph")

    val server = McpServer
      .sync(transport)
      .serverInfo("call-graph-mcp", BuildVersion.version)
      .capabilities(ServerCapabilities.builder().tools(false).build())
      .tools(ToolHandlers.all(service, jsonMapper, outputDir))
      .build()

    val shutdown = new CountDownLatch(1)
    sys.addShutdownHook(new Thread(() => {
      try server.closeGracefully()
      catch { case _: Throwable => () }
      shutdown.countDown()
    }))

    System.err.println("[call-graph-mcp] ready")
    shutdown.await()
  }

  private final case class Opts(root: Path, semanticdbDirs: Seq[Path])

  private def parseArgs(args: List[String]): Opts = {
    var root: Path       = Paths.get("").toAbsolutePath
    var dirs: List[Path] = Nil
    var rest             = args
    while (rest.nonEmpty)
      rest match {
        case "--root" :: v :: tail =>
          root = Paths.get(v).toAbsolutePath
          rest = tail
        case "--semanticdb-dir" :: v :: tail =>
          dirs = Paths.get(v).toAbsolutePath :: dirs
          rest = tail
        case unknown :: tail =>
          System.err.println(s"[call-graph-mcp] ignoring unknown arg: $unknown")
          rest = tail
        case Nil => ()
      }
    Opts(root, dirs.reverse)
  }
}
