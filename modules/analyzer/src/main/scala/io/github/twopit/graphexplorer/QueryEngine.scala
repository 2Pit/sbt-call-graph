package io.github.twopit.graphexplorer

import scala.collection.mutable

object QueryEngine {

  /**
   * Find paths among a set of vertices.
   * For each prefix pair (v_i, remaining v[i+1..]), finds all directed paths from v_i to
   * any vertex in v[i+1..]. The union of all found path nodes/edges is returned.
   */
  def pathsAmong(
      graph:    LoadedGraph,
      vertices: Seq[String],
      maxDepth: Int = 20,
      maxPaths: Int = 100,
  ): GraphResult = {
    val known = vertices.filter(graph.meta.contains)
    if (known.size < 2) return GraphResult(NodeSort.byLocation(known.toSet, graph.meta), Nil)

    val allPaths  = mutable.ListBuffer.empty[Seq[String]]
    var truncated = false
    var remaining = maxPaths

    known.zipWithIndex.foreach { case (from, i) =>
      if (!truncated) {
        val targets        = known.drop(i + 1).toSet
        val (paths, trunc) = collectPaths(graph, from, targets, maxDepth, remaining)
        allPaths  ++= paths
        remaining -= paths.size
        if (trunc || remaining <= 0) truncated = true
      }
    }

    buildResult(allPaths.toSeq, truncated, graph.meta)
  }

  /**
   * Return the neighbourhood of vertex `v` up to BFS depth in each direction.
   * Returns all reachable nodes (including v) and the induced subgraph edges.
   */
  def viaVertex(
      graph:    LoadedGraph,
      v:        String,
      depthIn:  Int = 2,
      depthOut: Int = 2,
  ): Option[GraphResult] = {
    if (!graph.meta.contains(v)) return None
    val inNodes  = bfsNodes(v, depthIn,  graph.in)
    val outNodes = bfsNodes(v, depthOut, graph.out)
    val allNodes = inNodes ++ outNodes + v
    val edges = allNodes.toSeq.flatMap { n =>
      graph.out.getOrElse(n, Set.empty).collect { case t if allNodes(t) => n -> t }
    }
    Some(GraphResult(NodeSort.byLocation(allNodes, graph.meta), edges))
  }

  /**
   * Search vertices whose FQN or displayName contains `query` (case-sensitive).
   * Returns up to `maxResults` matching IDs, sorted by (file, startLine).
   */
  def search(graph: LoadedGraph, query: String, maxResults: Int = 50): Seq[String] =
    NodeSort.byLocation(
      graph.meta.collect { case (id, m) if m.displayName.contains(query) || id.contains(query) => id },
      graph.meta,
    ).take(maxResults)

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private def buildResult(
      paths:     Seq[Seq[String]],
      truncated: Boolean,
      meta:      Map[String, NodeMeta],
  ): GraphResult = {
    val nodes = NodeSort.byLocation(paths.flatten.toSet, meta)
    val edges = paths.flatMap(p => p.zip(p.tail)).distinct
    GraphResult(nodes, edges, truncated)
  }

  /** BFS from `start` following `edges` up to `depth` hops; returns visited nodes (not `start`). */
  private def bfsNodes(start: String, depth: Int, edges: Map[String, Set[String]]): Set[String] = {
    if (depth <= 0) return Set.empty
    val visited = mutable.Set.empty[String]
    val queue   = mutable.Queue.empty[(String, Int)]

    edges.getOrElse(start, Set.empty).foreach { n =>
      if (visited.add(n)) queue.enqueue((n, 1))
    }

    while (queue.nonEmpty) {
      val (node, hops) = queue.dequeue()
      if (hops < depth) {
        edges.getOrElse(node, Set.empty).foreach { next =>
          if (visited.add(next)) queue.enqueue((next, hops + 1))
        }
      }
    }

    visited.toSet
  }

  /** DFS from `from`; records a path whenever a node in `targets` is reached.
   *  Stops at target nodes (does not recurse through them).
   *  Returns (paths, truncated). */
  private def collectPaths(
      graph:    LoadedGraph,
      from:     String,
      targets:  Set[String],
      maxDepth: Int,
      maxPaths: Int,
  ): (Seq[Seq[String]], Boolean) = {
    val results = mutable.ListBuffer.empty[Seq[String]]
    var done    = false

    val path    = mutable.ArrayBuffer[String](from)
    val visited = mutable.Set[String](from)

    def dfs(node: String): Unit = {
      if (done) return
      for (next <- graph.out.getOrElse(node, Set.empty)) {
        if (done) return
        if (!visited(next)) {
          if (targets(next)) {
            results += path.toList :+ next
            if (results.size >= maxPaths) { done = true; return }
          } else if (path.size - 1 < maxDepth) {
            path    += next
            visited += next
            dfs(next)
            visited -= next
            path.remove(path.size - 1)
          }
        }
      }
    }

    dfs(from)
    (results.toSeq, done)
  }
}
