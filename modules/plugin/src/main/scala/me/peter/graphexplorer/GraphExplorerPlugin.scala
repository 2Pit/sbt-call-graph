package me.peter.graphexplorer

import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers._
import java.nio.file.Path
import scala.util.matching.Regex

object GraphExplorerPlugin extends AutoPlugin {
  override def trigger = noTrigger

  object autoImport {
    val graphPath = inputKey[Unit](
      "Find call paths. Usage: graphPath <v1> <v2> [<v3>...] [--maxDepth N] [--maxPaths N]"
    )
    val graphVia  = inputKey[Unit](
      "Show callers/callees of a vertex. Usage: graphVia <vertex> [--depth N] [--depthIn N] [--depthOut N]"
    )
    val graphSearch = inputKey[Unit](
      "Search vertices by name/FQN substring. Usage: graphSearch <substring> [--maxResults N]"
    )
    val graphModule = inputKey[Unit](
      "Show cross-module call edges. Usage: graphModule <path-prefix>"
    )
    val graphIndex           = taskKey[Unit]("Write call graph diagnostics to target/call-graph/N.json")
    val graphSemanticdbRoots =
      taskKey[Seq[Path]]("All SemanticDB meta dirs to load: current module + internal dependencies")
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    graphSemanticdbRoots := {
      val current = (Compile / classDirectory).value.getParentFile / "meta"
      val deps    = (Compile / internalDependencyClasspath).value.files
        .map(_.getParentFile / "meta")
        .filter(_.isDirectory)
      (current +: deps).distinct.map(_.toPath)
    },
    graphPath := {
      val args          = spaceDelimited("<args>").parsed
      val compileResult = (Compile / compile).result.value
      val roots         = graphSemanticdbRoots.value
      val sourceRoot    = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir      = (target.value / "call-graph").toPath
      val analysisFile  = (Compile / compileAnalysisFile).value
      val log           = streams.value.log

      val positional = args.filterNot(_.startsWith("--"))
      if (positional.length < 2)
        sys.error("Usage: graphPath <v1> <v2> [<v3>...] [--maxDepth N] [--maxPaths N] [--format md|html]")
      val maxDepth     = flagInt(args, "--maxDepth", 20)
      val maxPaths     = flagInt(args, "--maxPaths", 100)
      val format       = flagString(args, "--format", "json")
      val filterOut    = flagRegexes(args, "--filterOut")
      val compileError = compileResult.isInstanceOf[Inc]

      val (graph, tLoad) = timed {
        CallGraphState.getOrLoad(roots, sourceRoot, compileStamp(analysisFile))
      }
      val (result, tQuery) = timed {
        QueryEngine.pathsAmong(graph, positional, maxDepth, maxPaths)
      }
      val (written, tOut) = timed {
        format match {
          case "md"   => MermaidOutput.writeGraphResult(result, graph, MermaidOutput.nextOutputFile(graphDir))
          case "html" => HtmlOutput.writeGraphResult(result, "graphPath", graph, HtmlOutput.nextOutputFile(graphDir), filterOut)
          case "dot"  => DotOutput.writeGraphResult(result, "graphPath", graph, DotOutput.nextOutputFile(graphDir), filterOut)
          case _      => JsonOutput.writePathResult(result, positional, compileError, graph, JsonOutput.nextOutputFile(graphDir), filterOut)
        }
      }

      log.debug(f"[graph] load ${tLoad}%5d ms  query ${tQuery}%5d ms  output ${tOut}%5d ms  total ${tLoad + tQuery + tOut}%5d ms  (nodes=${graph.nodeCount}, edges=${graph.edgeCount})")
      log.info(written.toAbsolutePath.toString)
    },
    graphVia := {
      val args          = spaceDelimited("<args>").parsed
      val compileResult = (Compile / compile).result.value
      val roots         = graphSemanticdbRoots.value
      val sourceRoot    = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir      = (target.value / "call-graph").toPath
      val analysisFile  = (Compile / compileAnalysisFile).value
      val log           = streams.value.log

      if (args.isEmpty)
        sys.error("Usage: graphVia <vertex> [--depth N] [--depthIn N] [--depthOut N] [--format md|html]")
      val vertex       = args(0)
      val defaultDepth = flagInt(args, "--depth", 2)
      val depthIn      = flagInt(args, "--depthIn", defaultDepth)
      val depthOut     = flagInt(args, "--depthOut", defaultDepth)
      val format       = flagString(args, "--format", "json")
      val filterOut    = flagRegexes(args, "--filterOut")
      val compileError = compileResult.isInstanceOf[Inc]

      val (graph, tLoad) = timed {
        CallGraphState.getOrLoad(roots, sourceRoot, compileStamp(analysisFile))
      }
      val (result, tQuery) = timed {
        QueryEngine.viaVertex(graph, vertex, depthIn, depthOut)
      }
      val title = graph.meta.get(vertex).map(_.displayName).getOrElse(vertex)
      val gr    = result.getOrElse(GraphResult.empty)
      val (written, tOut) = timed {
        format match {
          case "md"   => MermaidOutput.writeGraphResult(gr, graph, MermaidOutput.nextOutputFile(graphDir))
          case "html" => HtmlOutput.writeGraphResult(gr, s"graphVia: $title", graph, HtmlOutput.nextOutputFile(graphDir), filterOut)
          case "dot"  => DotOutput.writeGraphResult(gr, s"graphVia: $title", graph, DotOutput.nextOutputFile(graphDir), filterOut)
          case _      => JsonOutput.writeViaResult(result, vertex, depthIn, depthOut, compileError, graph, JsonOutput.nextOutputFile(graphDir), filterOut)
        }
      }

      log.debug(f"[graph] load ${tLoad}%5d ms  query ${tQuery}%5d ms  output ${tOut}%5d ms  total ${tLoad + tQuery + tOut}%5d ms  (nodes=${gr.nodes.size})")
      log.info(written.toAbsolutePath.toString)
    },
    graphSearch := {
      val args         = spaceDelimited("<args>").parsed
      val roots        = graphSemanticdbRoots.value
      val sourceRoot   = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir     = (target.value / "call-graph").toPath
      val analysisFile = (Compile / compileAnalysisFile).value
      val log          = streams.value.log

      if (args.isEmpty) sys.error("Usage: graphSearch <substring> [--maxResults N]")
      val query      = args(0)
      val maxResults = flagInt(args, "--maxResults", 200)

      val (graph, tLoad) = timed {
        CallGraphState.getOrLoad(roots, sourceRoot, compileStamp(analysisFile))
      }
      val ((matches, written), tRest) = timed {
        val m = QueryEngine.search(graph, query, maxResults)
        val w = JsonOutput.writeSearchResult(m, query, graph, JsonOutput.nextOutputFile(graphDir))
        (m, w)
      }

      log.debug(f"[graph] load ${tLoad}%5d ms  query+out ${tRest}%5d ms  total ${tLoad + tRest}%5d ms  (matches=${matches.size})")
      log.info(written.toAbsolutePath.toString)
    },
    graphModule := {
      val args         = spaceDelimited("<args>").parsed
      val roots        = graphSemanticdbRoots.value
      val sourceRoot   = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir     = (target.value / "call-graph").toPath
      val analysisFile = (Compile / compileAnalysisFile).value
      val log          = streams.value.log

      if (args.isEmpty) sys.error("Usage: graphModule <path-prefix>")
      val prefix = args(0)

      val (graph, tLoad) = timed {
        CallGraphState.getOrLoad(roots, sourceRoot, compileStamp(analysisFile))
      }
      val ((result, written), tRest) = timed {
        val r = ModuleQuery.moduleEdges(graph, prefix)
        val w = JsonOutput.writeModuleResult(r, prefix, graph, JsonOutput.nextOutputFile(graphDir))
        (r, w)
      }

      log.debug(f"[graph] load ${tLoad}%5d ms  query+out ${tRest}%5d ms  total ${tLoad + tRest}%5d ms  (out=${result.outgoing.size}, in=${result.incoming.size})")
      log.info(written.toAbsolutePath.toString)
    },
    graphIndex := {
      val compileResult = (Compile / compile).result.value
      val roots         = graphSemanticdbRoots.value
      val sourceRoot    = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir      = (target.value / "call-graph").toPath
      val analysisFile  = (Compile / compileAnalysisFile).value
      val log           = streams.value.log

      val compileError = compileResult.isInstanceOf[Inc]

      val (graph, tLoad) = timed {
        CallGraphState.getOrLoad(roots, sourceRoot, compileStamp(analysisFile))
      }
      val (written, tOut) = timed {
        val status = s"loaded at ${new java.util.Date()}"
        JsonOutput.writeIndex(graph, status, compileError, JsonOutput.nextOutputFile(graphDir))
      }

      log.debug(f"[graph] load ${tLoad}%5d ms  output ${tOut}%5d ms  total ${tLoad + tOut}%5d ms  (nodes=${graph.nodeCount}, edges=${graph.edgeCount})")
      log.info(written.toAbsolutePath.toString)
    },
  )

  private def timed[T](f: => T): (T, Long) = {
    val t0     = System.currentTimeMillis()
    val result = f
    (result, System.currentTimeMillis() - t0)
  }

  private def flagInt(args: Seq[String], flag: String, default: Int): Int = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size)
      scala.util.Try(args(idx + 1).toInt).getOrElse(default)
    else default
  }

  private def flagString(args: Seq[String], flag: String, default: String): String = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size) args(idx + 1) else default
  }

  private def flagRegexes(args: Seq[String], flag: String): Seq[Regex] = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size)
      args(idx + 1).split(",").map(_.trim).filter(_.nonEmpty).map(_.r)
    else Nil
  }

  private def compileStamp(analysisFile: java.io.File): Long =
    if (analysisFile.exists()) analysisFile.lastModified() else 0L
}
