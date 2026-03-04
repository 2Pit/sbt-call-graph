ThisBuild / organization := "me.peter"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.20"

// analyzer overrides to 2.13 — scalameta 4.x не публикуется для 2.12
lazy val analyzer = project
  .in(file("modules/analyzer"))
  .settings(
    name         := "graph-explorer-analyzer",
    scalaVersion := "2.13.18",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "4.8.14",
      "com.lihaoyi"   %% "upickle"   % "3.3.1",
    ),
  )

// plugin: sbtPlugin := true форсирует Scala 2.12 (берёт из ThisBuild)
// dependsOn(analyzer) невозможен из-за несовместимости версий Scala;
// взаимодействие через внешний процесс или файл — решим позже
lazy val plugin = project
  .in(file("modules/plugin"))
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
