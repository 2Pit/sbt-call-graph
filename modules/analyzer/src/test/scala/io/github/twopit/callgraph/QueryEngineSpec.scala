package io.github.twopit.callgraph

import munit.FunSuite

class QueryEngineSpec extends FunSuite {

  /** Build a LoadedGraph from edges. Nodes without explicit meta get a default entry. */
  private def makeGraph(
      edges: Seq[(String, String)],
      meta: Map[String, NodeMeta] = Map.empty,
  ): LoadedGraph = {
    val out      = edges.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2).toSet }
    val in       = edges.groupBy(_._2).map { case (k, vs) => k -> vs.map(_._1).toSet }
    val allNodes = (edges.flatMap(e => Seq(e._1, e._2)) ++ meta.keys).distinct
    val fullMeta = allNodes.foldLeft(meta) { (m, id) =>
      if (m.contains(id)) m
      else m + (id -> NodeMeta(file = "Test.scala", startLine = 0, endLine = 0, displayName = id))
    }
    LoadedGraph(out, in, fullMeta)
  }

  // ---------------------------------------------------------------------------
  // pathsAmong
  // ---------------------------------------------------------------------------

  test("pathsAmong: finds a direct path between two vertices") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.pathsAmong(g, Seq("A", "C"))
    assertEquals(r.nodes.toSet, Set("A", "B", "C"))
    assertEquals(r.edges.toSet, Set("A" -> "B", "B" -> "C"))
    assertEquals(r.truncated, false)
  }

  test("pathsAmong: no path returns empty") {
    val g = makeGraph(Seq("A" -> "B"))
    val r = QueryEngine.pathsAmong(g, Seq("B", "A"))
    assert(r.nodes.isEmpty)
    assert(r.edges.isEmpty)
  }

  test("pathsAmong: three vertices along a chain") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.pathsAmong(g, Seq("A", "B", "C"))
    assertEquals(r.nodes.toSet, Set("A", "B", "C"))
    assert(r.edges.toSet.contains("A" -> "B"))
    assert(r.edges.toSet.contains("B" -> "C"))
  }

  test("pathsAmong: single vertex returns just that vertex") {
    val g = makeGraph(Seq("A" -> "B"))
    val r = QueryEngine.pathsAmong(g, Seq("A"))
    assertEquals(r.nodes, Seq("A"))
    assert(r.edges.isEmpty)
  }

  test("pathsAmong: no paths between disconnected vertices returns empty") {
    val g = makeGraph(Seq("A" -> "B", "C" -> "D"))
    val r = QueryEngine.pathsAmong(g, Seq("B", "C"))
    assert(r.nodes.isEmpty)
  }

  test("pathsAmong: unknown vertices are skipped") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.pathsAmong(g, Seq("A", "X", "C"))
    assertEquals(r.nodes.toSet, Set("A", "B", "C"))
  }

  test("pathsAmong: cycle does not cause infinite loop") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "A", "B" -> "C"))
    val r = QueryEngine.pathsAmong(g, Seq("A", "C"))
    assertEquals(r.nodes.toSet, Set("A", "B", "C"))
    assertEquals(r.edges.toSet, Set("A" -> "B", "B" -> "C"))
  }

  test("pathsAmong: truncated when maxPaths exceeded") {
    // diamond: A→B→D and A→C→D — two paths
    val g = makeGraph(Seq("A" -> "B", "A" -> "C", "B" -> "D", "C" -> "D"))
    val r = QueryEngine.pathsAmong(g, Seq("A", "D"), maxPaths = 1)
    assertEquals(r.nodes.size, 3) // A + one of {B,C} + D
    assertEquals(r.truncated, true)
  }

  test("pathsAmong: maxDepth prevents reaching distant target") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C", "C" -> "D", "D" -> "E"))
    val r = QueryEngine.pathsAmong(g, Seq("A", "E"), maxDepth = 2)
    assert(r.nodes.isEmpty)
  }

  // ---------------------------------------------------------------------------
  // viaVertex
  // ---------------------------------------------------------------------------

  test("viaVertex: direct callers and callees at depth 1") {
    val g = makeGraph(Seq("A" -> "B", "C" -> "B", "B" -> "D"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 1).get
    assertEquals(r.nodes.toSet, Set("A", "B", "C", "D"))
    assertEquals(r.edges.toSet, Set("A" -> "B", "C" -> "B", "B" -> "D"))
  }

  test("viaVertex: depthIn and depthOut are independent") {
    // chain A→B→C→D; queried vertex = B
    val g = makeGraph(Seq("A" -> "B", "B" -> "C", "C" -> "D"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 2).get
    assertEquals(r.nodes.toSet, Set("A", "B", "C", "D"))
  }

  test("viaVertex: depthOut=1 does not expose 2-hop callees") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C", "C" -> "D"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 1).get
    assert(!r.nodes.contains("D"))
  }

  test("viaVertex: depthOut=0 omits all callees") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 0).get
    assertEquals(r.nodes.toSet, Set("A", "B"))
    assertEquals(r.edges.toSet, Set("A" -> "B"))
  }

  test("viaVertex: unknown vertex returns None") {
    val g = makeGraph(Seq("A" -> "B"))
    assertEquals(QueryEngine.viaVertex(g, "X"), None)
  }

  test("viaVertex: in-traversal does not include out-only nodes") {
    // B→C: C should not appear when depthOut=0
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 2, depthOut = 0).get
    assertEquals(r.nodes.toSet, Set("A", "B"))
  }

  test("viaVertex: out-traversal does not include in-only nodes") {
    // A→B: A should not appear when depthIn=0
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 0, depthOut = 2).get
    assertEquals(r.nodes.toSet, Set("B", "C"))
  }

  test("viaVertex: mutual call — both directions present in result") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "A"))
    val r = QueryEngine.viaVertex(g, "A", depthIn = 1, depthOut = 1).get
    assertEquals(r.nodes.toSet, Set("A", "B"))
    assertEquals(r.edges.toSet, Set("A" -> "B", "B" -> "A"))
  }

  test("viaVertex: in-traversal follows in-edges only (not out-edges of intermediates)") {
    // Graph: X→A→B→C; vertex=B; depthIn=3, depthOut=0
    // in-traversal should reach A and X, not C
    val g = makeGraph(Seq("X" -> "A", "A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 3, depthOut = 0).get
    assertEquals(r.nodes.toSet, Set("A", "B", "X"))
  }

  test("viaVertex: out-traversal follows out-edges only (not in-edges of intermediates)") {
    // Graph: X→B→C, A→C; vertex=B; depthIn=0, depthOut=3
    // out-traversal should reach C; A (which calls C) should NOT appear
    val g = makeGraph(Seq("X" -> "B", "B" -> "C", "A" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 0, depthOut = 3).get
    assertEquals(r.nodes.toSet, Set("B", "C"))
  }

  // ---------------------------------------------------------------------------
  // search
  // ---------------------------------------------------------------------------

  test("search: finds by displayName substring") {
    val meta = Map(
      "pkg/Foo#bar()." -> NodeMeta("F.scala", 1, 5, "bar"),
      "pkg/Foo#baz()." -> NodeMeta("F.scala", 7, 9, "baz"),
      "pkg/Foo#qux()." -> NodeMeta("F.scala", 11, 13, "qux"),
    )
    val g = makeGraph(Seq.empty, meta)
    assertEquals(QueryEngine.search(g, "ba").toSet, Set("pkg/Foo#bar().", "pkg/Foo#baz()."))
  }

  test("search: finds by FQN substring") {
    val meta = Map("pkg/Foo#bar()." -> NodeMeta("F.scala", 1, 5, "bar"))
    val g    = makeGraph(Seq.empty, meta)
    assertEquals(QueryEngine.search(g, "Foo#bar"), Seq("pkg/Foo#bar()."))
  }

  test("search: case-sensitive — wrong case returns nothing") {
    val meta = Map("pkg/Foo#bar()." -> NodeMeta("F.scala", 1, 5, "bar"))
    val g    = makeGraph(Seq.empty, meta)
    assertEquals(QueryEngine.search(g, "Bar"), Seq.empty[String])
  }

  test("search: respects maxResults") {
    val meta = (1 to 10).map(i => s"pkg/A#m$i()." -> NodeMeta("A.scala", i, i, s"m$i")).toMap
    val g    = makeGraph(Seq.empty, meta)
    assertEquals(QueryEngine.search(g, "m", maxResults = 3).size, 3)
  }

  // ---------------------------------------------------------------------------
  // moduleEdges
  // ---------------------------------------------------------------------------

  test("moduleEdges: outgoing — call from inside to outside") {
    val meta = Map(
      "mod/A#foo()."   -> NodeMeta("mod/A.scala", 1, 5, "foo"),
      "other/B#bar()." -> NodeMeta("other/B.scala", 1, 5, "bar"),
    )
    val g = makeGraph(Seq("mod/A#foo()." -> "other/B#bar()."), meta)
    val r = ModuleQuery.moduleEdges(g, "mod/")
    assertEquals(r.outgoing.map(e => (e.srcId, e.tgtId)), Seq("mod/A#foo()." -> "other/B#bar()."))
    assertEquals(r.incoming, Seq.empty[ModuleEdge])
  }

  test("moduleEdges: incoming — call from outside to inside") {
    val meta = Map(
      "mod/A#foo()."   -> NodeMeta("mod/A.scala", 1, 5, "foo"),
      "other/B#bar()." -> NodeMeta("other/B.scala", 1, 5, "bar"),
    )
    val g = makeGraph(Seq("other/B#bar()." -> "mod/A#foo()."), meta)
    val r = ModuleQuery.moduleEdges(g, "mod/")
    assertEquals(r.incoming.map(e => (e.srcId, e.tgtId)), Seq("other/B#bar()." -> "mod/A#foo()."))
    assertEquals(r.outgoing, Seq.empty[ModuleEdge])
  }

  test("moduleEdges: internal edge not reported") {
    val meta = Map(
      "mod/A#foo()." -> NodeMeta("mod/A.scala", 1, 5, "foo"),
      "mod/A#bar()." -> NodeMeta("mod/A.scala", 7, 10, "bar"),
    )
    val g = makeGraph(Seq("mod/A#foo()." -> "mod/A#bar()."), meta)
    val r = ModuleQuery.moduleEdges(g, "mod/")
    assertEquals(r.outgoing, Seq.empty[ModuleEdge])
    assertEquals(r.incoming, Seq.empty[ModuleEdge])
  }

  test("moduleEdges: callee not in meta is excluded") {
    // Build graph manually so the library symbol has no meta entry
    val fooId = "mod/A#foo()."
    val libId = "scala/collection/List#map()."
    val g = LoadedGraph(
      out = Map(fooId -> Set(libId)),
      in = Map(libId -> Set(fooId)),
      meta = Map(fooId -> NodeMeta("mod/A.scala", 1, 5, "foo")),
    )
    val r = ModuleQuery.moduleEdges(g, "mod/")
    assertEquals(r.outgoing, Seq.empty[ModuleEdge])
  }
}
