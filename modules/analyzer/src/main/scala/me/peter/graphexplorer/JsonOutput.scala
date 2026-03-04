package me.peter.graphexplorer

import java.nio.file.{Files, Path}
import ujson._

object JsonOutput {

  def writePathResult(
    result:  QueryEngine.PathResult,
    from:    String,
    to:      String,
    graph:   LoadedGraph,
    outFile: Path,
  ): Path = {
    val obj = if (result.paths.isEmpty) {
      Obj("found" -> false, "from" -> from, "to" -> to)
    } else {
      Obj(
        "found"     -> true,
        "truncated" -> result.truncated,
        "from"      -> from,
        "to"        -> to,
        "paths"     -> Arr.from(result.paths.map(path => Arr.from(path.map(nodeObj(_, graph))))),
      )
    }
    write(outFile, obj)
  }

  def writeViaResult(
    result:  QueryEngine.ViaResult,
    vertex:  String,
    graph:   LoadedGraph,
    outFile: Path,
  ): Path = {
    val obj = Obj(
      "vertex"  -> vertex,
      "callers" -> Arr.from(result.callers.map(nodeObj(_, graph))),
      "callees" -> Arr.from(result.callees.map(nodeObj(_, graph))),
    )
    write(outFile, obj)
  }

  def writeIndex(graph: LoadedGraph, status: String, outFile: Path): Path = {
    val obj = Obj("status" -> status, "nodes" -> graph.nodeCount, "edges" -> graph.edgeCount)
    write(outFile, obj)
  }

  // startLine/endLine stored 0-based in SemanticDB; +1 for human-readable output
  private def nodeObj(id: String, graph: LoadedGraph): Obj =
    graph.meta.get(id) match {
      case Some(m) =>
        Obj(
          "id"          -> id,
          "displayName" -> m.displayName,
          "file"        -> m.file,
          "startLine"   -> (m.startLine + 1),
          "endLine"     -> (m.endLine + 1),
        )
      case None =>
        Obj("id" -> id)
    }

  private def write(path: Path, obj: Obj): Path = {
    Files.createDirectories(path.getParent)
    Files.writeString(path, write(obj: Value))
    path
  }

  private def write(v: Value): String = ujson.write(v, indent = 2)
}
