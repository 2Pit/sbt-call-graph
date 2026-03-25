scalaVersion      := "2.13.14"
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

enablePlugins(CallGraphPlugin)

val graphCheck = taskKey[Unit]("Assert expected call-graph edges from compiled semanticdb")
graphCheck := {
  val _ = (Compile / compile).value
  val roots = graphSemanticdbRoots.value
  val graph = io.github.twopit.callgraph.GraphLoader.load(roots)

  def assertEdge(from: String, to: String): Unit =
    if (!graph.out.getOrElse(from, Set.empty).contains(to))
      sys.error(s"MISSING EDGE: $from → $to")

  def refuteEdge(from: String, to: String): Unit =
    if (graph.out.getOrElse(from, Set.empty).contains(to))
      sys.error(s"UNEXPECTED EDGE: $from → $to")

  // basic direct call
  assertEdge("me/example/Caller.doSomething().", "me/example/Helper.compute().")

  // for-comprehension: step1/step2 reachable from run
  assertEdge("me/example/ForComp.run().", "me/example/ForComp.step1().")
  assertEdge("me/example/ForComp.run().", "me/example/ForComp.step2().")

  // explicit flatMap: same edges
  assertEdge("me/example/ForComp.runExplicit().", "me/example/ForComp.step1().")
  assertEdge("me/example/ForComp.runExplicit().", "me/example/ForComp.step2().")

  // no self-edges
  refuteEdge("me/example/ForComp.step1().", "me/example/ForComp.step1().")

  streams.value.log.success(s"graphCheck passed (${graph.nodeCount} nodes, ${graph.edgeCount} edges)")
}
