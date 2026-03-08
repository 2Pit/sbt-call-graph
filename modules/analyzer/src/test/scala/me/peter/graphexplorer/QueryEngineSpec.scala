package me.peter.graphexplorer

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
  // pathAtoB
  // ---------------------------------------------------------------------------

  test("pathAtoB: finds a direct path") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.pathAtoB(g, "A", "C")
    assertEquals(r.paths, Seq(Seq("A", "B", "C")))
    assertEquals(r.truncated, false)
  }

  test("pathAtoB: no path returns empty") {
    val g = makeGraph(Seq("A" -> "B"))
    val r = QueryEngine.pathAtoB(g, "B", "A")
    assertEquals(r.paths, Seq.empty[Seq[String]])
    assertEquals(r.truncated, false)
  }

  test("pathAtoB: from == to returns single-node path") {
    val g = makeGraph(Seq("A" -> "B"))
    val r = QueryEngine.pathAtoB(g, "A", "A")
    assertEquals(r.paths, Seq(Seq("A")))
  }

  test("pathAtoB: cycle does not cause infinite loop") {
    // A→B→A (cycle) and B→C
    val g = makeGraph(Seq("A" -> "B", "B" -> "A", "B" -> "C"))
    val r = QueryEngine.pathAtoB(g, "A", "C")
    assertEquals(r.paths, Seq(Seq("A", "B", "C")))
  }

  test("pathAtoB: finds multiple paths, truncated when maxPaths exceeded") {
    // diamond: A→B→D and A→C→D — two paths
    val g = makeGraph(Seq("A" -> "B", "A" -> "C", "B" -> "D", "C" -> "D"))
    val r = QueryEngine.pathAtoB(g, "A", "D", maxPaths = 1)
    assertEquals(r.paths.size, 1)
    assertEquals(r.truncated, true)
  }

  test("pathAtoB: maxDepth prevents reaching distant target") {
    // A→B→C→D→E; maxDepth=2 stops expansion at C, so E is never reached
    val g = makeGraph(Seq("A" -> "B", "B" -> "C", "C" -> "D", "D" -> "E"))
    val r = QueryEngine.pathAtoB(g, "A", "E", maxDepth = 2)
    assertEquals(r.paths, Seq.empty[Seq[String]])
  }

  test("pathAtoB: unknown from-vertex returns empty") {
    val g = makeGraph(Seq("A" -> "B"))
    assertEquals(QueryEngine.pathAtoB(g, "X", "B").paths, Seq.empty[Seq[String]])
  }

  // ---------------------------------------------------------------------------
  // viaVertex
  // ---------------------------------------------------------------------------

  test("viaVertex: direct callers and callees at depth 1") {
    val g = makeGraph(Seq("A" -> "B", "C" -> "B", "B" -> "D"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 1).get
    assertEquals(r.in.map(_.id).toSet, Set("A", "C"))
    assertEquals(r.out.map(_.id).toSet, Set("D"))
  }

  test("viaVertex: depthIn and depthOut are independent") {
    // chain A→B→C→D; queried vertex = B
    val g = makeGraph(Seq("A" -> "B", "B" -> "C", "C" -> "D"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 2).get
    assertEquals(r.in.map(_.id).toSet, Set("A"))
    assertEquals(r.out.map(_.id).toSet, Set("C", "D"))
  }

  test("viaVertex: depth values on returned nodes are correct") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C", "C" -> "D"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 2, depthOut = 2).get
    assertEquals(r.out.find(_.id == "C").map(_.depth), Some(1))
    assertEquals(r.out.find(_.id == "D").map(_.depth), Some(2))
  }

  test("viaVertex: depthOut=1 does not expose 2-hop callees") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C", "C" -> "D"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 1).get
    assert(!r.out.map(_.id).contains("D"))
  }

  test("viaVertex: unknown vertex returns None") {
    val g = makeGraph(Seq("A" -> "B"))
    assertEquals(QueryEngine.viaVertex(g, "X"), None)
  }

  // ---------------------------------------------------------------------------
  // viaVertex: strict directionality — BFS never mixes in/out edges
  // ---------------------------------------------------------------------------

  test("viaVertex: out-only reachable node does not appear in in-set") {
    // B→C: C is a callee (out); it must NOT appear as a caller (in)
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 2, depthOut = 2).get
    assert(!r.in.map(_.id).contains("C"), "C is a callee, must not appear in callers")
  }

  test("viaVertex: in-only reachable node does not appear in out-set") {
    // A→B: A is a caller (in); it must NOT appear as a callee (out)
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 2, depthOut = 2).get
    assert(!r.out.map(_.id).contains("A"), "A is a caller, must not appear in callees")
  }

  test("viaVertex: depthOut=0 returns empty out-set") {
    val g = makeGraph(Seq("A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 0).get
    assertEquals(r.out, Seq.empty[QueryEngine.DepthNode])
  }

  test("viaVertex: in-traversal does not follow out-edges from intermediate nodes") {
    // Graph: X→A→B→C; queried vertex = B
    // in-traversal should reach A (depth 1) and X (depth 2)
    // C is only reachable via B's out-edge — must NOT appear in in-set
    val g = makeGraph(Seq("X" -> "A", "A" -> "B", "B" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 3, depthOut = 0).get
    assertEquals(r.in.map(_.id).toSet, Set("A", "X"))
    assert(!r.in.map(_.id).contains("C"), "C is only reachable via out-edge, must not appear in in-set")
    assertEquals(r.out, Seq.empty[QueryEngine.DepthNode], "depthOut=0 must produce empty out-set")
  }

  test("viaVertex: out-traversal does not follow in-edges from intermediate nodes") {
    // Graph: X→B→C, A→C; queried vertex = B
    // out-traversal should reach C (depth 1)
    // A is only reachable by following C's in-edge — must NOT appear in out-set
    val g = makeGraph(Seq("X" -> "B", "B" -> "C", "A" -> "C"))
    val r = QueryEngine.viaVertex(g, "B", depthIn = 1, depthOut = 3).get
    assertEquals(r.out.map(_.id).toSet, Set("C"))
    assert(!r.out.map(_.id).contains("A"), "A reaches C via in-edge, must not appear in out-set")
  }

  test("viaVertex: mutual call — node in both in and out sets independently") {
    // A↔B (A→B and B→A); queried vertex = A
    val g = makeGraph(Seq("A" -> "B", "B" -> "A"))
    val r = QueryEngine.viaVertex(g, "A", depthIn = 1, depthOut = 1).get
    assert(r.in.map(_.id).contains("B"),  "B calls A, so it should appear in in-set")
    assert(r.out.map(_.id).contains("B"), "A calls B, so it should appear in out-set")
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
    val r = QueryEngine.moduleEdges(g, "mod/")
    assertEquals(r.outgoing.map(e => (e.srcId, e.tgtId)), Seq("mod/A#foo()." -> "other/B#bar()."))
    assertEquals(r.incoming, Seq.empty[QueryEngine.ModuleEdge])
  }

  test("moduleEdges: incoming — call from outside to inside") {
    val meta = Map(
      "mod/A#foo()."   -> NodeMeta("mod/A.scala", 1, 5, "foo"),
      "other/B#bar()." -> NodeMeta("other/B.scala", 1, 5, "bar"),
    )
    val g = makeGraph(Seq("other/B#bar()." -> "mod/A#foo()."), meta)
    val r = QueryEngine.moduleEdges(g, "mod/")
    assertEquals(r.incoming.map(e => (e.srcId, e.tgtId)), Seq("other/B#bar()." -> "mod/A#foo()."))
    assertEquals(r.outgoing, Seq.empty[QueryEngine.ModuleEdge])
  }

  test("moduleEdges: internal edge not reported") {
    val meta = Map(
      "mod/A#foo()." -> NodeMeta("mod/A.scala", 1, 5, "foo"),
      "mod/A#bar()." -> NodeMeta("mod/A.scala", 7, 10, "bar"),
    )
    val g = makeGraph(Seq("mod/A#foo()." -> "mod/A#bar()."), meta)
    val r = QueryEngine.moduleEdges(g, "mod/")
    assertEquals(r.outgoing, Seq.empty[QueryEngine.ModuleEdge])
    assertEquals(r.incoming, Seq.empty[QueryEngine.ModuleEdge])
  }

  test("moduleEdges: callee not in meta is excluded") {
    // Build graph manually so the library symbol has no meta entry
    val fooId = "mod/A#foo()."
    val libId = "scala/collection/List#map()."
    val g = LoadedGraph(
      out  = Map(fooId -> Set(libId)),
      in   = Map(libId -> Set(fooId)),
      meta = Map(fooId -> NodeMeta("mod/A.scala", 1, 5, "foo")),
    )
    val r = QueryEngine.moduleEdges(g, "mod/")
    assertEquals(r.outgoing, Seq.empty[QueryEngine.ModuleEdge])
  }
}
