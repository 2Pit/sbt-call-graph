package io.github.twopit.graphexplorer

import munit.FunSuite
import scala.meta.internal.semanticdb._
import java.nio.file.Files

/** Tests that GraphLoader captures call edges from doc.synthetics.
  * These edges arise from for-comprehension desugaring (flatMap/map/withFilter)
  * and implicit conversions — they are NOT present in doc.occurrences.
  *
  * Each test builds a minimal TextDocument programmatically, writes it as a
  * .semanticdb file, and loads it through GraphLoader.load().
  */
class GraphLoaderSyntheticsSpec extends FunSuite {

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private def defn(symbol: String, line: Int, displayName: String = "m"): SymbolOccurrence =
    SymbolOccurrence(
      range = Some(Range(line, 0, line, displayName.length)),
      symbol = symbol,
      role = SymbolOccurrence.Role.DEFINITION,
    )

  private def ref(symbol: String, line: Int): SymbolOccurrence =
    SymbolOccurrence(
      range = Some(Range(line, 10, line, 20)),
      symbol = symbol,
      role = SymbolOccurrence.Role.REFERENCE,
    )

  private def methodInfo(symbol: String, name: String): SymbolInformation =
    SymbolInformation(symbol = symbol, kind = SymbolInformation.Kind.METHOD, displayName = name)

  private def loadDoc(doc: TextDocument): LoadedGraph = {
    val dir  = Files.createTempDirectory("semanticdb-synth-test")
    val file = dir.resolve("Test.semanticdb")
    try {
      Files.write(file, TextDocuments(Seq(doc)).toByteArray)
      GraphLoader.load(Seq(dir))
    } finally {
      Files.delete(file)
      Files.delete(dir)
    }
  }

  private val callerSym  = "test/Foo.caller()."
  private val targetSym  = "test/Foo.target()."
  private val flatMapSym = "scala/Option#flatMap()."
  private val mapSym     = "scala/Option#map()."

  private def baseSymbols = Seq(
    methodInfo(callerSym, "caller"),
    methodInfo(targetSym, "target"),
    methodInfo(flatMapSym, "flatMap"),
    methodInfo(mapSym, "map"),
  )

  // ---------------------------------------------------------------------------
  // SelectTree: the most common synthetic shape for for-comprehension
  //
  //   for { x <- foo() } yield bar(x)
  //   desugars to foo().flatMap { x => bar(x) }
  //   the .flatMap() call is a SelectTree in doc.synthetics
  // ---------------------------------------------------------------------------

