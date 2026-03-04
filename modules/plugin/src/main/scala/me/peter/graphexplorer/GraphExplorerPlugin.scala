package me.peter.graphexplorer

import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers._

object GraphExplorerPlugin extends AutoPlugin {
  override def trigger = noTrigger

  object autoImport {
    val graphPath  = inputKey[Unit]("Find call paths. Usage: graphPath <from> <to> [--maxDepth N] [--maxPaths N]")
    val graphVia   = inputKey[Unit]("Show callers/callees of a vertex. Usage: graphVia <vertex> [--depth N] [--depthIn N] [--depthOut N]")
    val graphIndex = taskKey[Unit]("Write call graph diagnostics to target/call-graph/N.json")
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(

    graphPath := {
      val args          = spaceDelimited("<args>").parsed
      val positional    = args.filterNot(_.startsWith("--"))
      if (positional.length < 2)
        sys.error("Usage: graphPath <from> <to> [--maxDepth N] [--maxPaths N]")
      val from          = positional(0)
      val to            = positional(1)
      val maxDepth      = flagInt(args, "--maxDepth", 20)
      val maxPaths      = flagInt(args, "--maxPaths", 100)
      val compileError  = (Compile / compile).result.value.isInstanceOf[Inc]
      val semanticdbDir = (Compile / classDirectory).value.getParentFile / "meta"
      val outDir        = target.value / "call-graph"
      val outFile       = JsonOutput.nextOutputFile(outDir.toPath)
      val graph         = CallGraphState.getOrLoad(semanticdbDir.toPath)
      val result        = QueryEngine.pathAtoB(graph, from, to, maxDepth, maxPaths)
      val written       = JsonOutput.writePathResult(result, from, to, compileError, graph, outFile)
      println(written.toAbsolutePath.toString)
    },

    graphVia := {
      val args          = spaceDelimited("<args>").parsed
      if (args.isEmpty) sys.error("Usage: graphVia <vertex> [--depth N] [--depthIn N] [--depthOut N]")
      val vertex        = args(0)
      val defaultDepth  = flagInt(args, "--depth", 2)
      val depthIn       = flagInt(args, "--depthIn",  defaultDepth)
      val depthOut      = flagInt(args, "--depthOut", defaultDepth)
      val compileError  = (Compile / compile).result.value.isInstanceOf[Inc]
      val semanticdbDir = (Compile / classDirectory).value.getParentFile / "meta"
      val outDir        = target.value / "call-graph"
      val outFile       = JsonOutput.nextOutputFile(outDir.toPath)
      val graph         = CallGraphState.getOrLoad(semanticdbDir.toPath)
      val result        = QueryEngine.viaVertex(graph, vertex, depthIn, depthOut)
      val written       = JsonOutput.writeViaResult(result, vertex, depthIn, depthOut, compileError, graph, outFile)
      println(written.toAbsolutePath.toString)
    },

    graphIndex := {
      val compileError  = (Compile / compile).result.value.isInstanceOf[Inc]
      val semanticdbDir = (Compile / classDirectory).value.getParentFile / "meta"
      val outDir        = target.value / "call-graph"
      val outFile       = JsonOutput.nextOutputFile(outDir.toPath)
      val graph         = CallGraphState.getOrLoad(semanticdbDir.toPath)
      val status        = s"loaded at ${new java.util.Date()}"
      val written       = JsonOutput.writeIndex(graph, status, compileError, outFile)
      println(written.toAbsolutePath.toString)
    },

  )

  private def flagInt(args: Seq[String], flag: String, default: Int): Int = {
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size)
      scala.util.Try(args(idx + 1).toInt).getOrElse(default)
    else default
  }
}
