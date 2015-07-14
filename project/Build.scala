/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import sbt._
import Keys._
import com.typesafe.sbteclipse.plugin.EclipsePlugin.EclipseKeys
import java.{net => jn}

object ApplicationBuild extends Build {

  val appName         = "debiki-server"
  val appVersion      = "1.0-SNAPSHOT"

  lazy val debikiCore =
    Project("debiki-core", file("modules/debiki-core"))

  lazy val debikiTckDao =
    (Project("debiki-tck-dao", file("modules/debiki-tck-dao"))
    dependsOn(debikiCore ))

  lazy val debikiDaoRdb =
    (Project("debiki-dao-rdb", file("modules/debiki-dao-rdb"))
    dependsOn(debikiCore, debikiTckDao % "test"))


  val appDependencies = Seq(
    play.Play.autoImport.cache,
    play.Play.autoImport.filters,
    // Authentication.
    "com.mohiva" %% "play-silhouette" % "1.0",
    // There's a PostgreSQL 903 build number too but it's not in the Maven repos.
    // PostgreSQL 9.2 drivers are also not in the Maven repos (as of May 2013).
    "postgresql" % "postgresql" % "9.1-901-1.jdbc4",
    "org.apache.commons" % "commons-email" % "1.3.3",
    "com.google.guava" % "guava" % "13.0.1",
    "org.owasp.encoder" % "encoder" % "1.1.1",
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
    // JSR 305 is requried by Guava, at build time only (so specify "provided"
    // so it won't be included in the JAR), or there's this weird error: """
    //   class file '...guava-13.0.1.jar(.../LocalCache.class)' is broken
    //   [error] (class java.lang.RuntimeException/bad constant pool tag 9 at byte 125)
    //   [warn] Class javax.annotation.CheckReturnValue not found ..."""
    // See: http://code.google.com/p/guava-libraries/issues/detail?id=776
    // and: http://stackoverflow.com/questions/10007994/
    //              why-do-i-need-jsr305-to-use-guava-in-scala
    "com.google.code.findbugs" % "jsr305" % "1.3.9" % "provided",
    // "com.twitter" %% "ostrich" % "4.10.6",
    "org.mockito" % "mockito-all" % "1.9.0" % "test", // I use Mockito with Specs2...
    "org.scalatest" %% "scalatest" % "2.2.0" % "test", // but prefer ScalaTest
    "org.scalatestplus" %% "play" % "1.2.0" % "test",
    // Use a recent Selenium driver, otherwise it won't work with the version of Firefox,
    // that your OS has probably upgraded to some days/months ago.
    // See: http://stackoverflow.com/a/13049150/694469
    // And see: https://groups.google.com/d/msg/play-framework/EmP9v10fH9Q/dz4k_qXlpFQJ
    // (I also got the """org.openqa.selenium.firefox.NotConnectedException: Unable
    // to connect to host 127.0.0.1 on port 7055 """ error.)
    "org.seleniumhq.selenium" % "selenium-java" % "2.35.0" % "test")


  // Make `idea with-sources` work in subprojects.
  // No longer needed, don't know why.
  override def settings =
    super.settings ++
    // By default, sbteclipse skips parent projects.
    // See the "skipParents" section, here:
    // https://github.com/typesafehub/sbteclipse/wiki/Using-sbteclipse
    Seq(EclipseKeys.skipParents := false, resolvers := Seq())


  val main = Project(appName, file(".")).enablePlugins(play.PlayScala)
    .settings(mainSettings: _*)
    .dependsOn(debikiCore % "test->test;compile->compile", debikiDaoRdb)


  def mainSettings = List(
    version := appVersion,
    libraryDependencies ++= appDependencies,
    scalaVersion := "2.11.1",

    // Disable ScalaDoc generation, it breaks seemingly because I'm compiling some Javascript
    // files to Java, and ScalaDoc complains the generated classes don't exist and breaks
    // the `dist` task.
    sources in (Compile, doc) := Seq.empty, // don't generate any docs
    publishArtifact in (Compile, packageDoc) := false,  // don't generate doc JAR

    Keys.fork in Test := false, // or cannot place breakpoints in test suites
    unmanagedClasspath in Compile <+= (baseDirectory) map { bd =>
      Attributed.blank(bd / "target/scala-2.10/compiledjs-classes")
    },
    listJarsTask)

  // This is supposedly needed when using ScalaTest instead of Specs2,
  // see: http://stackoverflow.com/a/10378430/694469, but I haven't
  // activated this, because ScalaTest works fine anyway:
  // `testOptions in Test := Nil`

  // Lists dependencies.
  // See: http://stackoverflow.com/a/6509428/694469
  // ((Could do that before and after upgrading Play Framework, and run a diff,
  // to find changed dependencies, in case terribly weird compilation
  // errors arise, e.g. "not enough arguments for method" or
  // "value getUnchecked is not a member of". Such errors happened when I built
  // debiki-server with the very same version of Play 2.1-SNAPSHOT,
  // but dependencies downloaded at different points in time (the more recent
  // dependencies included a newer version of Google Guava.)))
  def listJars = TaskKey[Unit]("list-jars")
  def listJarsTask = listJars <<= (target, fullClasspath in Runtime) map {
        (target, cp) =>
    println("Target path is: "+target)
    println("Full classpath is: "+cp.map(_.data).mkString(":"))
  }


  // Show unchecked and deprecated warnings, in this project and all
  // its modules.
  // scalacOptions in ThisBuild ++= Seq("-deprecation")

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
