package io.github.twopit.graphexplorer

import scala.meta._
import scala.meta.internal.semanticdb._
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

object GraphLoader {

  /**
   * Load a call graph by merging all .semanticdb files found under each root in `semanticdbRoots`.
   *
   * Per-file contributions (edges, meta, symbol kinds) are cached by file mtime.
   * On a warm cache with 1 file changed, only that file is reprocessed — all others are instant.
   */
  def load(semanticdbRoots: Seq[Path], sourceRoot: Option[Path] = None): LoadedGraph = {
    endLineParsed = 0
    val t0    = System.currentTimeMillis()
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

    val projectRoot: Option[Path] = sourceRoot.orElse {
      semanticdbRoots.headOption
        .map(_.getParent.getParent.getParent.getParent)
        .filter(Files.isDirectory(_))
    }

    // One stat() per file for contribCache invalidation.
    // Note: loadDocs and parseEndLines do their own independent stats for their caches.
    val fileMtimes: Seq[(Path, Long)] =
      files.map(p => p -> Files.getLastModifiedTime(p).toMillis)

    // Step 1: build globalKindOf from cached contrib.kinds (O(1) per unchanged file)
    // or from freshly loaded docs (only for changed/new files).
    val globalKindOf = collection.mutable.Map.empty[String, SymbolInformation.Kind]
    fileMtimes.foreach { case (path, mtime) =>
      val cached = contribCache.get(path.toString)
      if (cached != null && cached._1 == mtime) {
        globalKindOf ++= cached._2.kinds
      } else {
        try {
          val docs = loadDocs(path)
          docs.documents.foreach { doc =>
            doc.symbols.foreach { s =>
              if (s.kind != SymbolInformation.Kind.UNKNOWN_KIND) globalKindOf(s.symbol) = s.kind
            }
          }
        } catch { case _: Exception => }
      }
    }

    // Step 2: collect per-file contributions — from cache or freshly computed.
    var recomputed = 0
    val allContribs: Seq[FileContrib] = fileMtimes.flatMap { case (path, mtime) =>
      val key    = path.toString
      val cached = contribCache.get(key)
      if (cached != null && cached._1 == mtime) {
        Some(cached._2)
      } else {
        recomputed += 1
        try {
          val contrib = processFile(path, projectRoot, globalKindOf)
          contribCache.put(key, (mtime, contrib))
          Some(contrib)
        } catch {
          case e: Exception =>
            System.err.println(s"[graph-explorer] WARNING: failed to parse $path: ${e.getMessage}")
            None
        }
      }
    }

    // Step 3: merge all contributions.
    val metaB = collection.mutable.Map.empty[String, NodeMeta]
    val outB  = collection.mutable.Map.empty[String, collection.mutable.Set[String]]
    val inB   = collection.mutable.Map.empty[String, collection.mutable.Set[String]]
    allContribs.foreach { c =>
      metaB ++= c.meta
      c.edges.foreach { case (caller, callee) =>
        outB.getOrElseUpdate(caller, collection.mutable.Set.empty) += callee
        inB.getOrElseUpdate(callee,  collection.mutable.Set.empty) += caller
      }
    }

    if (outB.isEmpty)
      System.err.println(
        s"[graph-explorer] WARNING: graph is empty after loading ${files.size} files."
      )

    val elapsed = System.currentTimeMillis() - t0
    System.err.println(
      f"[graph-loader] ${files.size} files in $elapsed ms" +
      f"  (contribs: $recomputed recomputed, ${files.size - recomputed} cached)" +
      f"  (endLines: $endLineParsed parsed)"
    )

    LoadedGraph(
      out  = outB.map { case (k, v) => k -> v.toSet }.toMap,
      in   = inB.map  { case (k, v) => k -> v.toSet }.toMap,
      meta = metaB.toMap,
    )
  }

  // ---------------------------------------------------------------------------
  // Per-file contribution cache
  // ---------------------------------------------------------------------------

  /** All graph data derived from a single .semanticdb file. */
  private case class FileContrib(
    meta:  Map[String, NodeMeta],
    edges: Seq[(String, String)],                     // (caller, callee) pairs
    kinds: Map[String, SymbolInformation.Kind],       // symbols defined in this file
  )

  private val contribCache =
    new java.util.concurrent.ConcurrentHashMap[String, (Long, FileContrib)]()

