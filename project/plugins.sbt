addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("com.github.sbt" % "sbt-dynver"   % "5.1.0")

// Bootstrapping: the plugin dogfoods itself.
// Run `pub` (publishLocal) once, then `reload` to enable graphVia/graphPath on this project.
// If the published version is missing, sbt will fail on reload — just comment this line out.
//addSbtPlugin("io.github.2pit" % "sbt-graph-explorer" % "0.1.0-SNAPSHOT")
