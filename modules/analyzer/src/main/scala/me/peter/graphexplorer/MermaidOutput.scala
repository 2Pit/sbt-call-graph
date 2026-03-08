package me.peter.graphexplorer

import java.nio.file.Path

object MermaidOutput {

  def nextOutputFile(dir: Path): Path = OutputCounter.next(dir, ".md")

  /** Neighbourhood graph: vertex + all in/out nodes, edges from graph restricted to that set. */
  def writeViaResult(
      result:  Option[QueryEngine.ViaResult],
      vertex:  String,
      graph:   LoadedGraph,
      outFile: Path,
  ): Path = {
    val (nodes, edges) = DotOutput.viaNodesEdges(result, vertex, graph)
    DotOutput.write(outFile, render(nodes, edges, graph))
  }

  /** Path graph: union of all path nodes and consecutive edges across all paths. */
  def writePathResult(
      result:  QueryEngine.PathResult,
      graph:   LoadedGraph,
      outFile: Path,
  ): Path = {
    val (nodes, edges) = DotOutput.pathNodesEdges(result)
    DotOutput.write(outFile, render(nodes, edges, graph))
  }

  private def render(nodes: Set[String], edges: Set[(String, String)], graph: LoadedGraph): String = {
    val data = DotOutput.prepareData(nodes, graph)

    val sb = new StringBuilder
    sb.append("```mermaid\nflowchart LR\n")

    data.byGroup.toSeq.sortBy(_._1).foreach { case (className, fileNodes) =>
      sb.append(s"""  subgraph "$className"\n""")
      fileNodes
        .sortBy(n => graph.meta.get(n).map(_.startLine).getOrElse(0))
        .foreach { fqn =>
          val label = graph.meta.get(fqn).map(_.displayName).getOrElse(fqn)
          sb.append(s"""    ${data.idOf(fqn)}["$label"]\n""")
        }
      sb.append("  end\n")
    }

    edges.toSeq.sortBy { case (f, t) => (data.idOf.getOrElse(f, ""), data.idOf.getOrElse(t, "")) }
      .foreach { case (from, to) =>
        for (fid <- data.idOf.get(from); tid <- data.idOf.get(to))
          sb.append(s"  $fid --> $tid\n")
      }

    sb.append("```\n")
    sb.toString()
  }
}
