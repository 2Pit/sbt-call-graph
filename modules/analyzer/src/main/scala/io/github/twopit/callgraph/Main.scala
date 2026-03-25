package io.github.twopit.callgraph

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/*
 * CLI entry point.
 *
 * Usage:
 *   run demo [<out-file>]
 *     -- write a self-contained demo HTML graph to <out-file> (default: graph-demo.html)
 *
 *   run <semanticdb-dir>
 *     -- print stats (node/edge counts, top callers)
 *
 *   run <semanticdb-dir> path <from> <to> [--maxDepth N] [--maxPaths N]
 *     -- find paths from <from> to <to>, write JSON to call-graph/N.json
 *
 *   run <semanticdb-dir> via <vertex> [--depth N] [--depthIn N] [--depthOut N]
 *     -- show callers/callees of <vertex>, write JSON to call-graph/N.json
 */
object Main {

  def main(args: Array[String]) {
    if (args.isEmpty) {
      println(
        "Usage: run demo | run <semanticdb-dir> [path <from> <to> | via <vertex> | search <query> | module <prefix>]"
      )
      sys.exit(1)
    }

    // `demo` command needs no semanticdb-dir
    if (args(0) == "demo") {
      val outPath = Paths.get(if (args.length > 1) args(1) else "graph-demo.html").toAbsolutePath
      Files.write(outPath, HtmlOutput.renderDemo().getBytes(StandardCharsets.UTF_8))
      println(outPath.toString)
      sys.exit(0)
    }

    val semanticdbDir = Paths.get(args(0))
    val rest          = args.drop(1).toList

    val graph = CallGraphState.getOrLoad(Seq(semanticdbDir))

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
        val outFile  = JsonOutput.nextOutputFile(semanticdbDir.getParent.resolve("call-graph"))
        val written  = JsonOutput.writePathResult(result, Seq(from, to), compileError = false, graph, outFile)
        println(written.toAbsolutePath.toString)

      case "via" :: vertex :: _ =>
        val depth    = flagInt(rest, "--depth", 2)
        val depthIn  = flagInt(rest, "--depthIn", depth)
        val depthOut = flagInt(rest, "--depthOut", depth)
        val result   = QueryEngine.viaVertex(graph, vertex, depthIn, depthOut)
        val outFile  = JsonOutput.nextOutputFile(semanticdbDir.getParent.resolve("call-graph"))
        val written = JsonOutput.writeViaResult(result, vertex, depthIn, depthOut, compileError = false, graph, outFile)
        println(written.toAbsolutePath.toString)

      case "search" :: query :: _ =>
        val maxResults = flagInt(rest, "--maxResults", 50)
        val matches    = QueryEngine.search(graph, query, maxResults)
        val outFile    = JsonOutput.nextOutputFile(semanticdbDir.getParent.resolve("call-graph"))
        val written    = JsonOutput.writeSearchResult(matches, query, graph, outFile)
        println(written.toAbsolutePath.toString)

      case "module" :: prefix :: _ =>
        val result  = ModuleQuery.moduleEdges(graph, prefix)
        val outFile = JsonOutput.nextOutputFile(semanticdbDir.getParent.resolve("call-graph"))
        val written = JsonOutput.writeModuleResult(result, prefix, graph, outFile)
        println(written.toAbsolutePath.toString)

      case other =>
        System.err.println(s"[call-graph] unknown command: ${other.mkString(" ")}")
        sys.exit(1)
    }
  }

  private def flagInt(args: List[String], flag: String, default: Int): Int = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size)
      scala.util.Try(args(idx + 1).toInt).getOrElse(default)
    else default
  }
}
