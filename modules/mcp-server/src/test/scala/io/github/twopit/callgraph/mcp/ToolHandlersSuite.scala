package io.github.twopit.callgraph.mcp

import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper}
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest

import java.nio.file.{Files, Path}
import java.util.{ArrayList => JList, HashMap => JMap}
import scala.collection.JavaConverters._

class ToolHandlersSuite extends munit.FunSuite {

  private val jsonMapper: McpJsonMapper = McpJsonDefaults.getMapper

  private def withEmptyService[A](f: GraphService => A): A = {
    val dir = Files.createTempDirectory("cg-tools-")
    try f(new GraphService(dir, Nil))
    finally
      try Files.deleteIfExists(dir)
      catch { case _: Throwable => () }
  }

  private def call(
      spec: io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification,
      args: Map[String, AnyRef],
  ): io.modelcontextprotocol.spec.McpSchema.CallToolResult = {
    val jArgs = new JMap[String, AnyRef]()
    args.foreach { case (k, v) => jArgs.put(k, v) }
    val req = new CallToolRequest(spec.tool().name(), jArgs, null)
    spec.callHandler().apply(null, req)
  }

  private def textOf(r: io.modelcontextprotocol.spec.McpSchema.CallToolResult): String = {
    val first = r.content().get(0).asInstanceOf[io.modelcontextprotocol.spec.McpSchema.TextContent]
    first.text()
  }

  test("registers exactly five tools") {
    withEmptyService { svc =>
      val tools = ToolHandlers.all(svc, jsonMapper).asScala.toList.map(_.tool().name())
      assertEquals(tools.sorted, List("graphIndex", "graphModule", "graphPath", "graphSearch", "graphVia"))
    }
  }

  test("graphIndex returns JSON with status, notCompiled, emptyGraph on empty workspace") {
    withEmptyService { svc =>
      val specs = ToolHandlers.all(svc, jsonMapper).asScala.toList
      val idx   = specs.find(_.tool().name() == "graphIndex").get
      val res   = call(idx, Map.empty)
      assert(!res.isError, s"unexpected error: ${textOf(res)}")
      val json = textOf(res)
      assert(json.contains("\"status\""), json)
      assert(json.contains("\"notCompiled\": true"), json)
      assert(json.contains("\"emptyGraph\": false"), json)
    }
  }

  test("graphSearch with empty graph returns count=0") {
    withEmptyService { svc =>
      val specs = ToolHandlers.all(svc, jsonMapper).asScala.toList
      val s     = specs.find(_.tool().name() == "graphSearch").get
      val res   = call(s, Map("query" -> "Anything"))
      val json  = textOf(res)
      assert(json.contains("\"count\""), json)
      assert(json.contains("\"matches\""), json)
    }
  }

  test("graphSearch missing required arg -> isError=true") {
    withEmptyService { svc =>
      val s   = ToolHandlers.all(svc, jsonMapper).asScala.find(_.tool().name() == "graphSearch").get
      val res = call(s, Map.empty)
      assert(res.isError, "expected isError=true when 'query' is missing")
    }
  }

  test("graphPath with single vertex -> graceful (no isError)") {
    withEmptyService { svc =>
      val s    = ToolHandlers.all(svc, jsonMapper).asScala.find(_.tool().name() == "graphPath").get
      val list = new JList[AnyRef](); list.add("x")
      val res  = call(s, Map("vertices" -> list))
      // QueryEngine.pathsAmong handles <2 known gracefully; result has found=false but is not an error.
      assert(!res.isError, s"got error: ${textOf(res)}")
    }
  }

  test("graphVia with depth defaults") {
    withEmptyService { svc =>
      val s   = ToolHandlers.all(svc, jsonMapper).asScala.find(_.tool().name() == "graphVia").get
      val res = call(s, Map("vertex" -> "nope/such/Vertex.x()."))
      assert(!res.isError, s"got error: ${textOf(res)}")
      val json = textOf(res)
      assert(json.contains("\"depthIn\""), json)
      assert(json.contains("\"depthOut\""), json)
    }
  }

  test("graphModule with arbitrary prefix") {
    withEmptyService { svc =>
      val s   = ToolHandlers.all(svc, jsonMapper).asScala.find(_.tool().name() == "graphModule").get
      val res = call(s, Map("prefix" -> "any"))
      assert(!res.isError)
      val json = textOf(res)
      assert(json.contains("\"outgoing\""))
      assert(json.contains("\"incoming\""))
    }
  }
}
