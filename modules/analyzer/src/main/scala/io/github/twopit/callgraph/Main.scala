package io.github.twopit.callgraph

import java.nio.file.{Path, Paths}

object Main {

  def main(args: Array[String]) {
    if (args.isEmpty) {
      println(
        "Usage: run <semanticdb-dir> [path <from> <to> | via <vertex> | search <query> | module <prefix>] [--format json|dot]"
      )
      sys.exit(1)
    }

    val semanticdbDir = Paths.get(args(0))
    val rest          = args.drop(1).toList
    val format        = flagStr(rest, "--format", "json")

    val graph  = CallGraphState.getOrLoad(Seq(semanticdbDir))
    val outDir = semanticdbDir.getParent.resolve("call-graph")

    rest.filterNot(_.startsWith("--")) match {

      case Nil =>
        // stats mode
        println(s"=== Graph stats ===")
        println(s"Nodes : ${graph.nodeCount}")
        println(s"Edges : ${graph.edgeCount}")
        println()

        val topOut = graph.out.toList.sortBy(-_._2.size).take(10)
        println("Top 10 by out-degree:")
        topOut.foreach { case (sym, callees) =>
          val name = graph.meta.get(sym).map(_.displayName).getOrElse(sym)
          println(s"  [${callees.size}] $name")
          callees.take(3).foreach { c =>
            println(s"        → ${graph.meta.get(c).map(_.displayName).getOrElse(c)}")
          }
        }

      case "path" :: from :: to :: _ =>
        val maxDepth = flagInt(rest, "--maxDepth", 20)
        val maxPaths = flagInt(rest, "--maxPaths", 100)
        val result   = QueryEngine.pathsAmong(graph, Seq(from, to), maxDepth, maxPaths)
        val written = writeGraph(
          format,
          result,
          s"$from -> $to",
          graph,
          outDir,
          json => JsonOutput.writePathResult(result, Seq(from, to), compileError = false, graph, json),
        )
        println(written.toAbsolutePath.toString)

      case "via" :: vertex :: _ =>
        val depth    = flagInt(rest, "--depth", 2)
        val depthIn  = flagInt(rest, "--depthIn", depth)
        val depthOut = flagInt(rest, "--depthOut", depth)
        val result   = QueryEngine.viaVertex(graph, vertex, depthIn, depthOut)
        val written = writeGraph(
          format,
          result.getOrElse(GraphResult.empty),
          vertex,
          graph,
          outDir,
          json => JsonOutput.writeViaResult(result, vertex, depthIn, depthOut, compileError = false, graph, json),
        )
        println(written.toAbsolutePath.toString)

      case "search" :: query :: _ =>
        warnDotUnsupported(format, "search")
        val maxResults = flagInt(rest, "--maxResults", 50)
        val matches    = QueryEngine.search(graph, query, maxResults)
        val written    = JsonOutput.writeSearchResult(matches, query, graph, JsonOutput.nextOutputFile(outDir))
        println(written.toAbsolutePath.toString)

      case "module" :: prefix :: _ =>
        warnDotUnsupported(format, "module")
        val result  = ModuleQuery.moduleEdges(graph, prefix)
        val written = JsonOutput.writeModuleResult(result, prefix, graph, JsonOutput.nextOutputFile(outDir))
        println(written.toAbsolutePath.toString)

      case other =>
        System.err.println(s"[call-graph] unknown command: ${other.mkString(" ")}")
        sys.exit(1)
    }
  }

  // DOT is only offered for path/via because only they produce a GraphResult (search/module don't).
  private def writeGraph(
      format: String,
      result: GraphResult,
      title: String,
      graph: LoadedGraph,
      outDir: Path,
      writeJson: Path => Path,
  ): Path = format match {
    case "dot" => DotOutput.writeGraphResult(result, title, graph, DotOutput.nextOutputFile(outDir))
    case _     => writeJson(JsonOutput.nextOutputFile(outDir))
  }

  private def warnDotUnsupported(format: String, cmd: String): Unit =
    if (format == "dot")
      System.err.println(s"[call-graph] --format dot supports path/via only; writing $cmd as JSON")

  private def flagInt(args: List[String], flag: String, default: Int): Int = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size)
      scala.util.Try(args(idx + 1).toInt).getOrElse(default)
    else default
  }

  private def flagStr(args: List[String], flag: String, default: String): String = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size) args(idx + 1) else default
  }
}
