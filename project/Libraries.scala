import sbt._

object Libraries {
  val algebraVersion = "2.0.1"
  val catsVersion = "2.1.1"
  val disciplineMunitVersion = "0.2.0"
  val munitVersion = "0.7.5"
  val processingVersion = "3.3.7"
  val scalacheckVersion = "1.14.3"
  val spireVersion = "0.17.0-M1"

  val `algebra-laws` = "org.typelevel" %% "algebra-laws" % algebraVersion
  val `cats-core` = "org.typelevel" %% "cats-core" % catsVersion
  val `cats-kernel` = "org.typelevel" %% "cats-kernel" % catsVersion
  val `cats-laws` = "org.typelevel" %% "cats-laws" % catsVersion
  val `discipline-munit` = "org.typelevel" %% "discipline-munit" % disciplineMunitVersion
  val `munit` = "org.scalameta" %% "munit" % munitVersion
  val `processing-core` = "org.processing" % "core" % processingVersion
  val `scalacheck` = "org.scalacheck" %% "scalacheck" % scalacheckVersion
  val `spire` = "org.typelevel" %% "spire" % spireVersion
  val `spire-laws` = "org.typelevel" %% "spire-laws" % spireVersion
}
