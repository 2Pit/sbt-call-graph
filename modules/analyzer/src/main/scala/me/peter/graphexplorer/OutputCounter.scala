package me.peter.graphexplorer

import java.nio.file.{Files, Path}

/** Shared counter for output files across all formats (.json, .md, etc.).
 *  Scans all numeric-named files in the directory regardless of extension,
 *  so JSON and Mermaid outputs share a single monotonically increasing sequence.
 */
object OutputCounter {

  def next(dir: Path, extension: String): Path = lock.synchronized {
    Files.createDirectories(dir)
    import scala.collection.JavaConverters._
    val stream = Files.list(dir)
    val max =
      try stream.iterator().asScala
        .map(_.getFileName.toString)
        .flatMap { name =>
          val dot = name.lastIndexOf('.')
          if (dot > 0) scala.util.Try(name.substring(0, dot).toLong).toOption else None
        }
        .reduceOption(_ max _)
        .getOrElse(0L)
      finally stream.close()
    dir.resolve(s"${max + 1}$extension")
  }

  private val lock = new Object
}
