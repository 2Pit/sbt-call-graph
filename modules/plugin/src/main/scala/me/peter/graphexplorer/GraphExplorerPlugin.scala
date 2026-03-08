package me.peter.graphexplorer

import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers._
import java.nio.file.Path
import scala.util.matching.Regex

object GraphExplorerPlugin extends AutoPlugin {
  override def trigger = noTrigger

  object autoImport {
    val graphPath = inputKey[Unit]("Find call paths. Usage: graphPath <from> <to> [--maxDepth N] [--maxPaths N]")
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
      // --- SBT task dependencies (macro evaluates these before the task body) ---
      val args         = spaceDelimited("<args>").parsed
      val compileResult = (Compile / compile).result.value
      val roots        = graphSemanticdbRoots.value
      val sourceRoot   = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir     = (target.value / "call-graph").toPath
      val analysisFile = (Compile / compileAnalysisFile).value
      val log          = streams.value.log

      // --- Task body ---
      val positional = args.filterNot(_.startsWith("--"))
      if (positional.length < 2)
        sys.error("Usage: graphPath <from> <to> [--maxDepth N] [--maxPaths N] [--format md|html]")
      val from         = positional(0)
      val to           = positional(1)
      val maxDepth     = flagInt(args, "--maxDepth", 20)
      val maxPaths     = flagInt(args, "--maxPaths", 100)
      val format       = flagString(args, "--format", "json")
      val filterOut    = flagRegexes(args, "--filterOut")
      val compileError = compileResult.isInstanceOf[Inc]

      val t0    = System.currentTimeMillis()
      val stamp = compileStamp(analysisFile)
      val graph = CallGraphState.getOrLoad(roots, sourceRoot, stamp)
      val t1    = System.currentTimeMillis()

      val result = QueryEngine.pathAtoB(graph, from, to, maxDepth, maxPaths)
      val t2     = System.currentTimeMillis()

      val written = format match {
        case "md"  => MermaidOutput.writePathResult(result, graph, MermaidOutput.nextOutputFile(graphDir))
        case "html" => HtmlOutput.writePathResult(result, graph, HtmlOutput.nextOutputFile(graphDir), filterOut)
        case "dot"  => DotOutput.writePathResult(result, graph, DotOutput.nextOutputFile(graphDir), filterOut)
        case _      => JsonOutput.writePathResult(result, from, to, compileError, graph, JsonOutput.nextOutputFile(graphDir), filterOut)
      }
      val t3 = System.currentTimeMillis()

