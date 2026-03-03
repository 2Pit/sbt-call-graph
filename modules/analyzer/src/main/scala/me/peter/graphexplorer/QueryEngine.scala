package me.peter.graphexplorer

import scala.collection.mutable

object QueryEngine {

  final case class PathResult(
    paths:     Seq[Seq[String]], // each path is a list of node FQNs
    truncated: Boolean,
  )

  /**
   * Find all simple paths from `from` to `to` in the call graph.
   *
   * @param maxDepth  max path length (number of edges)
   * @param maxPaths  stop after collecting this many paths
   */
  def pathAtoB(
    graph:    LoadedGraph,
    from:     String,
    to:       String,
    maxDepth: Int = 20,
    maxPaths: Int = 100,
  ): PathResult = {
    if (!graph.meta.contains(from))
      return PathResult(Nil, truncated = false)
    if (!graph.meta.contains(to))
      return PathResult(Nil, truncated = false)
    if (from == to)
      return PathResult(Seq(Seq(from)), truncated = false)

    val results   = mutable.ListBuffer.empty[Seq[String]]
    var truncated = false

    // BFS with path tracking; visited per-path to allow simple paths only
    // Each element: (currentNode, pathSoFar, visitedSet)
    val queue = mutable.Queue.empty[(String, List[String], Set[String])]
    queue.enqueue((from, List(from), Set(from)))

    while (queue.nonEmpty && !truncated) {
      val (node, path, visited) = queue.dequeue()

      if (path.size - 1 < maxDepth) {
        graph.out.getOrElse(node, Set.empty).foreach { next =>
          if (!visited.contains(next)) {
            val newPath = path :+ next
            if (next == to) {
              results += newPath
              if (results.size >= maxPaths) truncated = true
            } else if (!truncated) {
              queue.enqueue((next, newPath, visited + next))
            }
          }
        }
      }
    }

    PathResult(results.toSeq, truncated)
  }

  final case class ViaResult(
    callers: Set[String], // methods that call V
    callees: Set[String], // methods that V calls
  )

  /** Return the immediate neighbourhood of vertex `v`. */
  def viaVertex(graph: LoadedGraph, v: String): Option[ViaResult] =
    if (!graph.meta.contains(v)) None
    else Some(ViaResult(
      callers = graph.in.getOrElse(v, Set.empty),
      callees = graph.out.getOrElse(v, Set.empty),
    ))
}
