addCommandAlias("pub", """set ThisBuild / version := "0.1.0-SNAPSHOT"; analyzer/publishLocal; plugin/publishLocal""")
addCommandAlias("fmt", "scalafmtAll; scalafmtSbt")
addCommandAlias(
  "selfVia",
  "analyzer/graphVia io/github/twopit/callgraph/CallGraphState.main(). --format html --depthOut 10 --depthIn 1",
)

ThisBuild / organization := "io.github.2pit"
ThisBuild / scalaVersion := "2.12.20"

// scalameta 4.x is published for both 2.12 and 2.13
lazy val analyzer = project
  .in(file("modules/analyzer"))
//  .enablePlugins(CallGraphPlugin)
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
