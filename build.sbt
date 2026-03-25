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
  Developer("2Pit", "Petr B.", "2Pit@users.noreply.github.com", url("https://github.com/2Pit")),
)
ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/2Pit/sbt-call-graph"), "scm:git@github.com:2Pit/sbt-call-graph.git"),
)
ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost
ThisBuild / description := "SBT plugin that builds a method-level call graph from SemanticDB"

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

lazy val root = project
  .in(file("."))
  .aggregate(analyzer, plugin)
  .settings(
    name           := "sbt-call-graph-root",
    publish / skip := true,
  )
