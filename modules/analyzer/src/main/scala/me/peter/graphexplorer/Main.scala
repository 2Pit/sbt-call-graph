package me.peter.graphexplorer

import java.nio.file.Paths

/**
 * CLI entry point.
 *
 * Usage:
 *   run <semanticdb-dir>
 *     -- print stats (node/edge counts, top callers)
 *
 *   run <semanticdb-dir> path <from> <to> [--maxDepth N] [--maxPaths N]
 *     -- find paths from <from> to <to>, write JSON to stdout
 *
 *   run <semanticdb-dir> via <vertex> [--maxDepth N] [--maxPaths N]
 *     -- show callers/callees of <vertex>, write JSON to stdout
 */
object Main extends App {
  if (args.isEmpty) {
    println("Usage: run <semanticdb-dir> [path <from> <to> | via <vertex>] [--maxDepth N] [--maxPaths N]")
    sys.exit(1)
  }

  val semanticdbDir = Paths.get(args(0))
  val rest          = args.drop(1).toList

  val maxDepth = flagInt(rest, "--maxDepth", default = 20)
  val maxPaths = flagInt(rest, "--maxPaths", default = 100)

  val graph = CallGraphState.getOrLoad(semanticdbDir)

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
      val result = QueryEngine.pathAtoB(graph, from, to, maxDepth, maxPaths)
      val outFile = semanticdbDir.getParent.resolve("graph-last-result.json")
      val written = JsonOutput.writePathResult(result, from, to, graph, outFile)
      println(written.toAbsolutePath.toString)

    case "via" :: vertex :: _ =>
      QueryEngine.viaVertex(graph, vertex) match {
        case None =>
          System.err.println(s"[graph-explorer] vertex not found: $vertex")
          sys.exit(1)
        case Some(result) =>
          val outFile = semanticdbDir.getParent.resolve("graph-last-result.json")
          val written = JsonOutput.writeViaResult(result, vertex, graph, outFile)
          println(written.toAbsolutePath.toString)
      }

    case other =>
      System.err.println(s"[graph-explorer] unknown command: ${other.mkString(" ")}")
      sys.exit(1)
  }

  private def flagInt(args: List[String], flag: String, default: Int): Int = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size)
      scala.util.Try(args(idx + 1).toInt).getOrElse(default)
    else default
  }
}
