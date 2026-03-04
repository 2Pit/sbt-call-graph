package me.peter.graphexplorer

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

object JsonOutput {

  def writePathResult(
      result: QueryEngine.PathResult,
      from: String,
      to: String,
      graph: LoadedGraph,
      outFile: Path,
  ): Path = {
    val json = if (result.paths.isEmpty) {
      obj("found" -> "false", "from" -> str(from), "to" -> str(to))
    } else {
      obj(
        "found"     -> "true",
        "truncated" -> result.truncated.toString,
        "from"      -> str(from),
        "to"        -> str(to),
        "paths"     -> arr(result.paths.map(path => arr(path.map(nodeJson(_, graph))))),
      )
    }
    write(outFile, json)
  }

  def writeViaResult(
      result: QueryEngine.ViaResult,
      vertex: String,
      depth: Int,
      graph: LoadedGraph,
      outFile: Path,
  ): Path = {
    val json = obj(
      "query"  -> obj("vertex" -> str(vertex), "depth" -> depth.toString),
      "vertex" -> nodeJson(vertex, graph),
      "in"     -> arr(result.in.map(n => depthNodeJson(n, graph))),
      "out"    -> arr(result.out.map(n => depthNodeJson(n, graph))),
    )
    write(outFile, json)
  }

  def writeIndex(graph: LoadedGraph, status: String, outFile: Path): Path = {
    val json = obj(
      "status" -> str(status),
      "nodes"  -> graph.nodeCount.toString,
      "edges"  -> graph.edgeCount.toString,
    )
    write(outFile, json)
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
