val catsVersion = "2.1.1"

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

lazy val core = project
  .settings(sharedSettings)
  .dependsOn(kernel)
  .settings(
    libraryDependencies += ("org.typelevel" %% "cats-core" % catsVersion).withDottyCompat(scalaVersion.value),
  )