      log.info(f"[graph] graph load  ${t1 - t0}%5d ms  (nodes=${graph.nodeCount}, edges=${graph.edgeCount})")
      log.info(f"[graph] query       ${t2 - t1}%5d ms  (paths=${result.paths.size})")
      log.info(f"[graph] output      ${t3 - t2}%5d ms")
      log.info(f"[graph] total       ${t3 - t0}%5d ms")
      log.info(written.toAbsolutePath.toString)
    },
    graphVia := {
      // --- SBT task dependencies ---
      val args         = spaceDelimited("<args>").parsed
      val compileResult = (Compile / compile).result.value
      val roots        = graphSemanticdbRoots.value
      val sourceRoot   = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir     = (target.value / "call-graph").toPath
      val analysisFile = (Compile / compileAnalysisFile).value
      val log          = streams.value.log

      // --- Task body ---
      if (args.isEmpty)
        sys.error("Usage: graphVia <vertex> [--depth N] [--depthIn N] [--depthOut N] [--format md|html]")
      val vertex       = args(0)
      val defaultDepth = flagInt(args, "--depth", 2)
      val depthIn      = flagInt(args, "--depthIn", defaultDepth)
      val depthOut     = flagInt(args, "--depthOut", defaultDepth)
      val format       = flagString(args, "--format", "json")
      val filterOut    = flagRegexes(args, "--filterOut")
      val compileError = compileResult.isInstanceOf[Inc]

      val t0    = System.currentTimeMillis()
      val stamp = compileStamp(analysisFile)
      val graph = CallGraphState.getOrLoad(roots, sourceRoot, stamp)
      val t1    = System.currentTimeMillis()

      val result = QueryEngine.viaVertex(graph, vertex, depthIn, depthOut)
      val t2     = System.currentTimeMillis()

      val written = format match {
        case "md"   => MermaidOutput.writeViaResult(result, vertex, graph, MermaidOutput.nextOutputFile(graphDir))
        case "html" => HtmlOutput.writeViaResult(result, vertex, graph, HtmlOutput.nextOutputFile(graphDir), filterOut)
        case "dot"  => DotOutput.writeViaResult(result, vertex, graph, DotOutput.nextOutputFile(graphDir), filterOut)
        case _      =>
          JsonOutput.writeViaResult(result, vertex, depthIn, depthOut, compileError, graph, JsonOutput.nextOutputFile(graphDir), filterOut)
      }
      val t3 = System.currentTimeMillis()

      val inCount  = result.map(_.in.size).getOrElse(0)
      val outCount = result.map(_.out.size).getOrElse(0)
      log.info(f"[graph] graph load  ${t1 - t0}%5d ms  (nodes=${graph.nodeCount}, edges=${graph.edgeCount})")
      log.info(f"[graph] query       ${t2 - t1}%5d ms  (in=$inCount, out=$outCount)")
      log.info(f"[graph] output      ${t3 - t2}%5d ms")
      log.info(f"[graph] total       ${t3 - t0}%5d ms")
      log.info(written.toAbsolutePath.toString)
    },
    graphSearch := {
      // --- SBT task dependencies ---
      val args         = spaceDelimited("<args>").parsed
      val roots        = graphSemanticdbRoots.value
      val sourceRoot   = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir     = (target.value / "call-graph").toPath
      val analysisFile = (Compile / compileAnalysisFile).value
      val log          = streams.value.log

      // --- Task body ---
      if (args.isEmpty) sys.error("Usage: graphSearch <substring> [--maxResults N]")
      val query      = args(0)
      val maxResults = flagInt(args, "--maxResults", 200)

      val t0      = System.currentTimeMillis()
      val stamp   = compileStamp(analysisFile)
      val graph   = CallGraphState.getOrLoad(roots, sourceRoot, stamp)
      val t1      = System.currentTimeMillis()
      val matches = QueryEngine.search(graph, query, maxResults)
      val written = JsonOutput.writeSearchResult(matches, query, graph, JsonOutput.nextOutputFile(graphDir))
      val t2      = System.currentTimeMillis()

      log.info(f"[graph] graph load  ${t1 - t0}%5d ms  (nodes=${graph.nodeCount}, edges=${graph.edgeCount})")
      log.info(f"[graph] query+out   ${t2 - t1}%5d ms  (matches=${matches.size})")
      log.info(f"[graph] total       ${t2 - t0}%5d ms")
      log.info(written.toAbsolutePath.toString)
    },
    graphModule := {
      // --- SBT task dependencies ---
      val args         = spaceDelimited("<args>").parsed
      val roots        = graphSemanticdbRoots.value
      val sourceRoot   = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir     = (target.value / "call-graph").toPath
      val analysisFile = (Compile / compileAnalysisFile).value
      val log          = streams.value.log

      // --- Task body ---
      if (args.isEmpty) sys.error("Usage: graphModule <path-prefix>")
      val prefix = args(0)

      val t0      = System.currentTimeMillis()
      val stamp   = compileStamp(analysisFile)
      val graph   = CallGraphState.getOrLoad(roots, sourceRoot, stamp)
      val t1      = System.currentTimeMillis()
      val result  = QueryEngine.moduleEdges(graph, prefix)
      val written = JsonOutput.writeModuleResult(result, prefix, graph, JsonOutput.nextOutputFile(graphDir))
      val t2      = System.currentTimeMillis()

      log.info(f"[graph] graph load  ${t1 - t0}%5d ms  (nodes=${graph.nodeCount}, edges=${graph.edgeCount})")
      log.info(f"[graph] query+out   ${t2 - t1}%5d ms  (out=${result.outgoing.size}, in=${result.incoming.size})")
      log.info(f"[graph] total       ${t2 - t0}%5d ms")
      log.info(written.toAbsolutePath.toString)
    },
    graphIndex := {
      // --- SBT task dependencies ---
      val compileResult = (Compile / compile).result.value
      val roots         = graphSemanticdbRoots.value
      val sourceRoot    = Some((ThisBuild / baseDirectory).value.toPath)
      val graphDir      = (target.value / "call-graph").toPath
      val analysisFile  = (Compile / compileAnalysisFile).value
      val log           = streams.value.log

      // --- Task body ---
      val compileError = compileResult.isInstanceOf[Inc]

      val t0      = System.currentTimeMillis()
      val stamp   = compileStamp(analysisFile)
      val graph   = CallGraphState.getOrLoad(roots, sourceRoot, stamp)
      val t1      = System.currentTimeMillis()
      val status  = s"loaded at ${new java.util.Date()}"
      val written = JsonOutput.writeIndex(graph, status, compileError, JsonOutput.nextOutputFile(graphDir))
      val t2      = System.currentTimeMillis()

      log.info(f"[graph] graph load  ${t1 - t0}%5d ms  (nodes=${graph.nodeCount}, edges=${graph.edgeCount})")
      log.info(f"[graph] output      ${t2 - t1}%5d ms")
      log.info(f"[graph] total       ${t2 - t0}%5d ms")
      log.info(written.toAbsolutePath.toString)
    },
  )

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

  /** A single stat() call on SBT's incremental-analysis file.
   *  The file is updated after every successful compile, so its mtime is a reliable
   *  proxy for "has a new compile happened?" — no need to walk all .semanticdb files.
   */
  private def compileStamp(analysisFile: java.io.File): Long =
    if (analysisFile.exists()) analysisFile.lastModified() else 0L
}
