ThisBuild / organization := "me.peter"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.20"

// scalameta 4.x публикуется и для 2.12, и для 2.13
lazy val analyzer = project
  .in(file("modules/analyzer"))
  .settings(
    name := "graph-explorer-analyzer",
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
    name             := "sbt-graph-explorer",
    sbtPlugin        := true,
    scriptedLaunchOpts ++= Seq("-Xmx1g", s"-Dplugin.version=${version.value}"),
    scriptedBufferLog := false,
  )

lazy val root = project
  .in(file("."))
  .aggregate(analyzer, plugin)
  .settings(
    name           := "sbt-graph-explorer-root",
    publish / skip := true,
  )
