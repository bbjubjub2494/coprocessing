import java.io.File
import java.nio.file._

import Modes._
import com.jsuereth.sbtpgp.PgpKeys
import sbt.Keys._
import sbt._
import complete.DefaultParsers._
import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh
import sbt.Package.ManifestAttributes
import sbt.plugins.SbtPlugin
import sbt.ScriptedPlugin.autoImport._
import xerial.sbt.pack.PackPlugin
import xerial.sbt.pack.PackPlugin.autoImport._
import xerial.sbt.Sonatype.autoImport._

import dotty.tools.sbtplugin.DottyPlugin.autoImport._
import dotty.tools.sbtplugin.DottyPlugin.makeScalaInstance
import dotty.tools.sbtplugin.DottyIDEPlugin.{ installCodeExtension, prepareCommand, runProcess }
import dotty.tools.sbtplugin.DottyIDEPlugin.autoImport._

import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport._

import scala.util.Properties.isJavaAtLeast

object MyScalaJSPlugin extends AutoPlugin {
  import Build._

  override def requires: Plugins = ScalaJSPlugin

  override def projectSettings: Seq[Setting[_]] = Def.settings(
    commonBootstrappedSettings,

    /* Remove the Scala.js compiler plugin for scalac, and enable the
     * Scala.js back-end of dotty instead.
     */
    libraryDependencies := {
      val deps = libraryDependencies.value
      deps.filterNot(_.name.startsWith("scalajs-compiler")).map(_.withDottyCompat(scalaVersion.value))
    },
    scalacOptions += "-scalajs",

    // Replace the JVM JUnit dependency by the Scala.js one
    libraryDependencies ~= {
      _.filter(!_.name.startsWith("junit-interface"))
    },
    libraryDependencies +=
      ("org.scala-js" %% "scalajs-junit-test-runtime" % scalaJSVersion  % "test").withDottyCompat(scalaVersion.value),

    // Typecheck the Scala.js IR found on the classpath
    scalaJSLinkerConfig ~= (_.withCheckIR(true)),

    // Exclude all these projects from `configureIDE/launchIDE` since they
    // take time to compile, print a bunch of warnings, and are rarely edited.
    excludeFromIDE := true
  )
}

object Build {
  val referenceVersion = "0.24.0-bin-20200407-2352d90-NIGHTLY"

  val baseVersion = "0.24.0"
  val baseSbtDottyVersion = "0.4.2"

  // Versions used by the vscode extension to create a new project
  // This should be the latest published releases.
  // TODO: Have the vscode extension fetch these numbers from the Internet
  // instead of hardcoding them ?
  val publishedDottyVersion = referenceVersion
  val publishedSbtDottyVersion = "0.3.4"

  /** scala-library version required to compile Dotty.
   *
   *  Both the non-bootstrapped and bootstrapped version should match, unless
   *  we're in the process of upgrading to a new major version of
   *  scala-library.
   */
  def stdlibVersion(implicit mode: Mode): String = mode match {
    case NonBootstrapped => "2.13.1"
    case Bootstrapped => "2.13.1"
  }

  val dottyOrganization = "ch.epfl.lamp"
  val dottyGithubUrl = "https://github.com/lampepfl/dotty"


  val isRelease = sys.env.get("RELEASEBUILD") == Some("yes")

  val dottyVersion = {
    def isNightly = sys.env.get("NIGHTLYBUILD") == Some("yes")
    if (isRelease)
      baseVersion
    else if (isNightly)
      baseVersion + "-bin-" + VersionUtil.commitDate + "-" + VersionUtil.gitHash + "-NIGHTLY"
    else
      baseVersion + "-bin-SNAPSHOT"
  }
  val dottyNonBootstrappedVersion = dottyVersion + "-nonbootstrapped"

  val sbtDottyName = "sbt-dotty"
  val sbtDottyVersion = {
    if (isRelease) baseSbtDottyVersion else baseSbtDottyVersion + "-SNAPSHOT"
  }

  val agentOptions = List(
    // "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
    // "-agentpath:/home/dark/opt/yjp-2013-build-13072/bin/linux-x86-64/libyjpagent.so"
    // "-agentpath:/Applications/YourKit_Java_Profiler_2015_build_15052.app/Contents/Resources/bin/mac/libyjpagent.jnilib",
    // "-XX:+HeapDumpOnOutOfMemoryError", "-Xmx1g", "-Xss2m"
  )

  // Packages all subprojects to their jars
  val packageAll = taskKey[Map[String, String]]("Package everything needed to run tests")

  // Run tests with filter through vulpix test suite
  val testCompilation = inputKey[Unit]("runs integration test with the supplied filter")

  // Spawns a repl with the correct classpath
  val repl = inputKey[Unit]("run the REPL with correct classpath")

  // Used to compile files similar to ./bin/dotc script
  val dotc = inputKey[Unit]("run the compiler using the correct classpath, or the user supplied classpath")

  // Used to run binaries similar to ./bin/dotr script
  val dotr = inputKey[Unit]("run compiled binary using the correct classpath, or the user supplied classpath")

  // Compiles the documentation and static site
  val genDocs = inputKey[Unit]("run dottydoc to generate static documentation site")

  // Shorthand for compiling a docs site
  val dottydoc = inputKey[Unit]("run dottydoc")

  // Only available in vscode-dotty
  val unpublish = taskKey[Unit]("Unpublish a package")

  // Settings used to configure the test language server
  val ideTestsCompilerVersion = taskKey[String]("Compiler version to use in IDE tests")
  val ideTestsCompilerArguments = taskKey[Seq[String]]("Compiler arguments to use in IDE tests")
  val ideTestsDependencyClasspath = taskKey[Seq[File]]("Dependency classpath to use in IDE tests")

  val fetchScalaJSSource = taskKey[File]("Fetch the sources of Scala.js")

  lazy val SourceDeps = config("sourcedeps")

  // Settings shared by the build (scoped in ThisBuild). Used in build.sbt
  lazy val thisBuildSettings = Def.settings(
    organization := dottyOrganization,
    organizationName := "LAMP/EPFL",
    organizationHomepage := Some(url("http://lamp.epfl.ch")),

    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-Xfatal-warnings",
      "-encoding", "UTF8",
      "-language:existentials,higherKinds,implicitConversions,postfixOps"
    ),

