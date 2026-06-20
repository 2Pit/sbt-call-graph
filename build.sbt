addCommandAlias("pub", """set ThisBuild / version := "0.1.0-SNAPSHOT"; analyzer/publishLocal""")
addCommandAlias("fmt", "scalafmtAll; scalafmtSbt")

ThisBuild / organization  := "io.github.2pit"
ThisBuild / scalaVersion  := "2.13.18"
ThisBuild / versionScheme := Some("early-semver")

// scalameta 4.x is published for both 2.12 and 2.13
lazy val analyzer = project
  .in(file("modules/analyzer"))
  .settings(
    name              := "call-graph-analyzer",
    semanticdbEnabled := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "4.8.14",
      "org.scalameta" %% "munit"     % "0.7.29" % Test,
    ),
  )

lazy val mcpServer = project
  .in(file("modules/mcp-server"))
  .dependsOn(analyzer)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name              := "call-graph-mcp",
    publish / skip    := true,
    semanticdbEnabled := true,
    libraryDependencies ++= Seq(
      "io.modelcontextprotocol.sdk" % "mcp"   % "1.1.3",
      "org.scalameta"              %% "munit" % "0.7.29" % Test,
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "io.github.twopit.callgraph.mcp",
    buildInfoObject            := "BuildVersion",
    assembly / mainClass       := Some("io.github.twopit.callgraph.mcp.Main"),
    assembly / assemblyJarName := "call-graph-mcp.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF")                                        => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.exists(_.toLowerCase.endsWith(".sf"))  => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.exists(_.toLowerCase.endsWith(".dsa")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.exists(_.toLowerCase.endsWith(".rsa")) => MergeStrategy.discard
      case PathList("module-info.class")                                              => MergeStrategy.discard
      case x if x.endsWith("/module-info.class")                                      => MergeStrategy.discard
      case _                                                                          => MergeStrategy.first
    },
  )

lazy val root = project
  .in(file("."))
  .aggregate(analyzer, mcpServer)
  .settings(
    name           := "sbt-call-graph-root",
    publish / skip := true,
  )
