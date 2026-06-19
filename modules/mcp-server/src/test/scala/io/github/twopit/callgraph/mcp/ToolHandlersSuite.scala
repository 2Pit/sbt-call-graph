package io.github.twopit.callgraph.mcp

import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper}
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest

import java.nio.file.{Files, Path}
import java.util.{ArrayList => JList, HashMap => JMap}
import scala.collection.JavaConverters._

class ToolHandlersSuite extends munit.FunSuite {

  private val jsonMapper: McpJsonMapper = McpJsonDefaults.getMapper

  private case class Fix(service: GraphService, outputDir: Path, workspaceRoot: Path)

  private def withEmptyService[A](f: Fix => A): A = {
    val dir = Files.createTempDirectory("cg-tools-")
    try {
      val out = dir.resolve("target").resolve("call-graph")
      f(Fix(new GraphService(dir, Nil), out, dir))
    } finally
      try {
        // best-effort cleanup; don't fail tests on it
        val walker = Files.walk(dir).iterator()
        val all    = scala.collection.mutable.ArrayBuffer.empty[Path]
        while (walker.hasNext) all += walker.next()
        all.reverse.foreach(p => try Files.deleteIfExists(p) catch { case _: Throwable => () })
      } catch { case _: Throwable => () }
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

  // pass the workspace root; the server derives outputDir = root/target/call-graph (== fix.outputDir).
  private def tools(fix: Fix) = ToolHandlers.all(fix.service, jsonMapper, fix.workspaceRoot).asScala.toList
  private def tool(fix: Fix, name: String)    = tools(fix).find(_.tool().name() == name).get
  private def filesIn(dir: Path): Seq[Path] =
    if (!Files.isDirectory(dir)) Nil
    else {
      val s = Files.list(dir)
      try s.iterator().asScala.toList
      finally s.close()
    }

  test("registers exactly five tools") {
    withEmptyService { fix =>
      val names = tools(fix).map(_.tool().name())
      assertEquals(names.sorted, List("graphIndex", "graphModule", "graphPath", "graphSearch", "graphVia"))
    }
  }

  test("graphIndex returns JSON with status, notCompiled, emptyGraph on empty workspace") {
    withEmptyService { fix =>
      val res = call(tool(fix, "graphIndex"), Map.empty)
      assert(!res.isError, s"unexpected error: ${textOf(res)}")
      val json = textOf(res)
      assert(json.contains("\"status\""), json)
      assert(json.contains("\"notCompiled\": true"), json)
      assert(json.contains("\"emptyGraph\": false"), json)
    }
  }

  test("graphIndex ignores mode=file (always inline) and does not write a file") {
    withEmptyService { fix =>
      val res  = call(tool(fix, "graphIndex"), Map("mode" -> "file"))
      val json = textOf(res)
      assert(!json.contains("\"file\":"), s"graphIndex should not divert to disk; got: $json")
      assertEquals(filesIn(fix.outputDir).filter(_.toString.endsWith(".json")), Nil)
    }
  }

  test("graphSearch with empty graph returns inline JSON (small response, auto mode)") {
    withEmptyService { fix =>
      val res  = call(tool(fix, "graphSearch"), Map("query" -> "Anything"))
      val json = textOf(res)
      assert(json.contains("\"count\""), json)
      assert(json.contains("\"matches\""), json)
      // small response — no file should be written
      assertEquals(filesIn(fix.outputDir).filter(_.toString.endsWith(".json")), Nil)
    }
  }

  test("graphSearch with mode=file writes file and returns summary even when small") {
    withEmptyService { fix =>
      val res  = call(tool(fix, "graphSearch"), Map("query" -> "Anything", "mode" -> "file"))
      val json = textOf(res)
      assert(json.contains("\"file\":"), json)
      assert(json.contains("\"previewNodes\":"), json)
      assert(json.contains("\"readHints\":"), json)
      assert(json.contains("\"nodes\":"), json)
      assert(json.contains("\"edges\":"), json)
      val written = filesIn(fix.outputDir).filter(_.toString.endsWith(".json"))
      assertEquals(written.size, 1, s"expected exactly one .json file under ${fix.outputDir}, found: $written")
    }
  }

  test("mode=inline forces inline output and writes no file") {
    withEmptyService { fix =>
      val res  = call(tool(fix, "graphVia"), Map("vertex" -> "nope/such/Vertex.x().", "mode" -> "inline"))
      val json = textOf(res)
      assert(json.contains("\"depthIn\""), s"expected full JSON; got: $json")
      assert(!json.contains("\"file\":"), s"inline mode should not write file; got: $json")
      assertEquals(filesIn(fix.outputDir).filter(_.toString.endsWith(".json")), Nil)
    }
  }

  test("invalid mode -> isError=true") {
    withEmptyService { fix =>
      val res = call(tool(fix, "graphSearch"), Map("query" -> "x", "mode" -> "bogus"))
      assert(res.isError, "expected isError=true for unknown mode value")
    }
  }

  test("graphSearch missing required arg -> isError=true") {
    withEmptyService { fix =>
      val res = call(tool(fix, "graphSearch"), Map.empty)
      assert(res.isError, "expected isError=true when 'query' is missing")
    }
  }

  test("graphPath with single vertex -> graceful (no isError)") {
    withEmptyService { fix =>
      val list = new JList[AnyRef](); list.add("x")
      val res  = call(tool(fix, "graphPath"), Map("vertices" -> list))
      assert(!res.isError, s"got error: ${textOf(res)}")
    }
  }

  test("graphVia with depth defaults") {
    withEmptyService { fix =>
      val res = call(tool(fix, "graphVia"), Map("vertex" -> "nope/such/Vertex.x()."))
      assert(!res.isError, s"got error: ${textOf(res)}")
      val json = textOf(res)
      assert(json.contains("\"depthIn\""), json)
      assert(json.contains("\"depthOut\""), json)
    }
  }

  test("graphModule with arbitrary prefix") {
    withEmptyService { fix =>
      val res = call(tool(fix, "graphModule"), Map("prefix" -> "any"))
      assert(!res.isError)
      val json = textOf(res)
      assert(json.contains("\"outgoing\""))
      assert(json.contains("\"incoming\""))
    }
  }

  // -------- pure policy unit tests --------

  test("ToolOutput.render auto: small inline, large -> file with summary") {
    withEmptyService { fix =>
      val small = ToolResult(
        fullJson = "{\"x\": 1}",
        nodeCount = 0,
        edgeCount = 0,
        found = Some(false),
        truncated = Some(false),
        previewNodeIds = Nil,
      )
      val smallOut = ToolOutput.render("graphVia", small, OutputMode.Auto, fix.outputDir)
      assertEquals(smallOut, small.fullJson)

      val bigBody = "x" * (ToolHandlers.AutoInlineThresholdBytes + 100)
      val big = ToolResult(
        fullJson = s"""{"payload": "$bigBody"}""",
        nodeCount = 42,
        edgeCount = 99,
        found = Some(true),
        truncated = Some(false),
        previewNodeIds = Seq("a/B#c().", "d/E#f()."),
      )
      val bigOut = ToolOutput.render("graphVia", big, OutputMode.Auto, fix.outputDir)
      assert(bigOut.contains("\"file\":"), bigOut)
      assert(bigOut.contains("\"nodes\": 42"), bigOut)
      assert(bigOut.contains("\"edges\": 99"), bigOut)
      assert(bigOut.contains("\"previewNodes\":"), bigOut)
      assert(bigOut.contains("a/B#c()."), bigOut)
      val written = filesIn(fix.outputDir).filter(_.toString.endsWith(".json"))
      assertEquals(written.size, 1)
    }
  }
}
