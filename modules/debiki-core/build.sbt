name := "debiki-core"

organization := "com.debiki"

version := "0.0.2-SNAPSHOT"

scalaVersion := "2.11.7"

resolvers += "Scala-Tools Maven2 Repository" at "http://scala-tools.org/repo-releases"

libraryDependencies ++= Seq(
  "org.scalactic" %% "scalactic" % "2.2.1",
  "com.google.guava" % "guava" % "10.0.1",
  "commons-codec" % "commons-codec" % "1.5",
  "nu.validator.htmlparser" % "htmlparser" % "1.2.1",
  "com.typesafe.play" %% "play" % "2.3.1", // for parsing JSON, unfortunately brings in all other Play stuff
  "com.lambdaworks" % "scrypt" % "1.4.0", // COULD move to debiki-server, see comments in src/main/scala/com/debiki/core/dao-db.scala
  "junit" % "junit" % "4.7" % "test",
  "org.specs2" %% "specs2" % "2.3.12" % "test",
  "org.scalatest" %% "scalatest" % "2.2.0" % "test"
)

scalacOptions += "-deprecation"

// See: https://groups.google.com/forum/?fromgroups=#!topic/simple-build-tool/bkF1IDZj4L0
ideaPackagePrefix := None

// Makes `dependency-graph` work.
net.virtualvoid.sbt.graph.Plugin.graphSettings

