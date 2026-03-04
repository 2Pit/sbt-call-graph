package me.peter.graphexplorer

import scala.meta._
import scala.meta.internal.semanticdb._
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

object GraphLoader {

  /** Load a call graph from all .semanticdb files found under `semanticdbRoot`. */
  def load(semanticdbRoot: Path): LoadedGraph = {
    val files = Files
      .walk(semanticdbRoot)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".semanticdb"))
      .toList

    if (files.isEmpty) {
      System.err.println(s"[graph-explorer] WARNING: no .semanticdb files found under $semanticdbRoot")
      System.err.println(s"[graph-explorer] Run 'compile' first.")
      return LoadedGraph.empty
    }

    // Derive project root: semanticdbRoot = <module>/target/scala-X.YY/meta
    val projectRoot: Option[Path] = {
      val candidate = semanticdbRoot.getParent.getParent.getParent.getParent
      Some(candidate).filter(Files.isDirectory(_))
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
      out = outB.view.mapValues(_.toSet).toMap,
      in = inB.view.mapValues(_.toSet).toMap,
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

      // Parse source file to get method end lines: startLine -> endLine (0-based)
      val endLineOf: Map[Int, Int] = projectRoot.flatMap { root =>
        val sourceFile = root.resolve(doc.uri)
        if (Files.exists(sourceFile)) Some(parseEndLines(sourceFile))
        else None
      }.getOrElse(Map.empty)

      val methodDefs: Vector[(String, Int)] =
        doc.occurrences.filter { occ =>
          occ.role == SymbolOccurrence.Role.DEFINITION &&
          occ.range.isDefined &&
          !isSynthetic(occ.symbol) &&
          kindOf.get(occ.symbol).contains(SymbolInformation.Kind.METHOD)
        }
          .map(occ => occ.symbol -> occ.range.get.startLine)
          .toVector
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

      def callerAt(line: Int): Option[String] =
        methodDefs.takeWhile(_._2 <= line).lastOption.map(_._1)

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

  /** Parse a Scala source file and return a map of methodStartLine -> methodEndLine (0-based). */
  private def parseEndLines(sourceFile: Path): Map[Int, Int] = {
    implicit val dialect: Dialect = dialects.Scala213
    val input                     = Input.File(sourceFile.toFile)
    val parsed                    = input.parse[Source]
    parsed.toOption match {
      case None       => Map.empty
      case Some(tree) =>
        tree.collect {
          case d: Defn.Def   => d.name.pos.startLine -> d.pos.endLine
          case d: Defn.Macro => d.name.pos.startLine -> d.pos.endLine
        }.toMap
    }
  }

  private def isSynthetic(symbol: String): Boolean =
    symbol.startsWith("local") || symbol.isEmpty
}
