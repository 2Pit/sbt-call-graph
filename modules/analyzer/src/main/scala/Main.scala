package me.peter.graphexplorer

import scala.meta.internal.semanticdb._
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

object Main extends App {
  if (args.isEmpty) {
    println("Usage: run <path-to-semanticdb-dir>")
    sys.exit(1)
  }

  val dir = Paths.get(args(0))

  val semanticdbFiles: List[Path] =
    Files.walk(dir).iterator().asScala
      .filter(_.toString.endsWith(".semanticdb"))
      .toList

  println(s"Found ${semanticdbFiles.size} .semanticdb files under $dir\n")

  // caller -> Set[callee]
  val edges = collection.mutable.Map.empty[String, collection.mutable.Set[String]]

  var totalEdges = 0

  semanticdbFiles.foreach { path =>
    val bytes = Files.readAllBytes(path)
    val docs  = TextDocuments.parseFrom(bytes)

    docs.documents.foreach { doc =>
      // Индекс: символ → kind (чтобы знать что METHOD, а что поле/тип)
      val kindOf: Map[String, SymbolInformation.Kind] =
        doc.symbols.map(s => s.symbol -> s.kind).toMap

      // Метод-дефиниции этого файла: symbol → startLine
      // Role.DEFINITION + kind == METHOD
      val methodDefs: List[(String, Int)] =
        doc.occurrences
          .filter { occ =>
            occ.role == SymbolOccurrence.Role.DEFINITION &&
            occ.range.isDefined &&
            kindOf.get(occ.symbol).contains(SymbolInformation.Kind.METHOD)
          }
          .map(occ => occ.symbol -> occ.range.get.startLine)
          .toList
          .sortBy(_._2)

      if (methodDefs.nonEmpty) {
        // Для строки L — caller — это метод с наибольшим startLine <= L
        def callerAt(line: Int): Option[String] =
          methodDefs.takeWhile(_._2 <= line).lastOption.map(_._1)

        // Все REFERENCE occurrences к METHOD-символам
        doc.occurrences
          .filter { occ =>
            occ.role == SymbolOccurrence.Role.REFERENCE &&
            occ.range.isDefined &&
            kindOf.get(occ.symbol).contains(SymbolInformation.Kind.METHOD)
          }
          .foreach { occ =>
            val callee = occ.symbol
            callerAt(occ.range.get.startLine).foreach { caller =>
              if (caller != callee) { // убираем самовызовы
                edges.getOrElseUpdate(caller, collection.mutable.Set.empty) += callee
                totalEdges += 1
              }
            }
          }
      }
    }
  }

  println(s"=== Call graph stats ===")
  println(s"Unique callers : ${edges.size}")
  println(s"Total edges    : $totalEdges")
  println()

  // Top 10 по out-degree
  println("=== Top 10 methods by out-degree (most calls made) ===")
  edges.toList.sortBy(-_._2.size).take(10).foreach { case (caller, callees) =>
    println(s"  [${callees.size}] $caller")
    callees.take(3).foreach(c => println(s"        → $c"))
  }
  println()

  // Top 10 по in-degree
  val inDegree = collection.mutable.Map.empty[String, Int]
  edges.foreach { case (_, callees) =>
    callees.foreach(c => inDegree(c) = inDegree.getOrElse(c, 0) + 1)
  }
  println("=== Top 10 methods by in-degree (most called) ===")
  inDegree.toList.sortBy(-_._2).take(10).foreach { case (callee, count) =>
    println(s"  [$count] $callee")
  }
  println()

  // Пример: первые 15 рёбер из первого файла с методами
  println("=== Sample edges (first 15) ===")
  edges.take(15).foreach { case (caller, callees) =>
    callees.foreach { callee =>
      println(s"  $caller")
      println(s"    → $callee")
    }
  }
}
