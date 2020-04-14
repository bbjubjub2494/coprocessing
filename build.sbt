val catsVersion = "2.1.1"
val minitestVersion = "2.7.0"
val processingVersion = "3.3.7"

lazy val sharedSettings = Seq(
  turbo := true,
  useSuperShell := false,
  scalaVersion := "0.23.0-RC1",
  scalacOptions ++= Seq(
    "-Yexplicit-nulls",
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked"
  ),
)

lazy val kernel = project
  .settings(sharedSettings)
  .settings(
    libraryDependencies += ("org.typelevel" %% "cats-kernel" % catsVersion).withDottyCompat(scalaVersion.value),
  )

lazy val core = project
  .settings(sharedSettings)
  .dependsOn(kernel)
  .settings(
    libraryDependencies += ("org.typelevel" %% "cats-core" % catsVersion).withDottyCompat(scalaVersion.value),
  )

lazy val p3backend = project
  .settings(sharedSettings)
  .dependsOn(core)
  .settings(
    libraryDependencies += "org.processing" % "core" % processingVersion,
  )

lazy val root = (project in file("."))
  .settings(sharedSettings)
  .dependsOn(core)
  .settings(
    moduleName := "coprocessing",
    libraryDependencies += ("org.typelevel" %% "cats-core" % catsVersion % Test).withDottyCompat(scalaVersion.value),
    libraryDependencies += ("org.typelevel" %% "cats-laws" % catsVersion % Test).withDottyCompat(scalaVersion.value),
    libraryDependencies += ("io.monix" %% "minitest-laws" % minitestVersion % Test).withDottyCompat(scalaVersion.value),
    testFrameworks += new TestFramework("minitest.runner.Framework")
  )

lazy val demo = project
  .settings(sharedSettings)
  .dependsOn(root, p3backend)
