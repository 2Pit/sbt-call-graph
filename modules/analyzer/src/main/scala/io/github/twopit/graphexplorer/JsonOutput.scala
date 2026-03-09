package io.github.twopit.graphexplorer

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.util.matching.Regex

object JsonOutput {

  /** Returns the next available `dir/N.json` path. */
  def nextOutputFile(dir: Path): Path = OutputCounter.next(dir, ".json")

  // ---------------------------------------------------------------------------
  // Render (return JSON string)
  // ---------------------------------------------------------------------------

  def renderPathResult(
      result: GraphResult,
      vertices: Seq[String],
      compileError: Boolean,
      graph: LoadedGraph,
      filterOut: Seq[Regex] = Nil,
  ): String = {
    val queryJson = obj("vertices" -> arr(vertices.map(str)))
    renderGraphResult(result, queryJson, compileError, graph, filterOut)
  }

  def renderViaResult(
      result: Option[GraphResult],
      vertex: String,
      depthIn: Int,
      depthOut: Int,
      compileError: Boolean,
      graph: LoadedGraph,
      filterOut: Seq[Regex] = Nil,
  ): String = {
    val queryJson = obj(
      "vertex"   -> str(vertex),
      "depthIn"  -> depthIn.toString,
      "depthOut" -> depthOut.toString,
    )
    renderGraphResult(result.getOrElse(GraphResult.empty), queryJson, compileError, graph, filterOut)
  }

  def renderSearchResult(
      matches: Seq[String],
      query: String,
      graph: LoadedGraph,
  ): String = {
    val fields = Seq(
      "query"   -> str(query),
      "count"   -> matches.size.toString,
      "matches" -> arr(matches.map(nodeJson(_, graph))),
    )
    obj(fields: _*)
  }

  def renderModuleResult(
      result: ModuleResult,
      prefix: String,
      graph: LoadedGraph,
  ): String = {
    def edgeJson(e: ModuleEdge): String =
      obj("from" -> nodeJson(e.srcId, graph), "to" -> nodeJson(e.tgtId, graph))
    val fields = Seq(
      "query"    -> obj("prefix" -> str(prefix)),
      "outgoing" -> arr(result.outgoing.map(edgeJson)),
      "incoming" -> arr(result.incoming.map(edgeJson)),
    )
    obj(fields: _*)
  }

  def renderIndex(graph: LoadedGraph, status: String, compileError: Boolean): String = {
    val fields = Seq(
      "status" -> str(status),
      "nodes"  -> graph.nodeCount.toString,
      "edges"  -> graph.edgeCount.toString,
    ) ++ (if (compileError) Seq("compileError" -> "true") else Nil)
    obj(fields: _*)
  }

  // ---------------------------------------------------------------------------
  // Write (render + write to file)
  // ---------------------------------------------------------------------------

  def writePathResult(
      result: GraphResult,
      vertices: Seq[String],
      compileError: Boolean,
      graph: LoadedGraph,
      outFile: Path,
      filterOut: Seq[Regex] = Nil,
  ): Path = write(outFile, renderPathResult(result, vertices, compileError, graph, filterOut))

  def writeViaResult(
      result: Option[GraphResult],
      vertex: String,
      depthIn: Int,
      depthOut: Int,
      compileError: Boolean,
      graph: LoadedGraph,
      outFile: Path,
      filterOut: Seq[Regex] = Nil,
  ): Path = write(outFile, renderViaResult(result, vertex, depthIn, depthOut, compileError, graph, filterOut))

  def writeSearchResult(
      matches: Seq[String],
      query: String,
      graph: LoadedGraph,
      outFile: Path,
  ): Path = write(outFile, renderSearchResult(matches, query, graph))

  def writeModuleResult(
      result: ModuleResult,
      prefix: String,
      graph: LoadedGraph,
      outFile: Path,
  ): Path = write(outFile, renderModuleResult(result, prefix, graph))

  def writeIndex(graph: LoadedGraph, status: String, compileError: Boolean, outFile: Path): Path =
    write(outFile, renderIndex(graph, status, compileError))

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private def renderGraphResult(
      result: GraphResult,
      queryJson: String,
      compileError: Boolean,
      graph: LoadedGraph,
      filterOut: Seq[Regex],
  ): String = {
    val hidden    = (id: String) => filterOut.exists(_.findFirstIn(id).isDefined)
    val filtNodes = result.nodes.filterNot(hidden)
    val filtSet   = filtNodes.toSet
    val filtEdges = result.edges.filter { case (s, t) => filtSet(s) && filtSet(t) }

    val nodesArr = arr(filtNodes.map(nodeJson(_, graph)))
    val edgesArr = arr(filtEdges.map { case (s, t) => obj("from" -> str(s), "to" -> str(t)) })

    val hintsArr = readHintsJson(filtNodes, graph.meta)

    val fields = Seq(
      "query"     -> queryJson,
      "found"     -> filtNodes.nonEmpty.toString,
      "truncated" -> result.truncated.toString,
      "nodes"     -> nodesArr,
      "edges"     -> edgesArr,
      "readHints" -> hintsArr,
    ) ++ (if (compileError) Seq("compileError" -> "true") else Nil)
    obj(fields: _*)
  }

  // startLine/endLine stored 0-based in SemanticDB; +1 for human-readable output
  private def metaFields(id: String, meta: Option[NodeMeta]): Seq[(String, String)] = meta match {
    case Some(m) =>
      Seq(
        "id"          -> str(id),
        "displayName" -> str(m.displayName),
        "file"        -> str(m.file),
        "startLine"   -> (m.startLine + 1).toString,
        "endLine"     -> (m.endLine + 1).toString,
      )
    case None => Seq("id" -> str(id))
  }

  private def nodeJson(id: String, graph: LoadedGraph): String =
    obj(metaFields(id, graph.meta.get(id)): _*)

  /** Group nodes by file, merge ranges with gap < 10 lines, output 1-based. */
  private def readHintsJson(nodeIds: Seq[String], meta: Map[String, NodeMeta]): String = {
    val byFile = nodeIds
      .flatMap(id => meta.get(id).map(m => m.file -> (m.startLine, m.endLine)))
      .groupBy(_._1)
      .toSeq
      .sortBy(_._1)

    val hints = byFile.map { case (file, pairs) =>
      val sorted = pairs.map(_._2).sortBy(_._1)
      val merged = sorted
        .foldLeft(List.empty[(Int, Int)]) {
          case (Nil, range) => List(range)
          case (head :: tail, (s, e)) =>
            if (s - head._2 <= 10) (head._1, math.max(head._2, e)) :: tail
            else (s, e) :: head :: tail
        }
        .reverse

      val rangesArr = arr(merged.map { case (s, e) =>
        obj("start" -> (s + 1).toString, "end" -> (e + 1).toString)
      })
      obj("file" -> str(file), "ranges" -> rangesArr)
    }
    arr(hints)
  }

  private def str(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  private def obj(fields: (String, String)*): String =
    fields
      .map { case (k, v) =>
        val lines = v.split("\n", -1)
        if (lines.length == 1) s"  ${str(k)}: $v"
        else {
          val rest = lines.tail.map("  " + _).mkString("\n")
          s"  ${str(k)}: ${lines.head}\n$rest"
        }
      }
      .mkString("{\n", ",\n", "\n}")

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
