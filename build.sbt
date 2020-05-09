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

lazy val primitives = project
  .settings(sharedSettings)
  .settings(
    moduleName := "coprocessing-primitives",
  )

lazy val `primitives-laws` = project
  .settings(sharedSettings)
  .dependsOn(primitives)
  .settings(
    moduleName := "coprocessing-primitives-laws",
    libraryDependencies ++= Seq(
      `spire`.withDottyCompat(dottyVersion),
      `algebra-laws`.withDottyCompat(dottyVersion),
    ),
  )

lazy val core = project
  .settings(sharedSettings)
  .dependsOn(primitives)
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
  .dependsOn(`primitives-laws` % Test)
  .settings(
    name := "Coprocessing",
    moduleName := "coprocessing",
    libraryDependencies ++= Seq(
      (`cats-core` % Test).withDottyCompat(dottyVersion),
      (`cats-laws` % Test).withDottyCompat(dottyVersion),
      (`munit` % Test).withDottyCompat(dottyVersion),
      (`discipline-munit` % Test).withDottyCompat(dottyVersion),
      (`spire-laws` % Test).withDottyCompat(dottyVersion),
    ),
    testFrameworks += new TestFramework("munit.Framework"),
  )

lazy val demo = project
  .settings(sharedSettings)
  .dependsOn(root, p3backend)
