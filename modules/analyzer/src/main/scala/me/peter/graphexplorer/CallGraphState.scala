package me.peter.graphexplorer

import java.nio.file.{Files, Path}
import java.util.stream.Collectors

object CallGraphState {

  private case class Cached(mtime: Long, graph: LoadedGraph)

  @volatile private var cached: Option[Cached] = None

  def getOrLoad(semanticdbRoot: Path): LoadedGraph = {
    val mtime = lastModified(semanticdbRoot)
    cached match {
      case Some(c) if c.mtime == mtime => c.graph
      case _ =>
        val graph = GraphLoader.load(semanticdbRoot)
        synchronized { cached = Some(Cached(mtime, graph)) }
        graph
    }
  }

  def invalidate(): Unit = synchronized { cached = None }

  def status: String = cached match {
    case None    => "not loaded"
    case Some(c) => s"loaded (nodes=${c.graph.nodeCount}, edges=${c.graph.edgeCount})"
  }

  private def lastModified(dir: Path): Long = {
    if (!Files.exists(dir)) return 0L
    Files.walk(dir)
      .collect(Collectors.toList[Path])
      .asScala
      .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".semanticdb"))
      .map(Files.getLastModifiedTime(_).toMillis)
      .maxOption
      .getOrElse(0L)
  }

  private implicit class RichList[A](l: java.util.List[A]) {
    def asScala: List[A] = {
      val buf = List.newBuilder[A]
      l.forEach(buf += _)
      buf.result()
    }
  }
}
