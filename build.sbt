val catsVersion = "2.1.1"
val minitestVersion = "2.7.0"
val processingVersion = "3.3.7"
val scalacheckVersion = "1.14.3"
val spireVersion = "0.17.0-M1"
val algebraVersion = "2.0.1"

val libraries = new {
  val scalacheck = "org.scalacheck" %% "scalacheck" % scalacheckVersion
  val spire = "org.typelevel" %% "spire" % spireVersion
  val `spire-laws` = "org.typelevel" %% "spire-laws" % spireVersion
  val `algebra-laws` = "org.typelevel" %% "algebra-laws" % algebraVersion
}

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

lazy val `kernel-laws` = project
  .settings(sharedSettings)
  .dependsOn(kernel)
  .settings(
    libraryDependencies += libraries.scalacheck.withDottyCompat(scalaVersion.value),
    libraryDependencies += libraries.spire.withDottyCompat(scalaVersion.value),
    libraryDependencies += libraries.`algebra-laws`.withDottyCompat(scalaVersion.value),
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
  .dependsOn(`kernel-laws` % Test)
  .settings(
    moduleName := "coprocessing",
    libraryDependencies += ("org.typelevel" %% "cats-core" % catsVersion % Test).withDottyCompat(scalaVersion.value),
    libraryDependencies += ("org.typelevel" %% "cats-laws" % catsVersion % Test).withDottyCompat(scalaVersion.value),
    libraryDependencies += ("io.monix" %% "minitest-laws" % minitestVersion % Test).withDottyCompat(scalaVersion.value),
    libraryDependencies += (libraries.`spire-laws` % Test).withDottyCompat(scalaVersion.value),
    testFrameworks += new TestFramework("minitest.runner.Framework")
  )

lazy val demo = project
  .settings(sharedSettings)
  .dependsOn(root, p3backend)
