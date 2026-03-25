addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("com.github.sbt" % "sbt-dynver"   % "5.1.0")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.12.2")
addSbtPlugin("com.github.sbt" % "sbt-pgp"      % "2.3.1")

// Bootstrapping: the plugin dogfoods itself.
// Run `pub` (publishLocal) once, then `reload` to enable graphVia/graphPath on this project.
// If the published version is missing, sbt will fail on reload — just comment this line out.
addSbtPlugin("io.github.2pit" % "sbt-call-graph" % "0.1.0-SNAPSHOT")