  test("synthetic SelectTree adds edge from caller to selected method") {
    val synthetic = Synthetic(
      range = Some(Range(1, 0, 1, 30)),
      tree = SelectTree(
        qualifier = OriginalTree(range = Some(Range(1, 0, 1, 5))),
        id = Some(IdTree(symbol = flatMapSym)),
      ),
    )
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Foo.scala",
      symbols = baseSymbols,
      occurrences = Seq(defn(callerSym, line = 0), defn(targetSym, line = 5)),
      synthetics = Seq(synthetic),
    )
    val graph = loadDoc(doc)
    assert(
      graph.out.getOrElse(callerSym, Set.empty).contains(flatMapSym),
      s"expected edge $callerSym → $flatMapSym from synthetic SelectTree",
    )
  }

  // ---------------------------------------------------------------------------
  // ApplyTree wrapping a SelectTree: the full desugared for-comprehension shape
  //
  //   foo().flatMap(x => bar(x))
  //   ApplyTree(SelectTree(OriginalTree, IdTree(flatMap)), [FunctionTree(bar)])
  // ---------------------------------------------------------------------------

  test("synthetic ApplyTree(SelectTree) adds edge from caller to selected method") {
    val synthetic = Synthetic(
      range = Some(Range(1, 0, 1, 40)),
      tree = ApplyTree(
        function = SelectTree(
          qualifier = OriginalTree(range = Some(Range(1, 0, 1, 5))),
          id = Some(IdTree(symbol = flatMapSym)),
        ),
        arguments = Seq(
          FunctionTree(
            parameters = Seq(IdTree("local0")),
            body = IdTree(symbol = targetSym),
          )
        ),
      ),
    )
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Foo.scala",
      symbols = baseSymbols,
      occurrences = Seq(defn(callerSym, line = 0), defn(targetSym, line = 5)),
      synthetics = Seq(synthetic),
    )
    val graph = loadDoc(doc)
    assert(
      graph.out.getOrElse(callerSym, Set.empty).contains(flatMapSym),
      s"expected edge $callerSym → $flatMapSym",
    )
    assert(
      graph.out.getOrElse(callerSym, Set.empty).contains(targetSym),
      s"expected edge $callerSym → $targetSym (from FunctionTree body)",
    )
  }

  // ---------------------------------------------------------------------------
  // FunctionTree body is traversed transparently
  //
  // The lambda itself should NOT appear as a node; the call inside it should be
  // attributed to the enclosing named method (callerSym).
  // ---------------------------------------------------------------------------

  test("FunctionTree body: callee attributed to enclosing method, no lambda node") {
    val synthetic = Synthetic(
      range = Some(Range(1, 0, 1, 30)),
      tree = FunctionTree(
        parameters = Seq(IdTree("local0")),
        body = IdTree(symbol = targetSym),
      ),
    )
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Foo.scala",
      symbols = baseSymbols,
      occurrences = Seq(defn(callerSym, line = 0), defn(targetSym, line = 5)),
      synthetics = Seq(synthetic),
    )
    val graph = loadDoc(doc)
    assert(
      graph.out.getOrElse(callerSym, Set.empty).contains(targetSym),
      s"expected edge $callerSym → $targetSym from lambda body",
    )
    // "local0" is a synthetic symbol — must not appear as a graph node
    assert(!graph.meta.contains("local0"), "lambda param 'local0' must not be a graph node")
  }

  // ---------------------------------------------------------------------------
  // OriginalTree is skipped (already in doc.occurrences)
  // ---------------------------------------------------------------------------

  test("OriginalTree does not produce duplicate edges") {
    // target is referenced both in occurrences and wrapped in OriginalTree in a synthetic
    val synthetic = Synthetic(
      range = Some(Range(1, 0, 1, 20)),
      tree = OriginalTree(range = Some(Range(1, 10, 1, 16))),
    )
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Foo.scala",
      symbols = baseSymbols,
      occurrences = Seq(
        defn(callerSym, line = 0),
        defn(targetSym, line = 5),
        ref(targetSym, line = 1), // already in occurrences
      ),
      synthetics = Seq(synthetic),
    )
    val graph  = loadDoc(doc)
    val outSet = graph.out.getOrElse(callerSym, Set.empty)
    assert(outSet.contains(targetSym), "edge from occurrences must still be present")
    // size check: only one edge caller→target (no duplicate from OriginalTree)
    assertEquals(outSet.count(_ == targetSym), 1)
  }

  // ---------------------------------------------------------------------------
  // Override resolution (CHA): traitMethod → implMethod edge
  //
  //   trait Repo { def find(): Option[Int] }
  //   class RepoLive extends Repo { override def find(): Option[Int] = ... }
  //
  //   caller calls Repo#find() (the trait method).
  //   After CHA, graphVia(RepoLive#find) at depthIn=2 should reach caller.
  // ---------------------------------------------------------------------------

  test("overriddenSymbols: adds virtual dispatch edge traitMethod → implMethod") {
    val traitSym   = "test/Repo#find()."
    val implSym    = "test/RepoLive#find()."
    val callerSym2 = "test/Service#run()."

    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/RepoLive.scala",
      symbols = Seq(
        methodInfo(traitSym, "find"),
        SymbolInformation(
          symbol = implSym,
          kind = SymbolInformation.Kind.METHOD,
          displayName = "find",
          overriddenSymbols = Seq(traitSym),
        ),
        methodInfo(callerSym2, "run"),
      ),
      occurrences = Seq(
        defn(callerSym2, line = 0),
        defn(implSym, line = 5),
        // caller calls the TRAIT method (as static analysis sees it)
        ref(traitSym, line = 1),
      ),
    )

    val graph = loadDoc(doc)

    // virtual dispatch edge: traitMethod → implMethod
    assert(
      graph.out.getOrElse(traitSym, Set.empty).contains(implSym),
      s"expected virtual dispatch edge $traitSym → $implSym",
    )
    // caller → traitMethod (from occurrences)
    assert(
      graph.out.getOrElse(callerSym2, Set.empty).contains(traitSym),
      s"expected edge $callerSym2 → $traitSym",
    )
    // transitively: implMethod is reachable from caller via traitMethod
    assert(
      graph.in.getOrElse(implSym, Set.empty).contains(traitSym),
      s"expected $traitSym in in($implSym)",
    )
  }

  test("overriddenSymbols: multiple implementations all get dispatch edge from trait") {
    val traitSym = "test/Repo#find()."
    val implA    = "test/RepoA#find()."
    val implB    = "test/RepoB#find()."

    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Impls.scala",
      symbols = Seq(
        methodInfo(traitSym, "find"),
        SymbolInformation(
          symbol = implA,
          kind = SymbolInformation.Kind.METHOD,
          displayName = "find",
          overriddenSymbols = Seq(traitSym),
        ),
        SymbolInformation(
          symbol = implB,
          kind = SymbolInformation.Kind.METHOD,
          displayName = "find",
          overriddenSymbols = Seq(traitSym),
        ),
      ),
      occurrences = Seq(defn(implA, line = 0), defn(implB, line = 5)),
    )
    val graph       = loadDoc(doc)
    val dispatchees = graph.out.getOrElse(traitSym, Set.empty)
    assert(dispatchees.contains(implA), s"expected dispatch edge to $implA")
    assert(dispatchees.contains(implB), s"expected dispatch edge to $implB")
  }

  test("overriddenSymbols: multi-level override chain A → B → C all connected") {
    // trait A ← abstract B ← concrete C
    val traitA    = "test/A#m()."
    val abstractB = "test/B#m()."
    val concreteC = "test/C#m()."

    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Chain.scala",
      symbols = Seq(
        methodInfo(traitA, "m"),
        SymbolInformation(
          symbol = abstractB,
          kind = SymbolInformation.Kind.METHOD,
          displayName = "m",
          overriddenSymbols = Seq(traitA),
        ),
        SymbolInformation(
          symbol = concreteC,
          kind = SymbolInformation.Kind.METHOD,
          displayName = "m",
          overriddenSymbols = Seq(abstractB),
        ),
      ),
      occurrences = Seq(defn(abstractB, line = 0), defn(concreteC, line = 5)),
    )
    val graph = loadDoc(doc)
    assert(graph.out.getOrElse(traitA, Set.empty).contains(abstractB), s"$traitA → $abstractB")
    assert(graph.out.getOrElse(abstractB, Set.empty).contains(concreteC), s"$abstractB → $concreteC")
  }

  test("overriddenSymbols: no self-loop when symbol overrides itself (guard)") {
    val sym = "test/Foo#m()."
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Foo.scala",
      symbols = Seq(
        SymbolInformation(
          symbol = sym,
          kind = SymbolInformation.Kind.METHOD,
          displayName = "m",
          overriddenSymbols = Seq(sym),
        )
      ),
      occurrences = Seq(defn(sym, line = 0)),
    )
    val graph = loadDoc(doc)
    assert(!graph.out.getOrElse(sym, Set.empty).contains(sym), "self-loop must not be added")
  }

  test("overriddenSymbols: external library trait symbol appears as edge target without meta") {
    val externalTrait = "scala/collection/IterableOnce#foreach()."
    val implSym2      = "test/MyCol#foreach()."
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/MyCol.scala",
      symbols = Seq(
        SymbolInformation(
          symbol = implSym2,
          kind = SymbolInformation.Kind.METHOD,
          displayName = "foreach",
          overriddenSymbols = Seq(externalTrait),
        )
      ),
      occurrences = Seq(defn(implSym2, line = 0)),
    )
    val graph = loadDoc(doc)
    // edge from external trait to our impl must exist
    assert(graph.out.getOrElse(externalTrait, Set.empty).contains(implSym2))
    // external trait has no meta (not in our sources)
    assert(!graph.meta.contains(externalTrait))
  }

  // ---------------------------------------------------------------------------
  // chained synthetics: two synthetics on consecutive lines both attributed
  // to the same enclosing caller
  // ---------------------------------------------------------------------------

  test("multiple synthetics on different lines in the same method all attributed to caller") {
    val synth1 = Synthetic(
      range = Some(Range(1, 0, 1, 20)),
      tree = IdTree(symbol = flatMapSym),
    )
    val synth2 = Synthetic(
      range = Some(Range(2, 0, 2, 20)),
      tree = IdTree(symbol = mapSym),
    )
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "test/Foo.scala",
      symbols = baseSymbols,
      occurrences = Seq(defn(callerSym, line = 0), defn(targetSym, line = 5)),
      synthetics = Seq(synth1, synth2),
    )
    val graph  = loadDoc(doc)
    val outSet = graph.out.getOrElse(callerSym, Set.empty)
    assert(outSet.contains(flatMapSym), s"expected $flatMapSym")
    assert(outSet.contains(mapSym), s"expected $mapSym")
  }
}
