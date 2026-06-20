package io.github.twopit.callgraph.mcp

import java.util.{ArrayList => JList, HashMap => JMap}

class ArgsSuite extends munit.FunSuite {

  private def map(pairs: (String, AnyRef)*): java.util.Map[String, AnyRef] = {
    val m = new JMap[String, AnyRef]()
    pairs.foreach { case (k, v) => m.put(k, v) }
    m
  }

  private def jlist(items: AnyRef*): java.util.List[AnyRef] = {
    val l = new JList[AnyRef]()
    items.foreach(l.add)
    l
  }

  test("str: returns string value") {
    assertEquals(Args.str(map("k" -> "v"), "k"), "v")
  }

  test("str: missing key throws") {
    intercept[ToolArgError](Args.str(map(), "k"))
  }

  test("str: empty string throws") {
    intercept[ToolArgError](Args.str(map("k" -> ""), "k"))
  }

  test("str: null map throws") {
    intercept[ToolArgError](Args.str(null, "k"))
  }

  test("int: default when absent") {
    assertEquals(Args.int(map(), "k", 7), 7)
  }

  test("int: accepts Integer") {
    assertEquals(Args.int(map("k" -> Integer.valueOf(3)), "k", 0), 3)
  }

  test("int: accepts Long") {
    assertEquals(Args.int(map("k" -> java.lang.Long.valueOf(4L)), "k", 0), 4)
  }

  test("int: accepts numeric string") {
    assertEquals(Args.int(map("k" -> "5"), "k", 0), 5)
  }

  test("int: non-numeric string throws") {
    intercept[ToolArgError](Args.int(map("k" -> "abc"), "k", 0))
  }

  test("strList: returns list of strings") {
    val v = Args.strList(map("k" -> jlist("a", "b")), "k")
    assertEquals(v, Seq("a", "b"))
  }

  test("strList: missing throws") {
    intercept[ToolArgError](Args.strList(map(), "k"))
  }

  test("strList: empty array throws") {
    intercept[ToolArgError](Args.strList(map("k" -> jlist()), "k"))
  }

  test("regexes: missing returns empty") {
    assertEquals(Args.regexes(map(), "k"), Seq.empty)
  }

  test("regexes: parses patterns") {
    val rs = Args.regexes(map("k" -> jlist("foo.*", "bar")), "k")
    assertEquals(rs.size, 2)
    assert(rs.head.findFirstIn("foobar").isDefined)
  }

  test("regexes: non-string item throws") {
    intercept[ToolArgError](Args.regexes(map("k" -> jlist("ok", Integer.valueOf(1))), "k"))
  }

  test("worktreeArg: absent or empty throws (required)") {
    intercept[ToolArgError](Args.worktreeArg(map()))
    intercept[ToolArgError](Args.worktreeArg(map("worktree" -> "")))
  }

  test("worktreeArg: \".\" -> None (main checkout)") {
    assertEquals(Args.worktreeArg(map("worktree" -> ".")), None)
  }

  test("worktreeArg: bare name -> Some") {
    assertEquals(Args.worktreeArg(map("worktree" -> "cert-scheduler")), Some("cert-scheduler"))
  }

  test("worktreeArg: path separators or .. throw") {
    intercept[ToolArgError](Args.worktreeArg(map("worktree" -> "../etc")))
    intercept[ToolArgError](Args.worktreeArg(map("worktree" -> "a/b")))
  }
}
