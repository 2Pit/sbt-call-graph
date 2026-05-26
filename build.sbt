addCommandAlias("pub", """set ThisBuild / version := "0.1.0-SNAPSHOT"; analyzer/publishLocal; plugin/publishLocal""")
addCommandAlias("fmt", "scalafmtAll; scalafmtSbt")
addCommandAlias(
  "selfVia",
  "analyzer/graphVia io/github/twopit/callgraph/CallGraphState.getOrLoad(). --format html --depthOut 3 --depthIn 2",
)

ThisBuild / organization := "io.github.2pit"
ThisBuild / scalaVersion := "2.12.20"
ThisBuild / homepage     := Some(url("https://github.com/2Pit/sbt-call-graph"))
ThisBuild / licenses     := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / developers := List(
  Developer("2Pit", "Petr B.", "2Pit@users.noreply.github.com", url("https://github.com/2Pit"))
)
ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/2Pit/sbt-call-graph"), "scm:git@github.com:2Pit/sbt-call-graph.git")
)
ThisBuild / sonatypeCredentialHost           := xerial.sbt.Sonatype.sonatypeCentralHost
ThisBuild / publishTo                        := sonatypePublishToBundle.value
ThisBuild / versionScheme                    := Some("early-semver")
ThisBuild / sbtPluginPublishLegacyMavenStyle := false
ThisBuild / description                      := "SBT plugin that builds a method-level call graph from SemanticDB"

// scalameta 4.x is published for both 2.12 and 2.13
lazy val analyzer = project
  .in(file("modules/analyzer"))
  .enablePlugins(CallGraphPlugin)
  .settings(
    name              := "call-graph-analyzer",
    semanticdbEnabled := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "4.8.14",
      "org.scalameta" %% "munit"     % "0.7.29" % Test,
    ),
  )

lazy val plugin = project
  .in(file("modules/plugin"))
  .dependsOn(analyzer)
  .enablePlugins(ScriptedPlugin)
  .settings(
    name                := "sbt-call-graph",
    sbtPlugin           := true,
    scriptedLaunchOpts ++= Seq("-Xmx1g", s"-Dplugin.version=${version.value}"),
    scriptedBufferLog   := false,
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
  .aggregate(analyzer, plugin, mcpServer)
  .settings(
    name           := "sbt-call-graph-root",
    publish / skip := true,
  )
