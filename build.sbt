ThisBuild / scalaVersion := "2.12.20"
ThisBuild / organization := "com.blankslate"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val analyzer = project
  .in(file("modules/analyzer"))
  .settings(
    name := "graph-explorer-analyzer",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta"  % "4.9.9",
      "org.scalameta" %% "semanticdb" % "4.9.9",
    ),
  )

lazy val plugin = project
  .in(file("modules/plugin"))
  .dependsOn(analyzer)
  .settings(
    name      := "sbt-graph-explorer",
    sbtPlugin := true,
  )

lazy val root = project
  .in(file("."))
  .aggregate(analyzer, plugin)
  .settings(
    name           := "sbt-graph-explorer-root",
    publish / skip := true,
  )