  private def processFile(
      path:        Path,
      projectRoot: Option[Path],
      kindOf:      collection.Map[String, SymbolInformation.Kind],
  ): FileContrib = {
    val docs  = loadDocs(path)
    val meta  = collection.mutable.Map.empty[String, NodeMeta]
    val edges = collection.mutable.Set.empty[(String, String)]
    val kinds = collection.mutable.Map.empty[String, SymbolInformation.Kind]

    docs.documents.foreach { doc =>
      doc.symbols.foreach { s =>
        if (s.kind != SymbolInformation.Kind.UNKNOWN_KIND) kinds(s.symbol) = s.kind
      }

      val displayNameOf: Map[String, String] =
        doc.symbols.map(s => s.symbol -> s.displayName).toMap

      val endLineOf: Map[Int, Int] = projectRoot.flatMap { root =>
        val sourceFile = root.resolve(doc.uri)
        if (Files.exists(sourceFile)) Some(parseEndLines(sourceFile)) else None
      }.getOrElse(Map.empty)

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
        if (!meta.contains(sym))
          meta(sym) = NodeMeta(
            file        = doc.uri,
            startLine   = line,
            endLine     = endLineOf.getOrElse(line, line),
            displayName = displayNameOf.getOrElse(sym, sym) + overloadSuffix(sym),
          )
      }

      if (methodDefs.nonEmpty) {
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
            if (caller != callee) edges += (caller -> callee)
          }
        }

        // Override resolution (CHA)
        doc.symbols.foreach { sym =>
          if (sym.kind == SymbolInformation.Kind.METHOD && sym.overriddenSymbols.nonEmpty) {
            sym.overriddenSymbols.foreach { traitSym =>
              if (!isSynthetic(traitSym) && traitSym != sym.symbol)
                edges += (traitSym -> sym.symbol)
            }
          }
        }

        // Synthetic call sites (for-comprehension, implicit conversions, etc.)
        doc.synthetics.foreach { synthetic =>
          synthetic.range.foreach { range =>
            callerAt(range.startLine).foreach { caller =>
              extractSyntheticSymbols(synthetic.tree).foreach { callee =>
                if (caller != callee && !isSynthetic(callee))
                  edges += (caller -> callee)
              }
            }
          }
        }
      }
    }

    FileContrib(meta.toMap, edges.toSeq, kinds.toMap)
  }

  // ---------------------------------------------------------------------------
  // TextDocuments cache (protobuf)
  // ---------------------------------------------------------------------------

  private val docsCache =
    new java.util.concurrent.ConcurrentHashMap[String, (Long, TextDocuments)]()

  private def loadDocs(path: Path): TextDocuments = {
    val mtime = Files.getLastModifiedTime(path).toMillis
    val key   = path.toString
    val entry = docsCache.get(key)
    if (entry != null && entry._1 == mtime) return entry._2
    val docs = TextDocuments.parseFrom(Files.readAllBytes(path))
    docsCache.put(key, (mtime, docs))
    docs
  }

  // ---------------------------------------------------------------------------
  // endLine cache (scalameta)
  // ---------------------------------------------------------------------------

  private val endLineCache =
    new java.util.concurrent.ConcurrentHashMap[String, (Long, Map[Int, Int])]()

  private var endLineParsed = 0

  private def parseEndLines(sourceFile: Path): Map[Int, Int] = {
    if (!Files.exists(sourceFile)) return Map.empty
    val mtime = Files.getLastModifiedTime(sourceFile).toMillis
    val key   = sourceFile.toString
    val entry = endLineCache.get(key)
    if (entry != null && entry._1 == mtime) return entry._2
    endLineParsed += 1
    val result = parseEndLinesUncached(sourceFile)
    endLineCache.put(key, (mtime, result))
    result
  }

  private def parseEndLinesUncached(sourceFile: Path): Map[Int, Int] = {
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

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def isSynthetic(symbol: String): Boolean =
    symbol.isEmpty ||
      (symbol.length > 5 && symbol.startsWith("local") && symbol.drop(5).forall(Character.isDigit))

  private def extractSyntheticSymbols(tree: scala.meta.internal.semanticdb.Tree): List[String] = {
    import scala.meta.internal.semanticdb.{
      ApplyTree, FunctionTree, IdTree, MacroExpansionTree,
      OriginalTree, SelectTree, TypeApplyTree,
    }
    tree match {
      case t: ApplyTree =>
        extractSyntheticSymbols(t.function) ++
          t.arguments.flatMap(a => extractSyntheticSymbols(a))
      case t: TypeApplyTree =>
        extractSyntheticSymbols(t.function)
      case t: SelectTree =>
        val sym  = t.id.map(_.symbol).getOrElse("")
        val self = if (sym.nonEmpty) List(sym) else Nil
        self ++ extractSyntheticSymbols(t.qualifier)
      case t: FunctionTree =>
        extractSyntheticSymbols(t.body)
      case t: IdTree =>
        if (t.symbol.nonEmpty) List(t.symbol) else Nil
      case _: OriginalTree | _: MacroExpansionTree => Nil
      case _                                        => Nil
    }
  }

  private def overloadSuffix(symbol: String): String = {
    val open  = symbol.lastIndexOf('(')
    val close = symbol.indexOf(')', open + 1)
    if (open < 0 || close < 0) return ""
    val inner = symbol.substring(open + 1, close)
    if (inner.startsWith("+")) symbol.substring(open, close + 1) else ""
  }
}
