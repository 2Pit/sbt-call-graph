package me.peter.graphexplorer

import scala.collection.mutable

object QueryEngine {

  final case class PathResult(
      paths:     Seq[Seq[String]], // each path is a list of node FQNs
      truncated: Boolean,
  )

  /**
   * Find simple paths from `from` to `to` in the call graph using DFS.
   * Results are NOT guaranteed to be sorted by length; use maxPaths to bound output.
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
    if (!graph.meta.contains(from)) return PathResult(Nil, truncated = false)
    if (!graph.meta.contains(to))   return PathResult(Nil, truncated = false)
    if (from == to)                 return PathResult(Seq(Seq(from)), truncated = false)

    val results = mutable.ListBuffer.empty[Seq[String]]
    var truncated = false

    val path    = mutable.ArrayBuffer[String](from)
    val visited = mutable.Set[String](from)

    def dfs(node: String): Unit = {
      if (truncated) return
      for (next <- graph.out.getOrElse(node, Set.empty)) {
        if (!visited(next)) {
          if (next == to) {
            results += path.toList :+ next
            if (results.size >= maxPaths) truncated = true
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
    PathResult(results.toSeq, truncated)
  }

  /** Node annotated with its BFS depth from the queried vertex. */
  final case class DepthNode(id: String, depth: Int)

  /**
   * @param in   methods that (transitively) call V, sorted by (depth, file, startLine)
   * @param out  methods that V (transitively) calls, sorted by (depth, file, startLine)
   */
  final case class ViaResult(in: Seq[DepthNode], out: Seq[DepthNode])

  /**
   * Return the neighbourhood of vertex `v` up to `depth` BFS hops in each direction.
   * Results are sorted by (depth asc, file asc, startLine asc).
   */
  def viaVertex(
      graph:    LoadedGraph,
      v:        String,
      depthIn:  Int = 2,
      depthOut: Int = 2,
  ): Option[ViaResult] =
    if (!graph.meta.contains(v)) None
    else Some(ViaResult(
      in  = bfsWithDepth(v, depthIn,  graph.in,  graph.meta),
      out = bfsWithDepth(v, depthOut, graph.out, graph.meta),
    ))

  /**
   * Search vertices whose FQN or displayName contains `query` (case-sensitive).
   * Returns up to `maxResults` matching IDs, sorted by (file, startLine).
   */
  def search(graph: LoadedGraph, query: String, maxResults: Int = 50): Seq[String] =
    graph.meta
      .collect { case (id, m) if m.displayName.contains(query) || id.contains(query) => id }
      .toSeq
      .sortBy(id => graph.meta.get(id).map(m => (m.file, m.startLine)).getOrElse(("", 0)))
      .take(maxResults)

  final case class ModuleEdge(srcId: String, tgtId: String)
  final case class ModuleResult(outgoing: Seq[ModuleEdge], incoming: Seq[ModuleEdge])

  /**
   * Return all call-graph edges that cross the boundary of a module identified by `pathPrefix`.
   * A vertex belongs to the module if its file path contains `pathPrefix`.
   * Only edges where BOTH endpoints are known in meta are included (library calls are excluded).
   *
   * @param outgoing  edges leaving the module  (src inside → tgt outside)
   * @param incoming  edges entering the module (src outside → tgt inside)
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

  private def bfsWithDepth(
      start: String,
      depth: Int,
      edges: Map[String, Set[String]],
      meta:  Map[String, NodeMeta],
  ): Seq[DepthNode] = {
    val visited = mutable.Map.empty[String, Int] // node -> hop count
    val queue   = mutable.Queue.empty[(String, Int)]

    if (depth <= 0) return Nil

    edges.getOrElse(start, Set.empty).foreach { n =>
      visited(n) = 1
      queue.enqueue((n, 1))
    }

    while (queue.nonEmpty) {
      val (node, hops) = queue.dequeue()
      if (hops < depth) {
        edges.getOrElse(node, Set.empty).foreach { next =>
          if (!visited.contains(next)) {
            visited(next) = hops + 1
            queue.enqueue((next, hops + 1))
          }
        }
      }
    }

    visited.toSeq
      .map { case (id, d) => DepthNode(id, d) }
      .sortWith { (a, b) =>
        if (a.depth != b.depth) a.depth < b.depth
        else {
          val fa = meta.get(a.id).map(_.file).getOrElse("")
          val fb = meta.get(b.id).map(_.file).getOrElse("")
          if (fa != fb) fa < fb
          else meta.get(a.id).map(_.startLine).getOrElse(0) <
               meta.get(b.id).map(_.startLine).getOrElse(0)
        }
      }
  }
}
