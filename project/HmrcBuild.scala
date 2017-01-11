
import play.core.PlayVersion
import sbt.Keys._
import sbt._
import uk.gov.hmrc.SbtAutoBuildPlugin
import uk.gov.hmrc.versioning.SbtGitVersioning

object HmrcBuild extends Build {

  import uk.gov.hmrc.DefaultBuildSettings._

  val appName = "txm-events"

  lazy val txmEvents = (project in file("."))
    .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)
    .settings(
      name := appName,
      targetJvm := "jvm-1.8",
      scalaVersion := "2.11.8",
      libraryDependencies ++= Seq(
        "javax.inject" % "javax.inject" % "1",
        "uk.gov.hmrc" %% "play-events" % "1.2.0",
        "de.threedimensions" %% "metrics-play" % "2.5.13",
        "uk.gov.hmrc" %% "http-verbs" % "6.2.0" % "provided",
        "uk.gov.hmrc" %% "play-auditing" % "2.4.0" % "provided",
        "uk.gov.hmrc" %% "play-config" % "3.0.0",
        "com.typesafe.play" %% "play" % PlayVersion.current,
        "org.scalatest" %% "scalatest" % "3.0.1" % "test",
        "org.pegdown" % "pegdown" % "1.5.0" % "test",
        "com.typesafe.play" %% "play-test" % PlayVersion.current % "test"
      ),
      Developers()
    )
}

object Developers {

  def apply() = developers := List[Developer]()
}