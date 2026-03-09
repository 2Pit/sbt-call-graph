package io.github.twopit.graphexplorer

import munit.FunSuite

class HtmlOutputSpec extends FunSuite {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def node(file: String, name: String, line: Int = 1): NodeMeta =
    NodeMeta(file = file, startLine = line - 1, endLine = line - 1, displayName = name)

  private def graph(
      meta: Map[String, NodeMeta],
      edges: (String, String)*
  ): LoadedGraph = {
    val out = edges.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2).toSet }
    val in  = edges.groupBy(_._2).map { case (k, vs) => k -> vs.map(_._1).toSet }
    LoadedGraph(out, in, meta)
  }

  /** Extract the raw value of a `const NAME = VALUE;` JS assignment. Handles: JS string literals `"..."`, JSON objects
    * `{...}`, JSON arrays `[...]`.
    */
  private def jsConst(html: String, name: String): String = {
    val marker     = s"const $name"
    val constStart = html.indexOf(marker)
    assert(constStart >= 0, s"'const $name' not found in HTML")
    val eqPos      = html.indexOf('=', constStart)
    var valueStart = eqPos + 1
    while (valueStart < html.length && html(valueStart) == ' ') valueStart += 1

    if (html(valueStart) == '"') {
      // JS string literal — find closing unescaped quote
      var i = valueStart + 1
      while (i < html.length && !(html(i) == '"' && html(i - 1) != '\\')) i += 1
      html.substring(valueStart, i + 1)
    } else {
      // JSON object or array — match all { } [ ] pairs
      var depth = 0
      var i     = valueStart
      var done  = false
      while (i < html.length && !done) {
        html(i) match {
          case '{' | '[' => depth += 1
          case '}' | ']' => depth -= 1; if (depth == 0) done = true
          case _         =>
        }
        if (!done) i += 1
      }
      assert(done, s"closing bracket not found for 'const $name'")
      html.substring(valueStart, i + 1)
    }
  }

  // ---------------------------------------------------------------------------
  // Simple two-file, two-node, one-edge fixture
  // ---------------------------------------------------------------------------

  private val simpleGraph = graph(
    meta = Map(
      "a/Foo#compute()." -> node("src/Foo.scala", "compute", 10),
      "b/Bar#process()." -> node("src/Bar.scala", "process", 20),
    ),
    "a/Foo#compute()." -> "b/Bar#process().",
  )
  private val simpleNodes = Set("a/Foo#compute().", "b/Bar#process().")
  private val simpleEdges = Set("a/Foo#compute()." -> "b/Bar#process().")

  private def simpleHtml: String =
    HtmlOutput.render(simpleNodes, simpleEdges, simpleGraph, "test graph")

  // ---------------------------------------------------------------------------
  // HTML structure
  // ---------------------------------------------------------------------------

  test("HTML starts with DOCTYPE") {
    assert(simpleHtml.startsWith("<!DOCTYPE html>"))
  }

  test("HTML loads viz.js 2.x from CDN") {
    assert(simpleHtml.contains("viz.js@2.1.2/viz.js"), "missing viz.js script")
    assert(simpleHtml.contains("viz.js@2.1.2/full.render.js"), "missing full.render.js")
  }

  test("HTML contains svg-pan-zoom script") {
    assert(simpleHtml.contains("svg-pan-zoom"), "missing svg-pan-zoom script")
  }

  test("HTML does not reference d3-graphviz") {
    assert(!simpleHtml.contains("d3-graphviz"), "d3-graphviz should not be present")
  }

  test("HTML does not reference @viz-js/viz (wrong package with no standalone build)") {
    assert(!simpleHtml.contains("@viz-js/viz"), "@viz-js/viz has no standalone build, use viz.js@2")
  }

  test("HTML contains Collapse all / Expand all buttons") {
    assert(simpleHtml.contains("Collapse all"))
    assert(simpleHtml.contains("Expand all"))
  }

  test("HTML title is HTML-escaped") {
    val html = HtmlOutput.render(Set.empty, Set.empty, LoadedGraph.empty, "A & <B>")
    assert(html.contains("<title>A &amp; &lt;B></title>"))
  }

  test("HTML creates Viz instance synchronously") {
    assert(simpleHtml.contains("new Viz()"), "Viz() not instantiated")
  }

  test("HTML calls render() on startup") {
    // render() is called directly after viz is created (no async init needed for viz.js@2)
    assert(simpleHtml.contains("render();"), "render() not called on startup")
  }

  test("HTML recreates Viz instance after render error") {
    assert(simpleHtml.contains("viz = new Viz()"), "Viz not recreated after error")
  }

  // ---------------------------------------------------------------------------
  // meta JSON
  // ---------------------------------------------------------------------------

  test("meta JS const is present") {
    assert(simpleHtml.contains("const meta"), "const meta missing")
  }

  test("meta contains both node IDs (n0 and n1)") {
    val m = jsConst(simpleHtml, "meta")
    assert(m.contains("\"n0\"") || m.contains("\"n1\""), s"node IDs missing: $m")
  }

  test("meta contains fqn for both nodes") {
    val m = jsConst(simpleHtml, "meta")
    assert(m.contains("a/Foo#compute"), s"compute fqn missing: $m")
    assert(m.contains("b/Bar#process"), s"process fqn missing: $m")
  }

  test("meta contains file basenames (not full paths)") {
    val m = jsConst(simpleHtml, "meta")
    assert(m.contains("Foo.scala"), s"Foo.scala missing: $m")
    assert(m.contains("Bar.scala"), s"Bar.scala missing: $m")
    assert(!m.contains("src/Foo.scala"), "full path should not appear in file field")
  }

  test("meta startLine is 1-based") {
    val m = jsConst(simpleHtml, "meta")
    // node at line 10 (1-based); stored as 0-based (9), displayed as 10
    assert(m.contains("\"startLine\":10") || m.contains("\"startLine\":20"), s"startLine should be 1-based: $m")
  }

  test("meta contains color hex values") {
    val m = jsConst(simpleHtml, "meta")
    assert(m.contains("color"), s"color missing: $m")
    assert(m.contains("#"), s"color is not hex: $m")
  }

  test("meta contains cls field with class name") {
    val m = jsConst(simpleHtml, "meta")
    assert(m.contains("\"cls\""), s"cls field missing: $m")
    assert(m.contains("\"Foo\""), s"Foo class missing: $m")
    assert(m.contains("\"Bar\""), s"Bar class missing: $m")
  }

  test("two nodes in different classes get different colors") {
    val m = jsConst(simpleHtml, "meta")
    // simpleGraph: a/Foo#compute and b/Bar#process → classes Foo and Bar
    val colors = "#[0-9a-f]{6}".r.findAllIn(m).toSeq
    assert(colors.length >= 2, s"expected at least 2 colors: $m")
    assert(colors.toSet.size >= 2, s"Foo and Bar should have different colors: $colors")
  }

  test("two nodes in the same class get the same color") {
    val g = graph(
      meta = Map(
        "a/Foo#m1()." -> node("src/Foo.scala", "m1", 1),
        "a/Foo#m2()." -> node("src/Foo.scala", "m2", 5),
      )
    )
    val html   = HtmlOutput.render(Set("a/Foo#m1().", "a/Foo#m2()."), Set.empty, g, "t")
    val m      = jsConst(html, "meta")
    val colors = "#[0-9a-f]{6}".r.findAllIn(m).toSeq
    assertEquals(colors.toSet.size, 1, s"same class should have same color: $colors")
  }

  // ---------------------------------------------------------------------------
  // edges JSON
  // ---------------------------------------------------------------------------

  test("edges JS const is present") {
    assert(simpleHtml.contains("const edges"), "const edges missing")
  }

  test("edges contains from and to fields") {
    val e = jsConst(simpleHtml, "edges")
    assert(e.contains("\"from\""), s"edges 'from' field missing: $e")
    assert(e.contains("\"to\""), s"edges 'to' field missing: $e")
  }

  test("edges is non-empty for a graph with edges") {
    val e = jsConst(simpleHtml, "edges")
    assert(e != "[]", s"edges should not be empty: $e")
  }

  test("edges is empty for a graph with no edges") {
    val g    = graph(meta = Map("a/A#f()." -> node("A.scala", "f")))
    val html = HtmlOutput.render(Set("a/A#f()."), Set.empty, g, "t")
    assertEquals(jsConst(html, "edges"), "[]")
  }

  test("edges uses node IDs not FQNs") {
    val e = jsConst(simpleHtml, "edges")
    assert(!e.contains("a/Foo#"), s"edges should use node IDs, not FQNs: $e")
    assert(e.contains("\"n"), s"edges should contain n0/n1 IDs: $e")
  }

  // ---------------------------------------------------------------------------
  // JS DOT builder (extracted from template and verified structurally)
  // ---------------------------------------------------------------------------

  test("HTML contains buildDot function") {
    assert(simpleHtml.contains("function buildDot()"), "buildDot() missing")
  }

  test("buildDot uses subgraph clusters") {
    assert(simpleHtml.contains("subgraph cluster_"), "subgraph cluster template missing")
  }

  test("buildDot respects rankdir LR") {
    assert(simpleHtml.contains("rankdir=LR"), "rankdir=LR missing in buildDot")
  }

  test("buildDot uses fillcolor from meta") {
    assert(simpleHtml.contains("meta[id].color"), "fillcolor not driven by meta.color")
  }

  test("buildDot collapses files to group nodes") {
    assert(simpleHtml.contains("collapsedSet.has(fn)"), "collapse logic missing")
  }

  test("buildDot deduplicates edges") {
    assert(simpleHtml.contains("seen.has(key)"), "edge deduplication missing")
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  test("node without meta is rendered with FQN as label") {
    val g    = graph(meta = Map.empty)
    val html = HtmlOutput.render(Set("orphan/X#m()."), Set.empty, g, "t")
    assert(html.contains("orphan/X#m()."), "FQN should be used as label for unknown node")
  }

  test("special characters in labels are escaped") {
    val g    = graph(meta = Map("x/A#m()." -> node("A.scala", "say \"hi\"", 1)))
    val html = HtmlOutput.render(Set("x/A#m()."), Set.empty, g, "t")
    // The label appears in the meta JSON — double quotes must be escaped
    assert(!html.contains(":\"say \"hi\"\""), "unescaped quote in JSON label")
    assert(html.contains("say \\\"hi\\\""), "label not properly escaped in JSON")
  }

  test("empty graph produces valid HTML with empty meta and edges") {
    val html = HtmlOutput.render(Set.empty, Set.empty, LoadedGraph.empty, "empty")
    assertEquals(jsConst(html, "meta"), "{}")
    assertEquals(jsConst(html, "edges"), "[]")
  }

  // ---------------------------------------------------------------------------
  // DotOutput.prepareData (shared sorting logic)
  // ---------------------------------------------------------------------------

  test("DotOutput.prepareData: sorted by file then startLine") {
    val g = graph(meta =
      Map(
        "b/B#b()." -> node("src/B.scala", "b", 5),
        "a/A#a()." -> node("src/A.scala", "a", 3),
      )
    )
    val data = DotOutput.prepareData(Set("a/A#a().", "b/B#b()."), g)
    assertEquals(data.idOf("a/A#a()."), "n0")
    assertEquals(data.idOf("b/B#b()."), "n1")
  }

  test("DotOutput.prepareData: two nodes in same file, sorted by line") {
    val g = graph(meta =
      Map(
        "a/A#late()."  -> node("src/A.scala", "late", 20),
        "a/A#early()." -> node("src/A.scala", "early", 5),
      )
    )
    val data = DotOutput.prepareData(Set("a/A#late().", "a/A#early()."), g)
    assertEquals(data.idOf("a/A#early()."), "n0")
    assertEquals(data.idOf("a/A#late()."), "n1")
  }

  test("DotOutput.prepareData: groups nodes by class name") {
    val g = graph(meta =
      Map(
        "a/A#a()." -> node("src/A.scala", "a", 1),
        "a/A#b()." -> node("src/A.scala", "b", 2),
        "b/B#c()." -> node("src/B.scala", "c", 1),
      )
    )
    val data = DotOutput.prepareData(Set("a/A#a().", "a/A#b().", "b/B#c()."), g)
    assertEquals(data.byGroup("A").toSet, Set("a/A#a().", "a/A#b()."))
    assertEquals(data.byGroup("B").toSet, Set("b/B#c()."))
  }

  test("DotOutput.renderGraph: contains subgraph cluster for each class") {
    val g = graph(meta =
      Map(
        "a/A#a()." -> node("src/A.scala", "a", 1),
        "b/B#b()." -> node("src/B.scala", "b", 1),
      )
    )
    val data = DotOutput.prepareData(Set("a/A#a().", "b/B#b()."), g)
    val dot  = DotOutput.renderGraph(data, Set("a/A#a()." -> "b/B#b()."), g, "t")
    assert(dot.contains("subgraph cluster_"), "missing subgraph")
    assert(dot.contains("\"A\""), "missing class A label")
    assert(dot.contains("\"B\""), "missing class B label")
    assert(dot.contains("->"), "missing edge")
  }
}
