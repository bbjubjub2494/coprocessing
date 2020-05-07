homepage := Some(url("https://coprocessing.bettens.info"))
licenses := Seq(
  "LGPL-3.0" -> url("https://opensource.org/licenses/LGPL-3.0"),
)

import Libraries._

val dottyVersion = "0.23.0"

lazy val sharedSettings = Seq(
  turbo := true,
  useSuperShell := false,
  scalaVersion := dottyVersion,
  scalacOptions ++= Seq(
    "-Yexplicit-nulls",
    "-deprecation",
    "-encoding", "UTF-8",
  ),
)

lazy val kernel = project
  .settings(sharedSettings)
  .settings(
    moduleName := "coprocessing-kernel",
    libraryDependencies ++= Seq(
      `cats-kernel`.withDottyCompat(dottyVersion),
    ),
  )

lazy val `kernel-laws` = project
  .settings(sharedSettings)
  .dependsOn(kernel)
  .settings(
    moduleName := "coprocessing-kernel-laws",
    libraryDependencies ++= Seq(
      `scalacheck`.withDottyCompat(dottyVersion),
      `spire`.withDottyCompat(dottyVersion),
      `algebra-laws`.withDottyCompat(dottyVersion),
    ),
  )

lazy val core = project
  .settings(sharedSettings)
  .dependsOn(kernel)
  .settings(
    moduleName := "coprocessing-core",
    libraryDependencies ++= Seq(
      `cats-core`.withDottyCompat(dottyVersion),
    ),
  )

lazy val p3backend = project
  .settings(sharedSettings)
  .dependsOn(core)
  .settings(
    moduleName := "coprocessing-p3backend",
    libraryDependencies ++= Seq(
      `processing-core`,
    ),
  )

lazy val root = (project in file("."))
  .settings(sharedSettings)
  .dependsOn(core)
  .dependsOn(`kernel-laws` % Test)
  .settings(
    name := "Coprocessing",
    moduleName := "coprocessing",
    libraryDependencies ++= Seq(
      (`cats-core` % Test).withDottyCompat(dottyVersion),
      (`cats-laws` % Test).withDottyCompat(dottyVersion),
      (`minitest-laws` % Test).withDottyCompat(dottyVersion),
      (`spire-laws` % Test).withDottyCompat(dottyVersion),
    ),
    testFrameworks += new TestFramework("minitest.runner.Framework"),
  )

lazy val demo = project
  .settings(sharedSettings)
  .dependsOn(root, p3backend)
