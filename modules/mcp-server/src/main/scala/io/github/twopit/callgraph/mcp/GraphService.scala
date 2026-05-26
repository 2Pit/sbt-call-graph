package io.github.twopit.callgraph.mcp

import io.github.twopit.callgraph.{CallGraphState, LoadedGraph}

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

/** Load status distinguishing two distinct failure modes that previously
  * collapsed into a single `compileError` flag:
  *
  *  - `notCompiled` — no `.semanticdb` directories found (run `sbt compile`)
  *  - `emptyGraph`  — files were loaded but no METHOD symbols were extracted
  *
  * `unusable = notCompiled || emptyGraph` is the legacy single-flag meaning
  * still useful for downstream renderers that just need to know "is the graph
  * queryable?".
  */
final case class GraphStatus(message: String, notCompiled: Boolean, emptyGraph: Boolean) {
  def unusable: Boolean = notCompiled || emptyGraph
}

/** Thin wrapper around CallGraphState that resolves the workspace root to a set of
  * SemanticDB directories and computes a cache-invalidation stamp from .semanticdb
  * file mtimes.
  *
  * Mirrors what SBT does via compileAnalysisFile, but without SBT.
  */
final class GraphService(
    workspaceRoot: Path,
    explicitSemanticdbDirs: Seq[Path],
) {

  def getGraph(): (LoadedGraph, GraphStatus) = {
    val dirs = if (explicitSemanticdbDirs.nonEmpty) explicitSemanticdbDirs else discoverSemanticdbDirs(workspaceRoot)
    if (dirs.isEmpty) {
      (
        LoadedGraph.empty,
        GraphStatus("no .semanticdb files found — run `sbt compile` first", notCompiled = true, emptyGraph = false),
      )
    } else {
      val stamp = computeStamp(dirs)
      val graph = CallGraphState.getOrLoad(dirs, Some(workspaceRoot), stamp)
      val empty = graph.nodeCount == 0
      val msg   = if (empty) "loaded but empty (no METHOD symbols)" else s"loaded ${dirs.size} dir(s)"
      (graph, GraphStatus(msg, notCompiled = false, emptyGraph = empty))
    }
  }

  /** Discover `target/.../meta` directories under the workspace.
    * SemanticDB writes to `target/<scala-X.Y>/meta/<package>/...`.
    * Heuristic: look for any directory named `meta` whose path also contains `target`.
    */
  private[mcp] def discoverSemanticdbDirs(root: Path): Seq[Path] = {
    if (!Files.isDirectory(root)) return Nil
    val stream = Files.walk(root, 8)
    try
      stream
        .iterator()
        .asScala
        .filter { p =>
          Files.isDirectory(p) &&
          p.getFileName != null &&
          p.getFileName.toString == "meta" &&
          p.toString.contains("target")
        }
        .toList
        .distinct
    finally stream.close()
  }

  /** Stamp = max mtime across all `.semanticdb` files under `dirs`. Sufficient as a
    * cache key for CallGraphState — a recompile updates at least one file.
    */
  private[mcp] def computeStamp(dirs: Seq[Path]): Long = {
    var max: Long = 0L
    dirs.foreach { dir =>
      if (Files.isDirectory(dir)) {
        val stream = Files.walk(dir)
        try
          stream.iterator().asScala.foreach { p =>
            if (p.toString.endsWith(".semanticdb")) {
              val m = Files.getLastModifiedTime(p).toMillis
              if (m > max) max = m
            }
          }
        finally stream.close()
      }
    }
    max
  }
}
