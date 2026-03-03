package me.peter.graphexplorer

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

object JsonOutput {

  /** Write pathAtoB result to file, return the file path. */
  def writePathResult(
    result:   QueryEngine.PathResult,
    from:     String,
    to:       String,
    graph:    LoadedGraph,
    outFile:  Path,
  ): Path = {
    val json = if (result.paths.isEmpty) {
      s"""|{
          |  "found": false,
          |  "from": ${str(from)},
          |  "to": ${str(to)}
          |}""".stripMargin
    } else {
      val pathsJson = result.paths.map { path =>
        val nodes = path.map(nodeJson(_, graph)).mkString(",\n      ")
        s"[\n      $nodes\n    ]"
      }.mkString(",\n    ")

      s"""|{
          |  "found": true,
          |  "truncated": ${result.truncated},
          |  "from": ${str(from)},
          |  "to": ${str(to)},
          |  "paths": [
          |    $pathsJson
          |  ]
          |}""".stripMargin
    }

    write(outFile, json)
  }

  /** Write viaVertex result to file, return the file path. */
  def writeViaResult(
    result:  QueryEngine.ViaResult,
    vertex:  String,
    graph:   LoadedGraph,
    outFile: Path,
  ): Path = {
    val callersJson = result.callers.map(nodeJson(_, graph)).mkString(",\n    ")
    val calleesJson = result.callees.map(nodeJson(_, graph)).mkString(",\n    ")

    val json =
      s"""|{
          |  "vertex": ${str(vertex)},
          |  "callers": [
          |    $callersJson
          |  ],
          |  "callees": [
          |    $calleesJson
          |  ]
          |}""".stripMargin

    write(outFile, json)
  }

  /** Write graphIndex diagnostics to file, return the file path. */
  def writeIndex(graph: LoadedGraph, status: String, outFile: Path): Path = {
    val json =
      s"""|{
          |  "status": ${str(status)},
          |  "nodes": ${graph.nodeCount},
          |  "edges": ${graph.edgeCount}
          |}""".stripMargin
    write(outFile, json)
  }

  private def nodeJson(id: String, graph: LoadedGraph): String = {
    graph.meta.get(id) match {
      case Some(m) =>
        s"""{"id": ${str(id)}, "displayName": ${str(m.displayName)}, "file": ${str(m.file)}, "startLine": ${m.startLine + 1}}"""
      case None =>
        s"""{"id": ${str(id)}}"""
    }
  }

  // startLine is stored 0-based in SemanticDB; add 1 for human-readable output

  private def str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def write(path: Path, content: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path
  }
}
