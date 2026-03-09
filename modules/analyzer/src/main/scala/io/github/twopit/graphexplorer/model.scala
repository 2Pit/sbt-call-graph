package io.github.twopit.graphexplorer

/** Metadata for a single graph vertex (method). */
final case class NodeMeta(
  file: String,
  startLine: Int,  // 0-based, as stored in SemanticDB
  endLine: Int,    // 0-based; equals startLine for fields/vals (no block body)
  displayName: String,
)

/** In-memory call graph. */
final case class LoadedGraph(
  out: Map[String, Set[String]],  // caller  → callees
  in: Map[String, Set[String]],   // callee  → callers
  meta: Map[String, NodeMeta],
) {
  def nodeCount: Int = meta.size
  def edgeCount: Int = out.values.map(_.size).sum
}

object LoadedGraph {
  val empty: LoadedGraph = LoadedGraph(Map.empty, Map.empty, Map.empty)
}

/** Result of a graph traversal query (paths or neighbourhood). */
final case class GraphResult(
    nodes:     Seq[String],           // sorted by (file, startLine)
    edges:     Seq[(String, String)], // (caller, callee) pairs
    truncated: Boolean = false,
)

object GraphResult {
  val empty: GraphResult = GraphResult(Nil, Nil)
}

/** Sort a collection of node FQNs by source location (file asc, startLine asc). */
private[graphexplorer] object NodeSort {
  def byLocation(ids: Iterable[String], meta: Map[String, NodeMeta]): Seq[String] =
    ids.toSeq.sortBy(id => meta.get(id).map(m => (m.file, m.startLine)).getOrElse(("", 0)))
}
