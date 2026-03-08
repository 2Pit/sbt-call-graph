package me.peter.graphexplorer

import java.nio.file.Path

object CallGraphState {

  private case class Cached(roots: Set[Path], stamp: Long, sourceRoot: Option[Path], graph: LoadedGraph)

  @volatile private var cached: Option[Cached] = None

  /** `stamp` should be a value that changes when the SemanticDB files are regenerated
   *  (e.g. the mtime of SBT's compile-analysis file).  A cache hit requires the same
   *  roots, same sourceRoot, AND the same stamp — so the file walk that used to happen
   *  on every call is gone entirely.
   */
  def getOrLoad(semanticdbRoots: Seq[Path], sourceRoot: Option[Path] = None, stamp: Long = 0L): LoadedGraph = {
    val rootSet = semanticdbRoots.toSet
    cached match {
      case Some(c) if c.roots == rootSet && c.stamp == stamp && c.sourceRoot == sourceRoot =>
        c.graph
      case _ =>
        val graph = GraphLoader.load(semanticdbRoots, sourceRoot)
        synchronized { cached = Some(Cached(rootSet, stamp, sourceRoot, graph)) }
        graph
    }
  }

  def invalidate(): Unit = synchronized { cached = None }

  def status: String = cached match {
    case None    => "not loaded"
    case Some(c) => s"loaded (nodes=${c.graph.nodeCount}, edges=${c.graph.edgeCount})"
  }
}
