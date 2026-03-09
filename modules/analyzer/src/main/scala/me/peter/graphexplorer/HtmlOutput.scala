package me.peter.graphexplorer

import java.nio.file.Path
import scala.util.matching.Regex

object HtmlOutput {

  def nextOutputFile(dir: Path): Path = OutputCounter.next(dir, ".html")

  def writeGraphResult(
      result:    GraphResult,
      title:     String,
      graph:     LoadedGraph,
      outFile:   Path,
      filterOut: Seq[Regex] = Nil,
  ): Path = {
    val (fn, fe) = DotOutput.applyFilter(result.nodes.toSet, result.edges.toSet, filterOut)
    DotOutput.write(outFile, render(fn, fe, graph, title))
  }

  private val palette = Array(
    "#aec6e8","#ffd59e","#b5e0a8","#f4b8b8","#b8dedd",
    "#faeaa0","#d9b8d9","#ffd6dc","#d4bfb0","#a8d4d2",
    "#c8e6e3","#ecd3e8","#c8edc0","#fae9a0","#edbdd4",
    "#d4e8f8","#ffe8c0","#fce0eb","#d8f0e4","#e4d8cc",
  )

  /** Generates JSON-only data for the browser; DOT is built entirely in JS.
   *  Tests can call this directly to inspect the generated HTML.
   */
  private[graphexplorer] def render(
      nodes: Set[String],
      edges: Set[(String, String)],
      graph: LoadedGraph,
      title: String,
  ): String = {
    val data       = DotOutput.prepareData(nodes, graph)
    val classes    = data.sorted.map(fqn => DotOutput.classFromFqn(fqn)).distinct.sorted
    val classColor = classes.zipWithIndex.map { case (c, i) => c -> palette(i % palette.length) }.toMap

    val metaEntries = data.sorted.map { fqn =>
      val m         = graph.meta.get(fqn)
      val label     = m.map(_.displayName).getOrElse(fqn)
      val file      = m.map(_.file).getOrElse("unknown")
      val cls       = DotOutput.classFromFqn(fqn)
      val startLine = m.map(_.startLine + 1).getOrElse(0)
      val endLine   = m.map(_.endLine + 1).getOrElse(0)
      val color     = classColor.getOrElse(cls, palette(0))
      val fileName  = file.split("/").last
      s"""${DotOutput.dq(data.idOf(fqn))}:{"label":${DotOutput.dq(label)},"fqn":${DotOutput.dq(fqn)},"file":${DotOutput.dq(fileName)},"cls":${DotOutput.dq(cls)},"startLine":$startLine,"endLine":$endLine,"color":${DotOutput.dq(color)}}"""
    }
    val edgeEntries = edges.toSeq.flatMap { case (from, to) =>
      for (fid <- data.idOf.get(from); tid <- data.idOf.get(to)) yield
        s"""{"from":${DotOutput.dq(fid)},"to":${DotOutput.dq(tid)}}"""
    }

    val metaJson  = metaEntries.mkString("{", ",\n", "}")
    val edgesJson = edgeEntries.mkString("[", ",\n", "]")

    template(title, metaJson, edgesJson)
  }

  /** Render a self-contained demo page from embedded sample data (no SemanticDB required). */
  def renderDemo(): String = {
    def fqn(cls: String, method: String) = s"demo/$cls#$method()."
    def node(cls: String, method: String, line: Int): (String, NodeMeta) =
      fqn(cls, method) -> NodeMeta(file = s"$cls.scala", startLine = line - 1, endLine = line + 4, displayName = method)

    // Classes A–G; method AB means "in A, calls B"; A0/A1 means standalone methods of A.
    val metaMap = Map(
      node("A", "AB",  5), node("A", "AC", 12), node("A", "A0", 19),
      node("B", "BC",  5), node("B", "BD", 12), node("B", "B0", 19),
      node("C", "CE",  5), node("C", "C0", 12),
      node("D", "DF",  5), node("D", "D0", 12),
      node("E", "EF",  5), node("E", "EG", 12),
      node("F", "FG",  5), node("F", "F0", 12),
      node("G", "G0",  5), node("G", "G1", 12),
    )
    val edges = Seq(
      fqn("A","AB") -> fqn("B","BC"), fqn("A","AB") -> fqn("B","BD"),
      fqn("A","AC") -> fqn("C","CE"), fqn("A","AC") -> fqn("C","C0"),
      fqn("B","BC") -> fqn("C","CE"),
      fqn("B","BD") -> fqn("D","DF"),
      fqn("C","CE") -> fqn("E","EF"), fqn("C","CE") -> fqn("E","EG"),
      fqn("D","DF") -> fqn("F","FG"),
      fqn("E","EF") -> fqn("F","F0"),
      fqn("E","EG") -> fqn("G","G1"),
      fqn("F","FG") -> fqn("G","G0"),
    )
    val out   = edges.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2).toSet }
    val in    = edges.groupBy(_._2).map { case (k, vs) => k -> vs.map(_._1).toSet }
    val graph = LoadedGraph(out, in, metaMap)
    render(metaMap.keySet, edges.toSet, graph, "Demo Graph")
  }

  // @@TITLE@@, @@META@@, @@EDGES@@ replaced at render time.
  private def template(title: String, metaJson: String, edgesJson: String): String =
    rawTemplate
      .replace("@@TITLE@@", title.replace("&", "&amp;").replace("<", "&lt;"))
      .replace("@@META@@",   metaJson)
      .replace("@@EDGES@@",  edgesJson)

  private lazy val rawTemplate: String = {
    val stream = Option(getClass.getResourceAsStream("graph.html"))
      .getOrElse(sys.error("[graph-explorer] graph.html resource not found"))
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()
  }
}
