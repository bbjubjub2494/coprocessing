package dotty.tools.vulpix

import java.io.{File => JFile}

final case class TestFlags(
  defaultClassPath: String,
  runClassPath: String, // class path that is used when running `run` tests (not compiling)
  options: Array[String]) {

  def and(flags: String*): TestFlags =
    TestFlags(defaultClassPath, runClassPath, options ++ flags)

  def without(flags: String*): TestFlags =
    TestFlags(defaultClassPath, runClassPath, options diff flags)

  def withClasspath(classPath: String): TestFlags =
    TestFlags(s"$defaultClassPath${JFile.pathSeparator}$classPath", runClassPath, options)

  def withRunClasspath(classPath: String): TestFlags =
    TestFlags(defaultClassPath, s"$runClassPath${JFile.pathSeparator}$classPath", options)

  def all: Array[String] = Array("-classpath", defaultClassPath) ++ options

  def withoutLanguageFeatures: TestFlags = copy(options = withoutLanguageFeaturesOptions)

  private val languageFeatureFlag = "-language:"
  private def withoutLanguageFeaturesOptions = options.filterNot(_.startsWith(languageFeatureFlag))

  // TODO simplify to add `-language:feature` to `options` once
  //      https://github.com/lampepfl/dotty-feature-requests/issues/107 is implemented
  def andLanguageFeature(feature: String) = {
    val (languageFeatures, rest) = options.partition(_.startsWith(languageFeatureFlag))
    val existingFeatures = if (languageFeatures.isEmpty) languageFeatures.mkString(",") + "," else ""
    copy(options = rest ++ Array(languageFeatureFlag + existingFeatures + feature))
  }

  def withoutLanguageFeature(feature: String) = {
    val (languageFeatures, rest) = options.partition(_.startsWith(languageFeatureFlag))
    val filteredFeatures = languageFeatures.filter(_ == feature)
    val newOptions =
      if (filteredFeatures.isEmpty) rest
      else rest ++ Array(languageFeatureFlag + filteredFeatures.mkString(","))

    copy(options = newOptions)
  }

  /** Subset of the flags that should be passed to javac. */
  def javacFlags: Array[String] = {
    val flags = all
    val cp = flags.dropWhile(_ != "-classpath").take(2)
    val output = flags.dropWhile(_ != "-d").take(2)
    cp ++ output
  }
}

object TestFlags {
  def apply(classPath: String, flags: Array[String]): TestFlags = TestFlags(classPath, classPath, flags)
}
