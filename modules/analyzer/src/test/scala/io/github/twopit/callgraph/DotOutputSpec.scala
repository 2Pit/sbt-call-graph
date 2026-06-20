package io.github.twopit.callgraph

import munit.FunSuite

import java.nio.file.Files

class DotOutputSpec extends FunSuite {

  private def node(file: String, name: String, line: Int = 1): NodeMeta =
    NodeMeta(file = file, startLine = line - 1, endLine = line - 1, displayName = name)

  private def graph(meta: Map[String, NodeMeta], edges: (String, String)*): LoadedGraph = {
    val out = edges.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2).toSet }
    val in  = edges.groupBy(_._2).map { case (k, vs) => k -> vs.map(_._1).toSet }
    LoadedGraph(out, in, meta)
  }

  test("prepareData: sorted by file then startLine") {
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

  test("prepareData: two nodes in same file, sorted by line") {
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

  test("prepareData: groups nodes by class name") {
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

  test("renderGraph: a subgraph cluster per class, LR rankdir, and the edge") {
    val g = graph(meta =
      Map(
        "a/A#a()." -> node("src/A.scala", "a", 1),
        "b/B#b()." -> node("src/B.scala", "b", 1),
      )
    )
    val data = DotOutput.prepareData(Set("a/A#a().", "b/B#b()."), g)
    val dot  = DotOutput.renderGraph(data, Set("a/A#a()." -> "b/B#b()."), g, "t")
    assert(dot.startsWith("digraph"), "not a digraph")
    assert(dot.contains("rankdir=LR"), "missing rankdir=LR")
    assert(dot.contains("subgraph cluster_"), "missing subgraph")
    assert(dot.contains("\"A\"") && dot.contains("\"B\""), "missing class labels")
    assert(dot.contains("->"), "missing edge")
  }

  test("dq escapes quotes, backslashes and newlines") {
    assertEquals(DotOutput.dq("say \"hi\"\n\\x"), "\"say \\\"hi\\\"\\n\\\\x\"")
  }

  test("writeGraphResult writes a .dot file from a GraphResult") {
    val g = graph(
      meta = Map(
        "a/Foo#compute()." -> node("src/Foo.scala", "compute", 10),
        "b/Bar#process()." -> node("src/Bar.scala", "process", 20),
      ),
      "a/Foo#compute()." -> "b/Bar#process().",
    )
    val result = GraphResult(
      nodes = Seq("a/Foo#compute().", "b/Bar#process()."),
      edges = Seq("a/Foo#compute()." -> "b/Bar#process()."),
    )
    val out = Files.createTempDirectory("dot-test").resolve("g.dot")
    DotOutput.writeGraphResult(result, "test graph", g, out)
    val dot = new String(Files.readAllBytes(out))
    assert(dot.contains("compute") && dot.contains("process"), "node labels missing")
    assert(dot.contains("->"), "edge missing")
  }
}
