package io.github.twopit.callgraph.mcp

import java.nio.file.{Files, Path, Paths}

class GraphServiceSuite extends munit.FunSuite {

  private def withTempDir[A](f: Path => A): A = {
    val dir = Files.createTempDirectory("cg-mcp-")
    try f(dir)
    finally {
      val stream = Files.walk(dir)
      try {
        val it  = stream.iterator()
        val all = scala.collection.mutable.ListBuffer.empty[Path]
        while (it.hasNext) all += it.next()
        all.reverse.foreach(p =>
          try Files.deleteIfExists(p)
          catch { case _: Throwable => () }
        )
      } finally stream.close()
    }
  }

  test("empty workspace -> notCompiled=true, emptyGraph=false") {
    withTempDir { dir =>
      val svc         = new GraphService(dir, Nil)
      val (graph, st) = svc.getGraph()
      assert(st.notCompiled, "expected notCompiled when no .semanticdb present")
      assert(!st.emptyGraph, "emptyGraph should be false when nothing was even loaded")
      assert(st.unusable, "graph is unusable in this state")
      assertEquals(graph.nodeCount, 0)
      assert(st.message.contains("no .semanticdb"))
    }
  }

  test("discoverSemanticdbDirs picks up target/.../meta directories") {
    withTempDir { dir =>
      val meta = dir.resolve("target/scala-2.13/meta/foo")
      Files.createDirectories(meta)
      val svc  = new GraphService(dir, Nil)
      val dirs = svc.discoverSemanticdbDirs(dir)
      assert(dirs.exists(_.toString.endsWith("meta")), s"got: $dirs")
    }
  }

  test("discoverSemanticdbDirs excludes .worktrees when asked") {
    withTempDir { dir =>
      Files.createDirectories(dir.resolve("target/scala-2.13/meta/foo"))
      Files.createDirectories(dir.resolve(".worktrees/wt1/target/scala-2.13/meta/bar"))
      val svc  = new GraphService(dir, Nil)
      val all  = svc.discoverSemanticdbDirs(dir, excludeWorktrees = false)
      val main = svc.discoverSemanticdbDirs(dir, excludeWorktrees = true)
      assert(all.exists(_.toString.contains(".worktrees")), s"raw scan should see worktree dirs: $all")
      assert(!main.exists(_.toString.contains(".worktrees")), s"excluded scan must drop worktree dirs: $main")
      assert(main.nonEmpty, "main checkout meta dir must still be found")
    }
  }

  test("getGraph(Some(unknown)) -> notCompiled with a worktree-specific message") {
    withTempDir { dir =>
      val svc     = new GraphService(dir, Nil)
      val (_, st) = svc.getGraph(Some("does-not-exist"))
      assert(st.notCompiled, "missing worktree should report notCompiled")
      assert(st.message.contains("worktree"), s"message should name the worktree problem: ${st.message}")
    }
  }

  test("computeStamp = 0 when no .semanticdb files") {
    withTempDir { dir =>
      val svc = new GraphService(dir, Nil)
      assertEquals(svc.computeStamp(Seq(dir)), 0L)
    }
  }

  test("computeStamp reflects file mtime") {
    withTempDir { dir =>
      val meta = dir.resolve("meta")
      Files.createDirectories(meta)
      val f = meta.resolve("a.semanticdb")
      Files.write(f, Array[Byte](0, 1, 2))
      val svc = new GraphService(dir, Nil)
      val s   = svc.computeStamp(Seq(meta))
      assert(s > 0L, s"expected positive stamp, got $s")
    }
  }
}