    javacOptions in (Compile, compile) ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),

    // Override `runCode` from sbt-dotty to use the language-server and
    // vscode extension from the source repository of dotty instead of a
    // published version.
    runCode := (run in `dotty-language-server`).toTask("").value,

    // Avoid various sbt craziness involving classloaders and parallelism
    fork in run := true,
    fork in Test := true,
    parallelExecution in Test := false,

    // enable verbose exception messages for JUnit
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit, "-a", "-v"),
  )

  // Settings shared globally (scoped in Global). Used in build.sbt
  lazy val globalSettings = Def.settings(
    onLoad := (onLoad in Global).value andThen { state =>
      def exists(submodule: String) = {
        val path = Paths.get(submodule)
        Files.exists(path) && {
          val fileStream = Files.list(path)
          try fileStream.iterator().hasNext
          finally fileStream.close()
        }
      }

      // Copy default configuration from .vscode-template/ unless configuration files already exist in .vscode/
      sbt.IO.copyDirectory(new File(".vscode-template/"), new File(".vscode/"), overwrite = false)

      state
    },

    // I find supershell more distracting than helpful
    useSuperShell := false,

    // Credentials to release to Sonatype
    credentials ++= (
      for {
        username <- sys.env.get("SONATYPE_USER")
        password <- sys.env.get("SONATYPE_PW")
      } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)
    ).toList,
    PgpKeys.pgpPassphrase := sys.env.get("PGP_PW").map(_.toCharArray()),
    PgpKeys.useGpgPinentry := true,

    javaOptions ++= {
      val ciOptions = // propagate if this is a CI build
        sys.props.get("dotty.drone.mem") match {
          case Some(prop) => List("-Xmx" + prop)
          case _ => List()
        }
      agentOptions ::: ciOptions
    }
  )

  lazy val commonSettings = publishSettings ++ Seq(
    scalaSource       in Compile    := baseDirectory.value / "src",
    scalaSource       in Test       := baseDirectory.value / "test",
    javaSource        in Compile    := baseDirectory.value / "src",
    javaSource        in Test       := baseDirectory.value / "test",
    resourceDirectory in Compile    := baseDirectory.value / "resources",
    resourceDirectory in Test       := baseDirectory.value / "test-resources",

    // Disable scaladoc generation, it's way too slow and we'll replace it
    // by dottydoc anyway. We still publish an empty -javadoc.jar to make
    // sonatype happy.
    sources in (Compile, doc) := Seq(),

    // Prevent sbt from rewriting our dependencies
    scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(false))),

    libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test,

    // If someone puts a source file at the root (e.g., for manual testing),
    // don't pick it up as part of any project.
    sourcesInBase := false,
  )

  // Settings used for projects compiled only with Java
  lazy val commonJavaSettings = commonSettings ++ Seq(
    version := dottyVersion,
    scalaVersion := referenceVersion,
    // Do not append Scala versions to the generated artifacts
    crossPaths := false,
    // Do not depend on the Scala library
    autoScalaLibrary := false,
    excludeFromIDE := true
  )

  // Settings used when compiling dotty (both non-boostrapped and bootstrapped)
  lazy val commonDottySettings = commonSettings ++ Seq(
    // Manually set the standard library to use
    autoScalaLibrary := false
  )

  lazy val commonScala2Settings = commonSettings ++ Seq(
    scalaVersion := stdlibVersion(Bootstrapped),
    moduleName ~= { _.stripSuffix("-scala2") },
    version := dottyVersion,
    target := baseDirectory.value / ".." / "out" / "scala-2" / name.value,
  )

  // Settings used when compiling dotty with the reference compiler
  lazy val commonNonBootstrappedSettings = commonDottySettings ++ Seq(
    unmanagedSourceDirectories in Compile += baseDirectory.value / "src-non-bootstrapped",

    version := dottyNonBootstrappedVersion,
    scalaVersion := referenceVersion,
    excludeFromIDE := true,
  )

  // Settings used when compiling dotty with a non-bootstrapped dotty
  lazy val commonBootstrappedSettings = commonDottySettings ++ Seq(
    unmanagedSourceDirectories in Compile += baseDirectory.value / "src-bootstrapped",

    version := dottyVersion,
    scalaVersion := dottyNonBootstrappedVersion,

    scalaCompilerBridgeBinaryJar := {
      Some((packageBin in (`dotty-sbt-bridge`, Compile)).value)
    },

    // Use the same name as the non-bootstrapped projects for the artifacts
    moduleName ~= { _.stripSuffix("-bootstrapped") },

    // Enforce that the only Scala 2 classfiles we unpickle come from scala-library
    /*
    scalacOptions ++= {
      val cp = (dependencyClasspath in `dotty-library` in Compile).value
      val scalaLib = findArtifactPath(cp, "scala-library")
      Seq("-Yscala2-unpickler", scalaLib)
    },
    */

    // sbt gets very unhappy if two projects use the same target
    target := baseDirectory.value / ".." / "out" / "bootstrap" / name.value,

    // Compile using the non-bootstrapped and non-published dotty
    managedScalaInstance := false,
    scalaInstance := {
      val externalNonBootstrappedDeps = externalDependencyClasspath.in(`dotty-doc`, Compile).value
      val scalaLibrary = findArtifact(externalNonBootstrappedDeps, "scala-library")

      // IMPORTANT: We need to use actual jars to form the ScalaInstance and not
      // just directories containing classfiles because sbt maintains a cache of
      // compiler instances. This cache is invalidated based on timestamps
      // however this is only implemented on jars, directories are never
      // invalidated.
      val tastyCore = packageBin.in(`tasty-core`, Compile).value
      val dottyLibrary = packageBin.in(`dotty-library`, Compile).value
      val dottyInterfaces = packageBin.in(`dotty-interfaces`, Compile).value
      val dottyCompiler = packageBin.in(`dotty-compiler`, Compile).value
      val dottyDoc = packageBin.in(`dotty-doc`, Compile).value

      val allJars = Seq(tastyCore, dottyLibrary, dottyInterfaces, dottyCompiler, dottyDoc) ++ externalNonBootstrappedDeps.map(_.data)

      makeScalaInstance(
        state.value,
        scalaVersion.value,
        scalaLibrary,
        dottyLibrary,
        dottyCompiler,
        allJars
      )
    },
    // sbt-dotty defines `scalaInstance in doc` so we need to override it manually
    scalaInstance in doc := scalaInstance.value,
  )

  lazy val commonBenchmarkSettings = Seq(
    outputStrategy := Some(StdoutOutput),
    mainClass in (Jmh, run) := Some("dotty.tools.benchmarks.Bench"), // custom main for jmh:run
    javaOptions += "-DBENCH_COMPILER_CLASS_PATH=" + Attributed.data((fullClasspath in (`dotty-bootstrapped`, Compile)).value).mkString("", File.pathSeparator, ""),
    javaOptions += "-DBENCH_CLASS_PATH=" + Attributed.data((fullClasspath in (`dotty-library-bootstrapped`, Compile)).value).mkString("", File.pathSeparator, "")
  )

  // sbt >= 0.13.12 will automatically rewrite transitive dependencies on
  // any version in any organization of scala{-library,-compiler,-reflect,p}
  // to have organization `scalaOrganization` and version `scalaVersion`
  // (see https://github.com/sbt/sbt/pull/2634).
  // This means that we need to provide dummy artefacts for these projects,
  // otherwise users will get compilation errors if they happen to transitively
  // depend on one of these projects.
  lazy val commonDummySettings = commonBootstrappedSettings ++ Seq(
    crossPaths := false,
    libraryDependencies := Seq()
  )

  /** Projects -------------------------------------------------------------- */

  val dottyCompilerBootstrappedRef = LocalProject("dotty-compiler-bootstrapped")

  /** External dependencies we may want to put on the compiler classpath. */
  def externalCompilerClasspathTask: Def.Initialize[Task[Def.Classpath]] =
    // Even if we're running the non-bootstrapped compiler, we want the
    // dependencies of the bootstrapped compiler since we want to put them on
    // the compiler classpath, not the JVM classpath.
    externalDependencyClasspath.in(dottyCompilerBootstrappedRef, Runtime)

  // The root project:
  // - aggregates other projects so that "compile", "test", etc are run on all projects at once.
  // - publishes its own empty artifact "dotty" that depends on "dotty-library" and "dotty-compiler",
  //   this is only necessary for compatibility with sbt which currently hardcodes the "dotty" artifact name
  lazy val dotty = project.in(file(".")).asDottyRoot(NonBootstrapped)
  lazy val `dotty-bootstrapped` = project.asDottyRoot(Bootstrapped)

  lazy val `dotty-interfaces` = project.in(file("interfaces")).
    settings(commonJavaSettings)

  private lazy val dottydocClasspath = Def.task {
    val jars = (packageAll in `dotty-compiler`).value
    val dottyLib = jars("dotty-library")
    val otherDeps = (dependencyClasspath in Compile).value.map(_.data).mkString(File.pathSeparator)
    val externalDeps = externalCompilerClasspathTask.value
    dottyLib + File.pathSeparator + findArtifactPath(externalDeps, "scala-library")
  }

  lazy val commonDocSettings = Seq(
    baseDirectory in (Compile, run) := baseDirectory.value / "..",
    baseDirectory in Test := baseDirectory.value / "..",
    libraryDependencies ++= {
      val flexmarkVersion = "0.42.12"
      Seq(
        "com.vladsch.flexmark" % "flexmark" % flexmarkVersion,
        "com.vladsch.flexmark" % "flexmark-ext-gfm-tasklist" % flexmarkVersion,
        "com.vladsch.flexmark" % "flexmark-ext-gfm-tables" % flexmarkVersion,
        "com.vladsch.flexmark" % "flexmark-ext-autolink" % flexmarkVersion,
        "com.vladsch.flexmark" % "flexmark-ext-anchorlink" % flexmarkVersion,
        "com.vladsch.flexmark" % "flexmark-ext-emoji" % flexmarkVersion,
        "com.vladsch.flexmark" % "flexmark-ext-gfm-strikethrough" % flexmarkVersion,
        "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % flexmarkVersion,
        Dependencies.`jackson-dataformat-yaml`,
        "nl.big-o" % "liqp" % "0.6.7"
      )
    }
  )

  def dottyDocSettings(implicit mode: Mode) = Seq(
    connectInput in run := true,
    outputStrategy := Some(StdoutOutput),

    javaOptions ++= (javaOptions in `dotty-compiler`).value,

    javaOptions += "-Xss3m",

    genDocs := Def.inputTaskDyn {
      val dottydocExtraArgs = spaceDelimited("<arg>").parsed

      // Make majorVersion available at dotty.epfl.ch/versions/latest-nightly-base
      // Used by sbt-dotty to resolve the latest nightly
      val majorVersion = baseVersion.take(baseVersion.lastIndexOf('.'))
      IO.write(file("./docs/_site/versions/latest-nightly-base"), majorVersion)

      // This file is used by GitHub Pages when the page is available in a custom domain
      IO.write(file("./docs/_site/CNAME"), "dotty.epfl.ch")

      val sources = unmanagedSources.in(dottyLibrary, Compile).value
      val args = Seq(
        "-siteroot", "docs",
        "-project", "Dotty",
        "-project-version", dottyVersion,
        "-project-url", dottyGithubUrl,
        "-project-logo", "dotty-logo.svg",
        "-classpath", dottydocClasspath.value,
        "-Yerased-terms"
      ) ++ dottydocExtraArgs
      (runMain in Compile).toTask(
        s""" dotty.tools.dottydoc.Main ${args.mkString(" ")} ${sources.mkString(" ")}"""
      )
    }.evaluated,

    dottydoc := Def.inputTaskDyn {
      val args = spaceDelimited("<arg>").parsed
      val cp = dottydocClasspath.value

      (runMain in Compile).toTask(s" dotty.tools.dottydoc.Main -classpath $cp " + args.mkString(" "))
    }.evaluated,
  )

  lazy val `dotty-doc` = project.in(file("doc-tool")).asDottyDoc(NonBootstrapped)
  lazy val `dotty-doc-bootstrapped` = project.in(file("doc-tool")).asDottyDoc(Bootstrapped)

  def dottyDoc(implicit mode: Mode): Project = mode match {
    case NonBootstrapped => `dotty-doc`
    case Bootstrapped => `dotty-doc-bootstrapped`
  }

  /** Find an artifact with the given `name` in `classpath` */
  def findArtifact(classpath: Def.Classpath, name: String): File = classpath
    .find(_.get(artifact.key).exists(_.name == name))
    .getOrElse(throw new MessageOnlyException(s"Artifact for $name not found in $classpath"))
    .data

  /** Like `findArtifact` but returns the absolute path of the entry as a string */
  def findArtifactPath(classpath: Def.Classpath, name: String): String =
    findArtifact(classpath, name).getAbsolutePath

  // Settings shared between dotty-compiler and dotty-compiler-bootstrapped
  lazy val commonDottyCompilerSettings = Seq(
      // set system in/out for repl
      connectInput in run := true,
      outputStrategy := Some(StdoutOutput),

      // Generate compiler.properties, used by sbt
      resourceGenerators in Compile += Def.task {
        import java.util._
        import java.text._
        val file = (resourceManaged in Compile).value / "compiler.properties"
        val dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"))
        val contents =                //2.11.11.v20170413-090219-8a413ba7cc
          s"""version.number=${version.value}
             |maven.version.number=${version.value}
             |git.hash=${VersionUtil.gitHash}
             |copyright.string=Copyright 2002-${Calendar.getInstance().get(Calendar.YEAR)}, LAMP/EPFL
           """.stripMargin

        if (!(file.exists && IO.read(file) == contents)) {
          IO.write(file, contents)
        }

        Seq(file)
      }.taskValue,

      // get libraries onboard
      libraryDependencies ++= Seq(
        "org.scala-lang.modules" % "scala-asm" % "7.3.1-scala-1", // used by the backend
        Dependencies.`compiler-interface`,
        "org.jline" % "jline-reader" % "3.9.0",   // used by the REPL
        "org.jline" % "jline-terminal" % "3.9.0",
        "org.jline" % "jline-terminal-jna" % "3.9.0" // needed for Windows
      ),

      // For convenience, change the baseDirectory when running the compiler
      baseDirectory in (Compile, run) := baseDirectory.value / "..",
      // And when running the tests
      baseDirectory in Test := baseDirectory.value / "..",

      test in Test := {
        // Exclude VulpixMetaTests
        (testOnly in Test).toTask(" -- --exclude-categories=dotty.VulpixMetaTests").value
      },

      testOptions in Test += Tests.Argument(
        TestFrameworks.JUnit,
        "--run-listener=dotty.tools.ContextEscapeDetector",
      ),

      // Spawn new JVM in run and test

      // Add git-hash used to package the distribution to the manifest to know it in runtime and report it in REPL
      packageOptions += ManifestAttributes(("Git-Hash", VersionUtil.gitHash)),

      javaOptions ++= {
        val managedSrcDir = {
          // Populate the directory
          (managedSources in Compile).value

          (sourceManaged in Compile).value
        }
        val externalDeps = externalCompilerClasspathTask.value
        val jars = packageAll.value

        Seq(
          "-Ddotty.tests.dottyCompilerManagedSources=" + managedSrcDir,
          "-Ddotty.tests.classes.dottyInterfaces=" + jars("dotty-interfaces"),
          "-Ddotty.tests.classes.dottyLibrary=" + jars("dotty-library"),
          "-Ddotty.tests.classes.dottyCompiler=" + jars("dotty-compiler"),
          "-Ddotty.tests.classes.tastyCore=" + jars("tasty-core"),
          "-Ddotty.tests.classes.compilerInterface=" + findArtifactPath(externalDeps, "compiler-interface"),
          "-Ddotty.tests.classes.scalaLibrary=" + findArtifactPath(externalDeps, "scala-library"),
          "-Ddotty.tests.classes.scalaAsm=" + findArtifactPath(externalDeps, "scala-asm"),
          "-Ddotty.tests.classes.jlineTerminal=" + findArtifactPath(externalDeps, "jline-terminal"),
          "-Ddotty.tests.classes.jlineReader=" + findArtifactPath(externalDeps, "jline-reader"),
        )
      },

      javaOptions += (
        s"-Ddotty.tools.dotc.semanticdb.test=${(ThisBuild / baseDirectory).value/"tests"/"semanticdb"}"
      ),

      testCompilation := Def.inputTaskDyn {
        val args = spaceDelimited("<arg>").parsed
        if (args.contains("--help")) {
          println(
            s"""
               |usage: testCompilation [--help] [--from-tasty] [--update-checkfiles] [<filter>]
               |
               |By default runs tests in dotty.tools.dotc.*CompilationTests excluding tests tagged with dotty.SlowTests.
               |
               |  --help                show this message
               |  --from-tasty          runs tests in dotty.tools.dotc.FromTastyTests
               |  --update-checkfiles   override the checkfiles that did not match with the current output
               |  <filter>              substring of the path of the tests file
               |
             """.stripMargin
          )
          (testOnly in Test).toTask(" not.a.test")
        }
        else {
          val updateCheckfile = args.contains("--update-checkfiles")
          val fromTasty = args.contains("--from-tasty")
          val args1 = if (updateCheckfile | fromTasty) args.filter(x => x != "--update-checkfiles" && x != "--from-tasty") else args
          val test = if (fromTasty) "dotty.tools.dotc.FromTastyTests" else "dotty.tools.dotc.*CompilationTests"
          val cmd = s" $test -- --exclude-categories=dotty.SlowTests" +
            (if (updateCheckfile) " -Ddotty.tests.updateCheckfiles=TRUE" else "") +
            (if (args1.nonEmpty) " -Ddotty.tests.filter=" + args1.mkString(" ") else "")
          (testOnly in Test).toTask(cmd)
        }
      }.evaluated,

      dotr := {
        val args: List[String] = spaceDelimited("<arg>").parsed.toList
        val externalDeps = externalCompilerClasspathTask.value
        val jars = packageAll.value

        val scalaLib = findArtifactPath(externalDeps, "scala-library")
        val dottyLib = jars("dotty-library")

        def run(args: List[String]): Unit = {
          val fullArgs = insertClasspathInArgs(args, List(".", dottyLib, scalaLib).mkString(File.pathSeparator))
          runProcess("java" :: fullArgs, wait = true)
        }

        if (args.isEmpty) {
          println("Couldn't run `dotr` without args. Use `repl` to run the repl or add args to run the dotty application")
        } else if (scalaLib == "") {
          println("Couldn't find scala-library on classpath, please run using script in bin dir instead")
        } else if (args.contains("-with-compiler")) {
          val args1 = args.filter(_ != "-with-compiler")
          val asm = findArtifactPath(externalDeps, "scala-asm")
          val dottyCompiler = jars("dotty-compiler")
          val dottyStaging = jars("dotty-staging")
          val dottyTastyInspector = jars("dotty-tasty-inspector")
          val dottyInterfaces = jars("dotty-interfaces")
          val tastyCore = jars("tasty-core")
          run(insertClasspathInArgs(args1, List(dottyCompiler, dottyInterfaces, asm, dottyStaging, dottyTastyInspector, tastyCore).mkString(File.pathSeparator)))
        } else run(args)
      },

      run := dotc.evaluated,
      dotc := runCompilerMain().evaluated,
      repl := runCompilerMain(repl = true).evaluated,

      /* Add the sources of scalajs-ir.
       * To guarantee that dotty can bootstrap without depending on a version
       * of scalajs-ir built with a different Scala compiler, we add its
       * sources instead of depending on the binaries.
       */
      ivyConfigurations += SourceDeps.hide,
      transitiveClassifiers := Seq("sources"),
      libraryDependencies +=
        ("org.scala-js" %% "scalajs-ir" % scalaJSVersion % "sourcedeps").withDottyCompat(scalaVersion.value),
      sourceGenerators in Compile += Def.task {
        val s = streams.value
        val cacheDir = s.cacheDirectory
        val trgDir = (sourceManaged in Compile).value / "scalajs-ir-src"

        val report = updateClassifiers.value
        val scalaJSIRSourcesJar = report.select(
            configuration = configurationFilter("sourcedeps"),
            module = (_: ModuleID).name.startsWith("scalajs-ir_"),
            artifact = artifactFilter(`type` = "src")).headOption.getOrElse {
          sys.error(s"Could not fetch scalajs-ir sources")
        }

        FileFunction.cached(cacheDir / s"fetchScalaJSIRSource",
            FilesInfo.lastModified, FilesInfo.exists) { dependencies =>
          s.log.info(s"Unpacking scalajs-ir sources to $trgDir...")
          if (trgDir.exists)
            IO.delete(trgDir)
          IO.createDirectory(trgDir)
          IO.unzip(scalaJSIRSourcesJar, trgDir)

          (trgDir ** "*.scala").get.toSet
        } (Set(scalaJSIRSourcesJar)).toSeq
      }.taskValue,
  )

  def runCompilerMain(repl: Boolean = false) = Def.inputTaskDyn {
    val log = streams.value.log
    val externalDeps = externalCompilerClasspathTask.value
    val jars = packageAll.value
    val scalaLib = findArtifactPath(externalDeps, "scala-library")
    val dottyLib = jars("dotty-library")
    val dottyCompiler = jars("dotty-compiler")
    val args0: List[String] = spaceDelimited("<arg>").parsed.toList
    val decompile = args0.contains("-decompile")
    val printTasty = args0.contains("-print-tasty")
    val debugFromTasty = args0.contains("-Ythrough-tasty")
    val args = args0.filter(arg => arg != "-repl" && arg != "-decompile" &&
        arg != "-with-compiler" && arg != "-Ythrough-tasty")

    val main =
      if (repl) "dotty.tools.repl.Main"
      else if (decompile || printTasty) "dotty.tools.dotc.decompiler.Main"
      else if (debugFromTasty) "dotty.tools.dotc.fromtasty.Debug"
      else "dotty.tools.dotc.Main"

    var extraClasspath = Seq(scalaLib, dottyLib)

    if ((decompile || printTasty) && !args.contains("-classpath"))
      extraClasspath ++= Seq(".")

    if (args0.contains("-with-compiler")) {
      if (scalaVersion.value == referenceVersion) {
        log.error("-with-compiler should only be used with a bootstrapped compiler")
      }
      val dottyInterfaces = jars("dotty-interfaces")
      val dottyStaging = jars("dotty-staging")
      val dottyTastyInspector = jars("dotty-tasty-inspector")
      val tastyCore = jars("tasty-core")
      val asm = findArtifactPath(externalDeps, "scala-asm")
      extraClasspath ++= Seq(dottyCompiler, dottyInterfaces, asm, dottyStaging, dottyTastyInspector, tastyCore)
    }

    val fullArgs = main :: insertClasspathInArgs(args, extraClasspath.mkString(File.pathSeparator))

    (runMain in Compile).toTask(fullArgs.mkString(" ", " ", ""))
  }

  def insertClasspathInArgs(args: List[String], cp: String): List[String] = {
    val (beforeCp, fromCp) = args.span(_ != "-classpath")
    val classpath = fromCp.drop(1).headOption.fold(cp)(_ + File.pathSeparator + cp)
    "-classpath" :: classpath :: beforeCp ::: fromCp.drop(2)
  }

  lazy val nonBootstrapedDottyCompilerSettings = commonDottyCompilerSettings ++ Seq(
    // packageAll packages all and then returns a map with the abs location
    packageAll := Def.taskDyn { // Use a dynamic task to avoid loops when loading the settings
      Def.task {
        Map(
          "dotty-interfaces"    -> packageBin.in(`dotty-interfaces`, Compile).value,
          "dotty-compiler"      -> packageBin.in(Compile).value,
          "tasty-core"          -> packageBin.in(`tasty-core`, Compile).value,

          // NOTE: Using dotty-library-bootstrapped here is intentional: when
          // running the compiler, we should always have the bootstrapped
          // library on the compiler classpath since the non-bootstrapped one
          // may not be binary-compatible.
          "dotty-library"       -> packageBin.in(`dotty-library-bootstrapped`, Compile).value
        ).mapValues(_.getAbsolutePath)
      }
    }.value,

    testOptions in Test += Tests.Argument(
      TestFrameworks.JUnit,
      "--exclude-categories=dotty.BootstrappedOnlyTests",
    ),
    // increase stack size for non-bootstrapped compiler, because some code
    // is only tail-recursive after bootstrap
    javaOptions in Test += "-Xss2m"
  )

  lazy val bootstrapedDottyCompilerSettings = commonDottyCompilerSettings ++ Seq(
    javaOptions ++= {
      val jars = packageAll.value
      Seq(
        "-Ddotty.tests.classes.dottyStaging=" + jars("dotty-staging"),
        "-Ddotty.tests.classes.dottyTastyInspector=" + jars("dotty-tasty-inspector"),
      )
    },
    packageAll := {
      packageAll.in(`dotty-compiler`).value ++ Seq(
        "dotty-compiler" -> packageBin.in(Compile).value.getAbsolutePath,
        "dotty-staging"  -> packageBin.in(LocalProject("dotty-staging"), Compile).value.getAbsolutePath,
        "dotty-tasty-inspector"  -> packageBin.in(LocalProject("dotty-tasty-inspector"), Compile).value.getAbsolutePath,
        "tasty-core"     -> packageBin.in(LocalProject("tasty-core-bootstrapped"), Compile).value.getAbsolutePath,
      )
    }
  )

  def dottyCompilerSettings(implicit mode: Mode): sbt.Def.SettingsDefinition =
    if (mode == NonBootstrapped) nonBootstrapedDottyCompilerSettings else bootstrapedDottyCompilerSettings

  lazy val `dotty-compiler` = project.in(file("compiler")).asDottyCompiler(NonBootstrapped)
  lazy val `dotty-compiler-bootstrapped` = project.in(file("compiler")).asDottyCompiler(Bootstrapped)

  def dottyCompiler(implicit mode: Mode): Project = mode match {
    case NonBootstrapped => `dotty-compiler`
    case Bootstrapped => `dotty-compiler-bootstrapped`
  }

  // Settings shared between dotty-library, dotty-library-bootstrapped and dotty-library-bootstrappedJS
  lazy val dottyLibrarySettings = Seq(
    scalacOptions in Compile ++= Seq(
      // Needed so that the library sources are visible when `dotty.tools.dotc.core.Definitions#init` is called
      "-sourcepath", (sourceDirectories in Compile).value.map(_.getAbsolutePath).distinct.mkString(File.pathSeparator),
     // support declaration of scala.compiletime.erasedValue
      "-Yerased-terms"
    ),
  )

  lazy val `dotty-library` = project.in(file("library")).asDottyLibrary(NonBootstrapped)
  lazy val `dotty-library-bootstrapped`: Project = project.in(file("library")).asDottyLibrary(Bootstrapped)

  def dottyLibrary(implicit mode: Mode): Project = mode match {
    case NonBootstrapped => `dotty-library`
    case Bootstrapped => `dotty-library-bootstrapped`
  }

  /** The dotty standard library compiled with the Scala.js back-end, to produce
   *  the corresponding .sjsir files.
   *
   *  This artifact must be on the classpath on every "Dotty.js" project.
   *
   *  Currently, only a very small fraction of the dotty library is actually
   *  included in this project, and hence available to Dotty.js projects. More
   *  will be added in the future as things are confirmed to be supported.
   */
  lazy val `dotty-library-bootstrappedJS`: Project = project.in(file("library-js")).
    asDottyLibrary(Bootstrapped).
    enablePlugins(MyScalaJSPlugin).
    settings(
      unmanagedSourceDirectories in Compile :=
        (unmanagedSourceDirectories in (`dotty-library-bootstrapped`, Compile)).value,
    )

  lazy val tastyCoreSettings = Seq(
    scalacOptions ~= { old =>
      val (language, other) = old.partition(_.startsWith("-language:"))
      other :+ (language.headOption.map(_ + ",Scala2Compat").getOrElse("-source:3.0-migration"))
    }
  )

  lazy val `tasty-core` = project.in(file("tasty")).asTastyCore(NonBootstrapped)
  lazy val `tasty-core-bootstrapped`: Project = project.in(file("tasty")).asTastyCore(Bootstrapped)
  lazy val `tasty-core-scala2`: Project = project.in(file("tasty")).asTastyCoreScala2

  def tastyCore(implicit mode: Mode): Project = mode match {
    case NonBootstrapped => `tasty-core`
    case Bootstrapped => `tasty-core-bootstrapped`
  }

  lazy val `dotty-staging` = project.in(file("staging")).
    withCommonSettings(Bootstrapped).
    // We want the compiler to be present in the compiler classpath when compiling this project but not
    // when compiling a project that depends on dotty-staging (see sbt-dotty/sbt-test/sbt-dotty/quoted-example-project),
    // but we always need it to be present on the JVM classpath at runtime.
    dependsOn(dottyCompiler(Bootstrapped) % "provided; compile->runtime; test->test").
    settings(commonBootstrappedSettings).
    settings(
      javaOptions := (javaOptions in `dotty-compiler-bootstrapped`).value
    )

  lazy val `dotty-tasty-inspector` = project.in(file("tasty-inspector")).
    withCommonSettings(Bootstrapped).
    // We want the compiler to be present in the compiler classpath when compiling this project but not
    // when compiling a project that depends on dotty-tasty-inspector (see sbt-dotty/sbt-test/sbt-dotty/tasty-inspector-example-project),
    // but we always need it to be present on the JVM classpath at runtime.
    dependsOn(dottyCompiler(Bootstrapped) % "provided; compile->runtime; test->test").
    settings(commonBootstrappedSettings).
    settings(
      javaOptions := (javaOptions in `dotty-compiler-bootstrapped`).value
    )

  lazy val `dotty-sbt-bridge` = project.in(file("sbt-bridge/src")).
    // We cannot depend on any bootstrapped project to compile the bridge, since the
    // bridge is needed to compile these projects.
    dependsOn(dottyDoc(NonBootstrapped) % Provided).
    settings(commonJavaSettings).
    settings(
      description := "sbt compiler bridge for Dotty",

      sources in Test := Seq(),
      scalaSource in Compile := baseDirectory.value,
      javaSource  in Compile := baseDirectory.value,

      // Referring to the other project using a string avoids an infinite loop
      // when sbt reads the settings.
      test in Test := (test in (LocalProject("dotty-sbt-bridge-tests"), Test)).value,

      libraryDependencies += Dependencies.`compiler-interface` % Provided
    )

  // We use a separate project for the bridge tests since they can only be run
  // with the bootstrapped library on the classpath.
  lazy val `dotty-sbt-bridge-tests` = project.in(file("sbt-bridge/test")).
    dependsOn(dottyCompiler(Bootstrapped) % Test).
    settings(commonBootstrappedSettings).
    settings(
      sources in Compile := Seq(),
      scalaSource in Test := baseDirectory.value,
      javaSource  in Test := baseDirectory.value,

      // Tests disabled until zinc-api-info cross-compiles with 2.13,
      // alternatively we could just copy in sources the part of zinc-api-info we need.
      sources in Test := Seq(),
      // libraryDependencies += (Dependencies.`zinc-api-info` % Test).withDottyCompat(scalaVersion.value)
    )

  lazy val `dotty-language-server` = project.in(file("language-server")).
    dependsOn(dottyCompiler(Bootstrapped)).
    settings(commonBootstrappedSettings).
    settings(
      // Sources representing the shared configuration file used to communicate between the sbt-dotty
      // plugin and the language server
      unmanagedSourceDirectories in Compile += baseDirectory.value / "../sbt-dotty/src/dotty/tools/sbtplugin/config",

      libraryDependencies ++= Seq(
        "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.6.0",
        Dependencies.`jackson-databind`
      ),
      // Work around https://github.com/eclipse/lsp4j/issues/295
      dependencyOverrides += "org.eclipse.xtend" % "org.eclipse.xtend.lib" % "2.16.0",
      javaOptions := (javaOptions in `dotty-compiler-bootstrapped`).value,

      run := Def.inputTaskDyn {
        val inputArgs = spaceDelimited("<arg>").parsed

        val mainClass = "dotty.tools.languageserver.Main"
        val extensionPath = (baseDirectory in `vscode-dotty`).value.getAbsolutePath

        val codeArgs =
          s"--extensionDevelopmentPath=$extensionPath" +:
            (if (inputArgs.isEmpty) List((baseDirectory.value / "..").getAbsolutePath) else inputArgs)

        val clientCommand = prepareCommand(codeCommand.value ++ codeArgs)

        val allArgs = "-client_command" +: clientCommand

        runTask(Runtime, mainClass, allArgs: _*)
      }.dependsOn(compile in (`vscode-dotty`, Compile)).evaluated
    ).
    settings(
      ideTestsCompilerVersion := (version in `dotty-compiler`).value,
      ideTestsCompilerArguments := Seq(),
      ideTestsDependencyClasspath := {
        val dottyLib = (classDirectory in `dotty-library-bootstrapped` in Compile).value
        val scalaLib =
          (dependencyClasspath in `dotty-library-bootstrapped` in Compile)
            .value
            .map(_.data)
            .filter(_.getName.matches("scala-library.*\\.jar"))
            .toList
        dottyLib :: scalaLib
      },
      buildInfoKeys in Test := Seq[BuildInfoKey](
        ideTestsCompilerVersion,
        ideTestsCompilerArguments,
        ideTestsDependencyClasspath
      ),
      buildInfoPackage in Test := "dotty.tools.languageserver.util.server",
      BuildInfoPlugin.buildInfoScopedSettings(Test),
      BuildInfoPlugin.buildInfoDefaultSettings
    )

  /** A sandbox to play with the Scala.js back-end of dotty.
   *
   *  This sandbox is compiled with dotty with support for Scala.js. It can be
   *  used like any regular Scala.js project. In particular, `fastOptJS` will
   *  produce a .js file, and `run` will run the JavaScript code with a JS VM.
   *
   *  Simply running `dotty/run -scalajs` without this sandbox is not very
   *  useful, as that would not provide the linker and JS runners.
   */
  lazy val sjsSandbox = project.in(file("sandbox/scalajs")).
    enablePlugins(MyScalaJSPlugin).
    dependsOn(`dotty-library-bootstrappedJS`).
    settings(
      // Required to run Scala.js tests.
      fork in Test := false,

      scalaJSUseMainModuleInitializer := true,
    )

  /** Scala.js test suite.
   *
   *  This project downloads the sources of the upstream Scala.js test suite,
   *  and tests them with the dotty Scala.js back-end. Currently, only a very
   *  small fraction of the upstream test suite is actually compiled and run.
   *  It will grow in the future, as more stuff is confirmed to be supported.
   */
  lazy val sjsJUnitTests = project.in(file("tests/sjs-junit")).
    enablePlugins(MyScalaJSPlugin).
    dependsOn(`dotty-library-bootstrappedJS`).
    settings(
      scalacOptions --= Seq("-Xfatal-warnings", "-deprecation"),

      // Required to run Scala.js tests.
      fork in Test := false,

      sourceDirectory in fetchScalaJSSource := target.value / s"scala-js-src-$scalaJSVersion",

      fetchScalaJSSource := {
        import org.eclipse.jgit.api._

        val s = streams.value
        val ver = scalaJSVersion
        val trgDir = (sourceDirectory in fetchScalaJSSource).value

        if (!trgDir.exists) {
          s.log.info(s"Fetching Scala.js source version $ver")
          IO.createDirectory(trgDir)
          new CloneCommand()
            .setDirectory(trgDir)
            .setURI("https://github.com/scala-js/scala-js.git")
            .call()
        }

        // Checkout proper ref. We do this anyway so we fail if something is wrong
        val git = Git.open(trgDir)
        s.log.info(s"Checking out Scala.js source version $ver")
        git.checkout().setName(s"v$ver").call()

        trgDir
      },

      // We need JUnit in the Compile configuration
      libraryDependencies +=
        ("org.scala-js" %% "scalajs-junit-test-runtime" % scalaJSVersion).withDottyCompat(scalaVersion.value),

      sourceGenerators in Compile += Def.task {
        import org.scalajs.linker.interface.CheckedBehavior

        val stage = scalaJSStage.value

        val linkerConfig = stage match {
          case FastOptStage => (scalaJSLinkerConfig in (Compile, fastOptJS)).value
          case FullOptStage => (scalaJSLinkerConfig in (Compile, fullOptJS)).value
        }

        val moduleKind = linkerConfig.moduleKind
        val sems = linkerConfig.semantics

        ConstantHolderGenerator.generate(
            (sourceManaged in Compile).value,
            "org.scalajs.testsuite.utils.BuildInfo",
            "scalaVersion" -> scalaVersion.value,
            "hasSourceMaps" -> false, //MyScalaJSPlugin.wantSourceMaps.value,
            "isNoModule" -> (moduleKind == ModuleKind.NoModule),
            "isESModule" -> (moduleKind == ModuleKind.ESModule),
            "isCommonJSModule" -> (moduleKind == ModuleKind.CommonJSModule),
            "isFullOpt" -> (stage == FullOptStage),
            "compliantAsInstanceOfs" -> (sems.asInstanceOfs == CheckedBehavior.Compliant),
            "compliantArrayIndexOutOfBounds" -> (sems.arrayIndexOutOfBounds == CheckedBehavior.Compliant),
            "compliantModuleInit" -> (sems.moduleInit == CheckedBehavior.Compliant),
            "strictFloats" -> sems.strictFloats,
            "productionMode" -> sems.productionMode,
            "es2015" -> linkerConfig.esFeatures.useECMAScript2015,
        )
      }.taskValue,

      managedSources in Compile ++= {
        val dir = fetchScalaJSSource.value / "test-suite/js/src/main/scala"
        val filter = (
          ("*.scala": FileFilter)
            -- "Typechecking*.scala"
            -- "NonNativeTypeTestSeparateRun.scala"
        )
        (dir ** filter).get
      },

      managedSources in Test ++= {
        val dir = fetchScalaJSSource.value / "test-suite"
        (
          (dir / "shared/src/test/scala/org/scalajs/testsuite/compiler" ** (("*.scala":FileFilter) -- "RegressionTest.scala" -- "ReflectiveCallTest.scala")).get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/lang" ** "*.scala").get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/io" ** (("*.scala": FileFilter) -- "ReadersTest.scala")).get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/math" ** "*.scala").get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/net" ** "*.scala").get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/security" ** "*.scala").get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/util/regex" ** "*.scala").get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/util/concurrent" ** "*.scala").get

          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/javalib/util" * (("*.scala": FileFilter)
            -- "AbstractListTest.scala" -- "AbstractMapTest.scala" -- "AbstractSetTest.scala" -- "ArrayDequeTest.scala" -- "ArrayListTest.scala"
            -- "CollectionsOnCheckedCollectionTest.scala" -- "CollectionsOnCheckedListTest.scala" -- "CollectionsOnCheckedMapTest.scala" -- "CollectionsOnCheckedSetTest.scala"
            -- "CollectionsOnCollectionsTest.scala" -- "CollectionsOnListsTest.scala" -- "CollectionsOnMapsTest.scala" -- "CollectionsOnSetFromMapTest.scala" -- "CollectionsOnSetsTest.scala"
            -- "CollectionsOnSynchronizedCollectionTest.scala" -- "CollectionsOnSynchronizedListTest.scala" -- "CollectionsOnSynchronizedMapTest.scala" -- "CollectionsOnSynchronizedSetTest.scala" -- "CollectionsTest.scala"
            -- "DequeTest.scala" -- "EventObjectTest.scala" -- "FormatterTest.scala" -- "HashMapTest.scala" -- "HashSetTest.scala" -- "IdentityHashMapTest.scala"
            -- "LinkedHashMapTest.scala" -- "LinkedHashSetTest.scala" -- "LinkedListTest.scala"
            -- "PriorityQueueTest.scala"  -- "SortedMapTest.scala" -- "SortedSetTest.scala" -- "TreeSetTest.scala")).get

          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/utils" ** "*.scala").get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/junit" ** "*.scala").get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/niobuffer" ** (("*.scala": FileFilter)  -- "ByteBufferTest.scala")).get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/niocharset" ** (("*.scala": FileFilter)  -- "BaseCharsetTest.scala" -- "Latin1Test.scala" -- "USASCIITest.scala" -- "UTF16Test.scala" -- "UTF8Test.scala")).get
          ++ (dir / "shared/src/test/scala/org/scalajs/testsuite/scalalib" ** (("*.scala": FileFilter)  -- "ArrayBuilderTest.scala" -- "ClassTagTest.scala" -- "EnumerationTest.scala" -- "SymbolTest.scala")).get
          ++ (dir / "shared/src/test/require-sam" ** "*.scala").get
          ++ (dir / "shared/src/test/require-jdk8/org/scalajs/testsuite/compiler" ** (("*.scala": FileFilter) -- "DefaultMethodsTest.scala")).get
          ++ (dir / "shared/src/test/require-jdk8/org/scalajs/testsuite/javalib/lang" ** "*.scala").get
          ++ (dir / "shared/src/test/require-jdk8/org/scalajs/testsuite/javalib/util" ** (("*.scala": FileFilter) -- "CollectionsOnCopyOnWriteArrayListTestOnJDK8.scala")).get
          ++ (dir / "shared/src/test/require-jdk7/org/scalajs/testsuite/javalib/io" ** "*.scala").get
          ++ (dir / "shared/src/test/require-jdk7/org/scalajs/testsuite/javalib/lang" ** "*.scala").get
          ++ (dir / "shared/src/test/require-jdk7/org/scalajs/testsuite/javalib/util" ** (("*.scala": FileFilter) -- "ObjectsTestOnJDK7.scala")).get
        )
      }
    )

  lazy val `dotty-bench` = project.in(file("bench")).asDottyBench(NonBootstrapped)
  lazy val `dotty-bench-bootstrapped` = project.in(file("bench")).asDottyBench(Bootstrapped)
  lazy val `dotty-bench-run` = project.in(file("bench-run")).asDottyBench(Bootstrapped)

  lazy val `dotty-tastydoc` = project.in(file("tastydoc")).asDottyTastydoc(Bootstrapped)
  lazy val `dotty-tastydoc-input` = project.in(file("tastydoc/input")).asDottyTastydocInput(Bootstrapped)

  // Depend on dotty-library so that sbt projects using dotty automatically
  // depend on the dotty-library
  lazy val `scala-library` = project.
    dependsOn(`dotty-library-bootstrapped`).
    settings(commonDummySettings).
    settings(
      // Need a direct dependency on the real scala-library even though we indirectly
      // depend on it via dotty-library, because sbt may rewrite dependencies
      // (see https://github.com/sbt/sbt/pull/2634), but won't rewrite the direct
      // dependencies of scala-library (see https://github.com/sbt/sbt/pull/2897)
      libraryDependencies += "org.scala-lang" % "scala-library" % stdlibVersion(Bootstrapped)
    )

  lazy val `scala-compiler` = project.
    settings(commonDummySettings)
  lazy val `scala-reflect` = project.
    settings(commonDummySettings).
    settings(
      libraryDependencies := Seq("org.scala-lang" % "scala-reflect" % stdlibVersion(Bootstrapped))
    )
  lazy val scalap = project.
    settings(commonDummySettings).
    settings(
      libraryDependencies := Seq("org.scala-lang" % "scalap" % stdlibVersion(Bootstrapped))
    )


  // sbt plugin to use Dotty in your own build, see
  // https://github.com/lampepfl/dotty-example-project for usage.
  lazy val `sbt-dotty` = project.in(file("sbt-dotty")).
    enablePlugins(SbtPlugin).
    settings(commonSettings).
    settings(
      name := sbtDottyName,
      version := sbtDottyVersion,
      // Keep in sync with inject-sbt-dotty.sbt
      libraryDependencies ++= Seq(
        Dependencies.`jackson-databind`,
        Dependencies.`compiler-interface`
      ),
      unmanagedSourceDirectories in Compile +=
        baseDirectory.value / "../language-server/src/dotty/tools/languageserver/config",
      sbtTestDirectory := baseDirectory.value / "sbt-test",
      scriptedLaunchOpts ++= Seq(
        "-Dplugin.version=" + version.value,
        "-Dplugin.scalaVersion=" + dottyVersion,
        "-Dsbt.boot.directory=" + ((baseDirectory in ThisBuild).value / ".sbt-scripted").getAbsolutePath // Workaround sbt/sbt#3469
      ),
      // Pass along ivy home and repositories settings to sbt instances run from the tests
      scriptedLaunchOpts ++= {
        val repositoryPath = (io.Path.userHome / ".sbt" / "repositories").absolutePath
        s"-Dsbt.repository.config=$repositoryPath" ::
        ivyPaths.value.ivyHome.map("-Dsbt.ivy.home=" + _.getAbsolutePath).toList
      },
      scriptedBufferLog := true,
      scripted := scripted.dependsOn(
        publishLocal in `dotty-sbt-bridge`,
        publishLocal in `dotty-interfaces`,
        publishLocal in `dotty-compiler-bootstrapped`,
        publishLocal in `dotty-library-bootstrapped`,
        publishLocal in `tasty-core-bootstrapped`,
        publishLocal in `dotty-staging`,
        publishLocal in `dotty-tasty-inspector`,
        publishLocal in `scala-library`,
        publishLocal in `scala-reflect`,
        publishLocal in `dotty-doc-bootstrapped`,
        publishLocal in `dotty-bootstrapped` // Needed because sbt currently hardcodes the dotty artifact
      ).evaluated
    )

  lazy val `vscode-dotty` = project.in(file("vscode-dotty")).
    settings(commonSettings).
    settings(
      version := "0.1.17-snapshot", // Keep in sync with package.json
      autoScalaLibrary := false,
      publishArtifact := false,
      resourceGenerators in Compile += Def.task {
        // Resources that will be copied when bootstrapping a new project
        val buildSbtFile = baseDirectory.value / "out" / "build.sbt"
        IO.write(buildSbtFile,
          s"""scalaVersion := "$publishedDottyVersion"""")
        val dottyPluginSbtFile = baseDirectory.value / "out" / "dotty-plugin.sbt"
        IO.write(dottyPluginSbtFile,
          s"""addSbtPlugin("$dottyOrganization" % "$sbtDottyName" % "$publishedSbtDottyVersion")""")
        Seq(buildSbtFile, dottyPluginSbtFile)
      },
      compile in Compile := Def.task {
        val workingDir = baseDirectory.value
        val coursier = workingDir / "out" / "coursier"
        val packageJson = workingDir / "package.json"
        if (!coursier.exists || packageJson.lastModified > coursier.lastModified)
          runProcess(Seq("npm", "install"), wait = true, directory = Some(workingDir))
        val tsc = workingDir / "node_modules" / ".bin" / "tsc"
        runProcess(Seq(tsc.getAbsolutePath, "--pretty", "--project", workingDir.getAbsolutePath), wait = true)

        // vscode-dotty depends on scala-lang.scala for syntax highlighting,
        // this is not automatically installed when starting the extension in development mode
        // (--extensionDevelopmentPath=...)
        installCodeExtension(codeCommand.value, "scala-lang.scala")

        sbt.internal.inc.Analysis.Empty
      }.dependsOn(managedResources in Compile).value,
      sbt.Keys.`package`:= {
        runProcess(Seq("vsce", "package"), wait = true, directory = Some(baseDirectory.value))

        baseDirectory.value / s"dotty-${version.value}.vsix"
      },
      unpublish := {
        runProcess(Seq("vsce", "unpublish"), wait = true, directory = Some(baseDirectory.value))
      },
      publish := {
        runProcess(Seq("vsce", "publish"), wait = true, directory = Some(baseDirectory.value))
      },
      run := Def.inputTask {
        val inputArgs = spaceDelimited("<arg>").parsed
        val codeArgs = if (inputArgs.isEmpty) List((baseDirectory.value / "..").getAbsolutePath) else inputArgs
        val extensionPath = baseDirectory.value.getAbsolutePath
        val processArgs = List(s"--extensionDevelopmentPath=$extensionPath") ++ codeArgs

        runProcess(codeCommand.value ++ processArgs, wait = true)
      }.dependsOn(compile in Compile).evaluated
    )

  val prepareCommunityBuild = taskKey[Unit]("Publish local the compiler and the sbt plugin. Also store the versions of the published local artefacts in two files, community-build/{dotty-bootstrapped.version,sbt-dotty-sbt}.")

  val updateCommunityBuild = taskKey[Unit]("Updates the community build.")

  lazy val `community-build` = project.in(file("community-build")).
    dependsOn(dottyLibrary(Bootstrapped)).
    settings(commonBootstrappedSettings).
    settings(
      prepareCommunityBuild := {
        (publishLocal in `dotty-sbt-bridge`).value
        (publishLocal in `dotty-interfaces`).value
        (publishLocal in `scala-library`).value
        (publishLocal in `scala-reflect`).value
        (publishLocal in `tasty-core-bootstrapped`).value
        (publishLocal in `dotty-library-bootstrapped`).value
        (publishLocal in `dotty-doc-bootstrapped`).value
        (publishLocal in `dotty-compiler-bootstrapped`).value
        (publishLocal in `sbt-dotty`).value
        (publishLocal in `dotty-bootstrapped`).value
        // (publishLocal in `dotty-staging`).value
        val pluginText =
          s"""updateOptions in Global ~= (_.withLatestSnapshots(false))
             |addSbtPlugin("ch.epfl.lamp" % "sbt-dotty" % "$sbtDottyVersion")""".stripMargin
        IO.write(baseDirectory.value / "sbt-dotty-sbt", pluginText)
        IO.write(baseDirectory.value / "dotty-bootstrapped.version", dottyVersion)
      },
      updateCommunityBuild := testOnly.in(Test).toTask(
        " dotty.communitybuild.CommunityBuildUpdate -- --include-categories=dotty.communitybuild.UpdateCategory").value,
      testOptions in Test += Tests.Argument(
        TestFrameworks.JUnit,
        "--include-categories=dotty.communitybuild.TestCategory",
      ),
      (Test / testOnly) := ((Test / testOnly) dependsOn prepareCommunityBuild).evaluated,
      (Test / test    ) := ((Test / test    ) dependsOn prepareCommunityBuild).value,
      javaOptions ++= {
        // Propagate the ivy cache directory setting to the tests, which will
        // then propagate it further to the sbt instances they will spawn.
        val sbtProps = Option(System.getProperty("sbt.ivy.home")) match {
          case Some(ivyHome) =>
            Seq(s"-Dsbt.ivy.home=$ivyHome")
          case _ =>
            Seq()
        }
        sbtProps
      }
    )

  lazy val publishSettings = Seq(
    publishMavenStyle := true,
    isSnapshot := version.value.contains("SNAPSHOT"),
    publishTo := sonatypePublishToBundle.value,
    publishConfiguration ~= (_.withOverwrite(true)),
    publishLocalConfiguration ~= (_.withOverwrite(true)),
    publishArtifact in Test := false,
    homepage := Some(url(dottyGithubUrl)),
    licenses += (("Apache-2.0",
      url("https://www.apache.org/licenses/LICENSE-2.0"))),
    scmInfo := Some(
      ScmInfo(
        url(dottyGithubUrl),
        "scm:git:git@github.com:lampepfl/dotty.git"
      )
    ),
    developers := List(
      Developer(
        id = "odersky",
        name = "Martin Odersky",
        email = "martin.odersky@epfl.ch",
        url = url("https://github.com/odersky")
      ),
      Developer(
        id = "DarkDimius",
        name = "Dmitry Petrashko",
        email = "me@d-d.me",
        url = url("https://d-d.me")
      ),
      Developer(
        id = "smarter",
        name = "Guillaume Martres",
        email = "smarter@ubuntu.com",
        url = url("http://guillaume.martres.me")
      ),
      Developer(
        id = "felixmulder",
        name = "Felix Mulder",
        email = "felix.mulder@gmail.com",
        url = url("http://felixmulder.com")
      ),
      Developer(
        id = "liufengyun",
        name = "Liu Fengyun",
        email = "liu@fengy.me",
        url = url("https://fengy.me")
      ),
      Developer(
        id = "nicolasstucki",
        name = "Nicolas Stucki",
        email = "nicolas.stucki@gmail.com",
        url = url("https://github.com/nicolasstucki")
      ),
      Developer(
        id = "OlivierBlanvillain",
        name = "Olivier Blanvillain",
        email = "olivier.blanvillain@gmail.com",
        url = url("https://github.com/OlivierBlanvillain")
      ),
      Developer(
        id = "biboudis",
        name = "Aggelos Biboudis",
        email = "aggelos.biboudis@epfl.ch",
        url = url("http://biboudis.github.io")
      ),
      Developer(
        id = "allanrenucci",
        name = "Allan Renucci",
        email = "allan.renucci@gmail.com",
        url = url("https://github.com/allanrenucci")
      ),
      Developer(
        id = "Duhemm",
        name = "Martin Duhem",
        email = "martin.duhem@gmail.com",
        url = url("https://github.com/Duhemm")
      )
    )
  )

  lazy val commonDistSettings = Seq(
    packMain := Map(),
    publishArtifact := false,
    packGenerateMakefile := false,
    packExpandedClasspath := true,
    packArchiveName := "dotty-" + dottyVersion
  )

  lazy val dist = project.asDist(NonBootstrapped)
    .settings(
      packResourceDir += (baseDirectory.value / "bin" -> "bin"),
    )
  lazy val `dist-bootstrapped` = project.asDist(Bootstrapped)
    .settings(
      packResourceDir += ((baseDirectory in dist).value / "bin" -> "bin"),
    )

  implicit class ProjectDefinitions(val project: Project) extends AnyVal {

    // FIXME: we do not aggregate `bin` because its tests delete jars, thus breaking other tests
    def asDottyRoot(implicit mode: Mode): Project = project.withCommonSettings.
      aggregate(`dotty-interfaces`, dottyLibrary, dottyCompiler, tastyCore, dottyDoc, `dotty-sbt-bridge`).
      bootstrappedAggregate(`scala-library`, `scala-compiler`, `scala-reflect`, scalap,
        `dotty-language-server`, `dotty-staging`, `dotty-tasty-inspector`, `dotty-tastydoc`).
      dependsOn(tastyCore).
      dependsOn(dottyCompiler).
      dependsOn(dottyLibrary).
      nonBootstrappedSettings(
        addCommandAlias("run", "dotty-compiler/run"),
        // Clean everything by default
        addCommandAlias("clean", ";dotty/clean;dotty-bootstrapped/clean"),
        // `publishLocal` on the non-bootstrapped compiler does not produce a
        // working distribution (it can't in general, since there's no guarantee
        // that the non-bootstrapped library is compatible with the
        // non-bootstrapped compiler), so publish the bootstrapped one by
        // default.
        addCommandAlias("publishLocal", "dotty-bootstrapped/publishLocal"),
      )

    def asDottyCompiler(implicit mode: Mode): Project = project.withCommonSettings.
      dependsOn(`dotty-interfaces`).
      dependsOn(dottyLibrary).
      dependsOn(tastyCore).
      settings(dottyCompilerSettings)

    def asDottyLibrary(implicit mode: Mode): Project = project.withCommonSettings.
      settings(
        libraryDependencies += "org.scala-lang" % "scala-library" % stdlibVersion
      ).
      settings(dottyLibrarySettings)

    def asTastyCore(implicit mode: Mode): Project = project.withCommonSettings.
      dependsOn(dottyLibrary).
      settings(tastyCoreSettings)

    def asTastyCoreScala2: Project = project.settings(commonScala2Settings)

    def asDottyDoc(implicit mode: Mode): Project = project.withCommonSettings.
      dependsOn(dottyCompiler, dottyCompiler % "test->test").
      settings(commonDocSettings).
      settings(dottyDocSettings)

    def asDottyBench(implicit mode: Mode): Project = project.withCommonSettings.
      dependsOn(dottyCompiler).
      settings(commonBenchmarkSettings).
      enablePlugins(JmhPlugin)

    def asDottyTastydoc(implicit mode: Mode): Project = project.withCommonSettings.
      aggregate(`dotty-tastydoc-input`).
      dependsOn(dottyCompiler).
      dependsOn(`dotty-tasty-inspector`).
      settings(commonDocSettings)

    def asDottyTastydocInput(implicit mode: Mode): Project = project.withCommonSettings.
      dependsOn(dottyCompiler)

    def asDist(implicit mode: Mode): Project = project.
      enablePlugins(PackPlugin).
      withCommonSettings.
      dependsOn(`dotty-interfaces`, dottyCompiler, dottyLibrary, tastyCore, `dotty-staging`, `dotty-tasty-inspector`, dottyDoc).
      settings(commonDistSettings).
      bootstrappedSettings(
        target := baseDirectory.value / "target" // override setting in commonBootstrappedSettings
      )

    def withCommonSettings(implicit mode: Mode): Project = project.settings(mode match {
      case NonBootstrapped => commonNonBootstrappedSettings
      case Bootstrapped => commonBootstrappedSettings
    })
  }
}
