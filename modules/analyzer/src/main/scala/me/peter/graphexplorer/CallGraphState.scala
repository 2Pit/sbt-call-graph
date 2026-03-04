package me.peter.graphexplorer

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

object CallGraphState {

  private case class Cached(mtime: Long, sourceRoot: Option[Path], graph: LoadedGraph)

  @volatile private var cached: Option[Cached] = None

  def getOrLoad(semanticdbRoot: Path, sourceRoot: Option[Path] = None): LoadedGraph = {
    val mtime = lastModified(semanticdbRoot)
    cached match {
      case Some(c) if c.mtime == mtime && c.sourceRoot == sourceRoot => c.graph
      case _ =>
        val graph = GraphLoader.load(semanticdbRoot, sourceRoot)
        synchronized { cached = Some(Cached(mtime, sourceRoot, graph)) }
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
    val stream = Files.walk(dir)
    try
      stream.iterator().asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".semanticdb"))
        .map(Files.getLastModifiedTime(_).toMillis)
        .reduceOption(_ max _)
        .getOrElse(0L)
    finally stream.close()
  }
}