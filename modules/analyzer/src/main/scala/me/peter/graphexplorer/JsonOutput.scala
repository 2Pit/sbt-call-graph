package me.peter.graphexplorer

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

object JsonOutput {

  /** Returns the next available `dir/<prefix>N.json` path, thread-safe within this JVM. */
  def nextOutputFile(dir: Path, prefix: String = ""): Path = nextFileLock.synchronized {
    Files.createDirectories(dir)
    import scala.collection.JavaConverters._
    val stream = Files.list(dir)
    val suffix = ".json"
    val max    =
      try
        stream
          .iterator()
          .asScala
          .map(_.getFileName.toString)
          .filter(n => n.startsWith(prefix) && n.endsWith(suffix))
          .flatMap(n => scala.util.Try(n.drop(prefix.length).dropRight(suffix.length).toLong).toOption)
          .reduceOption(_ max _)
          .getOrElse(0L)
      finally stream.close()
    dir.resolve(s"$prefix${max + 1}.json")
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
    val fields = Seq(
      "query"     -> obj("from" -> str(from), "to" -> str(to)),
      "found"     -> result.paths.nonEmpty.toString,
      "truncated" -> result.truncated.toString,
      "paths"     -> arr(result.paths.map(path => arr(path.map(nodeJson(_, graph))))),
    ) ++ (if (compileError) Seq("compileError" -> "true") else Nil)
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

  def writeSearchResult(
      matches:  Seq[String],
      query:    String,
      graph:    LoadedGraph,
      outFile:  Path,
  ): Path = {
    val fields = Seq(
      "query"   -> str(query),
      "count"   -> matches.size.toString,
      "matches" -> arr(matches.map(nodeJson(_, graph))),
    )
    write(outFile, obj(fields: _*))
  }

  def writeModuleResult(
      result:  QueryEngine.ModuleResult,
      prefix:  String,
      graph:   LoadedGraph,
      outFile: Path,
  ): Path = {
    def edgeJson(e: QueryEngine.ModuleEdge): String =
      obj("from" -> nodeJson(e.srcId, graph), "to" -> nodeJson(e.tgtId, graph))
    val fields = Seq(
      "query"    -> obj("prefix" -> str(prefix)),
      "outgoing" -> arr(result.outgoing.map(edgeJson)),
      "incoming" -> arr(result.incoming.map(edgeJson)),
    )
    write(outFile, obj(fields: _*))
  }

  def writeIndex(graph: LoadedGraph, status: String, compileError: Boolean, outFile: Path): Path = {
    // nodeCount = project-defined methods only; edgeCount includes edges to external symbols
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
