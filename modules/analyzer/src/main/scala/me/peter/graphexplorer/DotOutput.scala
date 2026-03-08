package me.peter.graphexplorer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.matching.Regex

object DotOutput {

  def nextOutputFile(dir: Path): Path = OutputCounter.next(dir, ".dot")

  def writeGraphResult(
      result:    GraphResult,
      title:     String,
      graph:     LoadedGraph,
      outFile:   Path,
      filterOut: Seq[Regex] = Nil,
  ): Path = {
    val (fn, fe) = applyFilter(result.nodes.toSet, result.edges.toSet, filterOut)
    write(outFile, render(fn, fe, graph, title))
  }

  private[graphexplorer] def applyFilter(
      nodes:     Set[String],
      edges:     Set[(String, String)],
      filterOut: Seq[Regex],
  ): (Set[String], Set[(String, String)]) =
    if (filterOut.isEmpty) (nodes, edges)
    else {
      val kept = nodes.filterNot(n => filterOut.exists(_.findFirstIn(n).isDefined))
      (kept, edges.filter { case (a, b) => kept(a) && kept(b) })
    }

  /** Extract class/object name from a SemanticDB FQN.
   *  "a/Foo#compute()." → "Foo",  "a/Bar.method()." → "Bar"
   */
  private[graphexplorer] def classFromFqn(fqn: String): String = {
    val afterSlash = fqn.substring(fqn.lastIndexOf('/') + 1)
    val sepIdx     = afterSlash.indexWhere(c => c == '#' || c == '.')
    if (sepIdx > 0) afterSlash.substring(0, sepIdx) else afterSlash
  }

  /** Shared graph layout used by HtmlOutput and MermaidOutput. */
  private[graphexplorer] case class GraphData(
    sorted:  Seq[String],
    idOf:    Map[String, String],
    byGroup: Map[String, Seq[String]],   // class name → FQNs
  )

  private[graphexplorer] def prepareData(nodes: Set[String], graph: LoadedGraph): GraphData = {
    val sorted  = NodeSort.byLocation(nodes, graph.meta)
    val idOf    = sorted.zipWithIndex.map { case (fqn, i) => fqn -> s"n$i" }.toMap
    val byGroup = sorted.groupBy(fqn => classFromFqn(fqn))
    GraphData(sorted, idOf, byGroup)
  }

  private[graphexplorer] def renderGraph(
    data:  GraphData,
    edges: Set[(String, String)],
    graph: LoadedGraph,
    title: String,
  ): String = {
    val GraphData(_, idOf, byGroup) = data

    val sb = new StringBuilder
    sb.append(s"""digraph ${dq(title)} {\n""")
    sb.append("  rankdir=LR;\n")
    sb.append("  node [shape=box style=rounded fontname=monospace fontsize=10];\n")
    sb.append("  edge [fontsize=9];\n\n")

    byGroup.toSeq.sortBy(_._1).zipWithIndex.foreach { case ((className, fqns), gi) =>
      sb.append(s"  subgraph cluster_$gi {\n")
      sb.append(s"    label=${dq(className)};\n")
      sb.append( "    style=rounded;\n")
      fqns.sortBy(n => graph.meta.get(n).map(_.startLine).getOrElse(0)).foreach { fqn =>
        val label = graph.meta.get(fqn).map(_.displayName).getOrElse(fqn)
        sb.append(s"    ${idOf(fqn)} [label=${dq(label)} tooltip=${dq(fqn)}];\n")
      }
      sb.append("  }\n\n")
    }

    edges.toSeq.sortBy { case (f, t) => (idOf.getOrElse(f, ""), idOf.getOrElse(t, "")) }
      .foreach { case (from, to) =>
        for (fid <- idOf.get(from); tid <- idOf.get(to))
          sb.append(s"  $fid -> $tid;\n")
      }

    sb.append("}\n")
    sb.toString()
  }

  private def render(nodes: Set[String], edges: Set[(String, String)], graph: LoadedGraph, title: String): String =
    renderGraph(prepareData(nodes, graph), edges, graph, title)

  private[graphexplorer] def dq(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  private[graphexplorer] def write(path: Path, content: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path
  }
}
