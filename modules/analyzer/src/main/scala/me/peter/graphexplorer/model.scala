package me.peter.graphexplorer

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
