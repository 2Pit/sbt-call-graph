package me.peter.graphexplorer

import scala.meta._
import scala.meta.internal.semanticdb._
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

object GraphLoader {

  /**
   * Load a call graph by merging all .semanticdb files found under each root in `semanticdbRoots`.
   *
   * @param sourceRoot
   *   root directory from which `doc.uri` paths are resolved (typically the build root). When None, falls back to
   *   inferring it from the first root (assumes layout `<module>/target/scala-X.YY/meta`).
   */
  def load(semanticdbRoots: Seq[Path], sourceRoot: Option[Path] = None): LoadedGraph = {
    val files = semanticdbRoots.flatMap { root =>
      if (!Files.exists(root)) Nil
      else {
        val stream = Files.walk(root)
        try stream.iterator().asScala.filter(_.toString.endsWith(".semanticdb")).toList
        finally stream.close()
      }
    }.distinct

    if (files.isEmpty) {
      System.err.println(
        s"[graph-explorer] WARNING: no .semanticdb files found under: ${semanticdbRoots.mkString(", ")}"
      )
      System.err.println(s"[graph-explorer] Run 'compile' first.")
      return LoadedGraph.empty
    }

    // Prefer explicit sourceRoot; fall back to inferring from the first root's path layout.
    val projectRoot: Option[Path] = sourceRoot.orElse {
      semanticdbRoots.headOption
        .map(_.getParent.getParent.getParent.getParent)
        .filter(Files.isDirectory(_))
    }

    val outB  = collection.mutable.Map.empty[String, collection.mutable.Set[String]]
    val inB   = collection.mutable.Map.empty[String, collection.mutable.Set[String]]
    val metaB = collection.mutable.Map.empty[String, NodeMeta]

    files.foreach { path =>
      try processFile(path, projectRoot, outB, inB, metaB)
      catch {
        case e: Exception =>
          System.err.println(s"[graph-explorer] WARNING: failed to parse $path: ${e.getMessage}")
      }
    }

    if (outB.isEmpty)
      System.err.println(
        s"[graph-explorer] WARNING: graph is empty after loading ${files.size} files."
      )

    LoadedGraph(
      out = outB.map { case (k, v) => k -> v.toSet }.toMap,
      in = inB.map { case (k, v) => k -> v.toSet }.toMap,
      meta = metaB.toMap,
    )
  }

  private def processFile(
      path: Path,
      projectRoot: Option[Path],
      out: collection.mutable.Map[String, collection.mutable.Set[String]],
      in: collection.mutable.Map[String, collection.mutable.Set[String]],
      meta: collection.mutable.Map[String, NodeMeta],
  ): Unit = {
    val bytes = Files.readAllBytes(path)
    val docs  = TextDocuments.parseFrom(bytes)

    docs.documents.foreach { doc =>
      val kindOf: Map[String, SymbolInformation.Kind] =
        doc.symbols.map(s => s.symbol -> s.kind).toMap

      val displayNameOf: Map[String, String] =
        doc.symbols.map(s => s.symbol -> s.displayName).toMap

      val endLineOf: Map[Int, Int] = projectRoot.flatMap { root =>
        val sourceFile = root.resolve(doc.uri)
        if (Files.exists(sourceFile)) Some(parseEndLines(sourceFile)) else None
      }.getOrElse(Map.empty)

      // Array for O(1) random access during binary search; sorted by startLine
      val methodDefs: Array[(String, Int)] =
        doc.occurrences.filter { occ =>
          occ.role == SymbolOccurrence.Role.DEFINITION &&
          occ.range.isDefined &&
          !isSynthetic(occ.symbol) &&
          kindOf.get(occ.symbol).contains(SymbolInformation.Kind.METHOD)
        }
          .map(occ => occ.symbol -> occ.range.get.startLine)
          .toArray
          .sortBy(_._2)

      methodDefs.foreach { case (sym, line) =>
        if (!meta.contains(sym)) {
          meta(sym) = NodeMeta(
            file = doc.uri,
            startLine = line,
            endLine = endLineOf.getOrElse(line, line),
            displayName = displayNameOf.getOrElse(sym, sym),
          )
        }
      }

      if (methodDefs.isEmpty) return

      // Binary search: find rightmost method with startLine <= line
      def callerAt(line: Int): Option[String] = {
        var lo = 0; var hi = methodDefs.length - 1; var found = -1
        while (lo <= hi) {
          val mid = (lo + hi) >>> 1
          if (methodDefs(mid)._2 <= line) { found = mid; lo = mid + 1 }
          else hi = mid - 1
        }
        if (found >= 0) Some(methodDefs(found)._1) else None
      }

      doc.occurrences.filter { occ =>
        occ.role == SymbolOccurrence.Role.REFERENCE &&
        occ.range.isDefined &&
        !isSynthetic(occ.symbol) &&
        kindOf.get(occ.symbol).contains(SymbolInformation.Kind.METHOD)
      }.foreach { occ =>
        val callee = occ.symbol
        callerAt(occ.range.get.startLine).foreach { caller =>
          if (caller != callee) {
            out.getOrElseUpdate(caller, collection.mutable.Set.empty) += callee
            in.getOrElseUpdate(callee, collection.mutable.Set.empty) += caller
          }
        }
      }
    }
  }

  /**
   * Parse a Scala source file; return methodStartLine -> methodEndLine (0-based). Tries Scala 2.13 dialect first, then
   * Scala 3 as fallback.
   */
  private def parseEndLines(sourceFile: Path): Map[Int, Int] = {
    val input                                = Input.File(sourceFile.toFile)
    def tryParse(d: Dialect): Option[Source] = {
      implicit val dialect: Dialect = d
      input.parse[Source].toOption
    }
    tryParse(dialects.Scala213)
      .orElse(tryParse(dialects.Scala3))
      .map { tree =>
        tree.collect {
          case d: Defn.Def   => d.name.pos.startLine -> d.pos.endLine
          case d: Defn.Macro => d.name.pos.startLine -> d.pos.endLine
        }.toMap
      }
      .getOrElse(Map.empty)
  }

  // SemanticDB local symbols are "local" followed by decimal digits: local0, local1, ...
  private def isSynthetic(symbol: String): Boolean =
    symbol.isEmpty ||
      (symbol.length > 5 && symbol.startsWith("local") && symbol.drop(5).forall(Character.isDigit))
}
