package io.github.twopit.callgraph

final case class ModuleEdge(srcId: String, tgtId: String)
final case class ModuleResult(outgoing: Seq[ModuleEdge], incoming: Seq[ModuleEdge])

/** Finds call-graph edges that cross the boundary of a module identified by a file path prefix. */
object ModuleQuery {

  /** Return all edges crossing the boundary of the module whose files match `pathPrefix`.
    *  Only edges where BOTH endpoints are known in meta are included.
    */
  def moduleEdges(graph: LoadedGraph, pathPrefix: String): ModuleResult = {
    val inside = graph.meta.collect { case (id, m) if m.file.contains(pathPrefix) => id }.toSet

    val outgoing = (for {
      src <- inside.toSeq
      tgt <- graph.out.getOrElse(src, Set.empty).toSeq
      if !inside(tgt) && graph.meta.contains(tgt)
    } yield ModuleEdge(src, tgt))
      .sortBy(e => graph.meta.get(e.srcId).map(m => (m.file, m.startLine)).getOrElse(("", 0)))
      .distinct

    val incoming = (for {
      tgt <- inside.toSeq
      src <- graph.in.getOrElse(tgt, Set.empty).toSeq
      if !inside(src) && graph.meta.contains(src)
    } yield ModuleEdge(src, tgt))
      .sortBy(e => graph.meta.get(e.tgtId).map(m => (m.file, m.startLine)).getOrElse(("", 0)))
      .distinct

    ModuleResult(outgoing, incoming)
  }
}
