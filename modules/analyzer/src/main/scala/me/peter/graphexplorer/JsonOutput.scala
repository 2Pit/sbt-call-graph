package me.peter.graphexplorer

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

object JsonOutput {

  /** Returns the next available `dir/N.json` path, thread-safe. */
  def nextOutputFile(dir: Path): Path = nextFileLock.synchronized {
    Files.createDirectories(dir)
    import scala.collection.JavaConverters._
    val max = Files
      .list(dir)
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".json"))
      .flatMap(n => scala.util.Try(n.dropRight(5).toLong).toOption)
      .reduceOption(_ max _)
      .getOrElse(0L)
    dir.resolve(s"${max + 1}.json")
  }

  private val nextFileLock = new Object

  def writePathResult(
      result: QueryEngine.PathResult,
      from: String,
      to: String,
      compileError: Boolean,
      graph: LoadedGraph,
      outFile: Path,
  ): Path = {
    val base = if (result.paths.isEmpty) {
      Seq("found" -> "false", "from" -> str(from), "to" -> str(to))
    } else {
      Seq(
        "found"     -> "true",
        "truncated" -> result.truncated.toString,
        "from"      -> str(from),
        "to"        -> str(to),
        "paths"     -> arr(result.paths.map(path => arr(path.map(nodeJson(_, graph))))),
      )
    }
    val fields = if (compileError) base :+ ("compileError" -> "true") else base
    write(outFile, obj(fields: _*))
  }

  def writeViaResult(
      result: Option[QueryEngine.ViaResult],
      vertex: String,
      depthIn: Int,
      depthOut: Int,
      compileError: Boolean,
      graph: LoadedGraph,
      outFile: Path,
  ): Path = {
    val (vertexJson, inArr, outArr) = result match {
      case Some(r) =>
        (
          nodeJson(vertex, graph),
          arr(r.in.map(n => depthNodeJson(n, graph))),
          arr(r.out.map(n => depthNodeJson(n, graph)))
        )
      case None =>
        ("null", "[]", "[]")
    }
    val fields = Seq(
      "query"  -> obj("vertex" -> str(vertex), "depthIn" -> depthIn.toString, "depthOut" -> depthOut.toString),
      "vertex" -> vertexJson,
      "in"     -> inArr,
      "out"    -> outArr,
    ) ++ (if (compileError) Seq("compileError" -> "true") else Nil)
    write(outFile, obj(fields: _*))
  }

  def writeIndex(graph: LoadedGraph, status: String, compileError: Boolean, outFile: Path): Path = {
    val fields = Seq(
      "status" -> str(status),
      "nodes"  -> graph.nodeCount.toString,
      "edges"  -> graph.edgeCount.toString,
    ) ++ (if (compileError) Seq("compileError" -> "true") else Nil)
    write(outFile, obj(fields: _*))
  }

  // startLine/endLine stored 0-based in SemanticDB; +1 for human-readable output
  private def nodeJson(id: String, graph: LoadedGraph): String =
    graph.meta.get(id) match {
      case Some(m) =>
        obj(
          "id"          -> str(id),
          "displayName" -> str(m.displayName),
          "file"        -> str(m.file),
          "startLine"   -> (m.startLine + 1).toString,
          "endLine"     -> (m.endLine + 1).toString,
        )
      case None =>
        obj("id" -> str(id))
    }

  private def depthNodeJson(node: QueryEngine.DepthNode, graph: LoadedGraph): String =
    graph.meta.get(node.id) match {
      case Some(m) =>
        obj(
          "id"          -> str(node.id),
          "displayName" -> str(m.displayName),
          "file"        -> str(m.file),
          "startLine"   -> (m.startLine + 1).toString,
          "endLine"     -> (m.endLine + 1).toString,
          "depth"       -> node.depth.toString,
        )
      case None =>
        obj("id" -> str(node.id), "depth" -> node.depth.toString)
    }

  private def str(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  private def obj(fields: (String, String)*): String =
    fields.map { case (k, v) =>
      val lines = v.split("\n", -1)
      if (lines.length == 1) s"  ${str(k)}: $v"
      else {
        val rest = lines.tail.map("  " + _).mkString("\n")
        s"  ${str(k)}: ${lines.head}\n$rest"
      }
    }.mkString("{\n", ",\n", "\n}")

  private def arr(items: Seq[String]): String =
    if (items.isEmpty) "[]"
    else items.map(indent).mkString("[\n", ",\n", "\n]")

  private def indent(s: String): String =
    s.split("\n").mkString("  ", "\n  ", "")

  private def write(path: Path, content: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path
  }
}
