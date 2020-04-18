package dotty
package tools
package dotc

import org.junit.{ Test, BeforeClass, AfterClass }
import org.junit.Assert._
import org.junit.Assume._
import org.junit.experimental.categories.Category

import java.io.File
import java.nio.file._
import java.util.stream.{ Stream => JStream }
import scala.collection.JavaConverters._
import scala.util.matching.Regex
import scala.concurrent.duration._
import TestSources.sources
import vulpix._

class CompilationTests extends ParallelTesting {
  import ParallelTesting._
  import TestConfiguration._
  import CompilationTests._
  import CompilationTest.aggregateTests

  // Test suite configuration --------------------------------------------------

  def maxDuration = 45.seconds
  def numberOfSlaves = 5
  def safeMode = Properties.testsSafeMode
  def isInteractive = SummaryReport.isInteractive
  def testFilter = Properties.testsFilter
  def updateCheckFiles: Boolean = Properties.testsUpdateCheckfile

  // Positive tests ------------------------------------------------------------

  @Test def pos: Unit = {
    implicit val testGroup: TestGroup = TestGroup("compilePos")
    aggregateTests(
      compileFile("tests/pos/nullarify.scala", defaultOptions.and("-Ycheck:nullarify")),
      compileFile("tests/pos-scala2/rewrites.scala", scala2CompatMode.and("-rewrite")).copyToTarget(),
      compileFile("tests/pos-special/utf8encoded.scala", explicitUTF8),
      compileFile("tests/pos-special/utf16encoded.scala", explicitUTF16),
      compileFilesInDir("tests/pos-special/sourcepath/outer", defaultOptions.and("-sourcepath", "tests/pos-special/sourcepath")),
      compileFile("tests/pos-special/sourcepath/outer/nested/Test4.scala", defaultOptions.and("-sourcepath", "tests/pos-special/sourcepath")),
      compileFilesInDir("tests/pos-special/fatal-warnings", defaultOptions.and("-Xfatal-warnings", "-feature")),
      compileFilesInDir("tests/pos-special/spec-t5545", defaultOptions),
      compileFilesInDir("tests/pos-special/strawman-collections", allowDeepSubtypes),
      compileFilesInDir("tests/pos-special/isInstanceOf", allowDeepSubtypes.and("-Xfatal-warnings")),
      compileFilesInDir("tests/new", defaultOptions),
      compileFilesInDir("tests/pos-scala2", scala2CompatMode),
      compileFilesInDir("tests/pos-custom-args/erased", defaultOptions.and("-Yerased-terms")),
      compileFilesInDir("tests/pos", defaultOptions),
      compileFilesInDir("tests/pos-deep-subtype", allowDeepSubtypes),
      compileFile(
        // succeeds despite -Xfatal-warnings because of -nowarn
        "tests/neg-custom-args/fatal-warnings/xfatalWarnings.scala",
        defaultOptions.and("-nowarn", "-Xfatal-warnings")
      ),
      compileFile("tests/pos-special/typeclass-scaling.scala", defaultOptions.and("-Xmax-inlines", "40")),
      compileFile("tests/pos-special/indent-colons.scala", defaultOptions.and("-Yindent-colons")),
      compileFile("tests/pos-special/i7296.scala", defaultOptions.and("-source", "3.1", "-deprecation", "-Xfatal-warnings")),
      compileFile("tests/pos-special/notNull.scala", defaultOptions.and("-Yexplicit-nulls")),
      compileDir("tests/pos-special/adhoc-extension", defaultOptions.and("-source", "3.1", "-feature", "-Xfatal-warnings")),
      compileFile("tests/pos-special/i7575.scala", defaultOptions.andLanguageFeature("dynamics")),
      compileFile("tests/pos-special/kind-projector.scala", defaultOptions.and("-Ykind-projector")),
      compileFile("tests/run/i5606.scala", defaultOptions.and("-Yretain-trees")),
      compileFile("tests/pos-custom-args/i5498-postfixOps.scala", defaultOptions withoutLanguageFeature "postfixOps"),
    ).checkCompile()
  }

  @Test def posTwice: Unit = {
    implicit val testGroup: TestGroup = TestGroup("posTwice")
    aggregateTests(
      compileFilesInDir("tests/pos-java-interop", defaultOptions),
      compileFilesInDir("tests/pos-java-interop-separate", defaultOptions),
      compileFile("tests/pos/t2168.scala", defaultOptions),
      compileFile("tests/pos/erasure.scala", defaultOptions),
      compileFile("tests/pos/Coder.scala", defaultOptions),
      compileFile("tests/pos/blockescapes.scala", defaultOptions),
      compileFile("tests/pos/functions1.scala", defaultOptions),
      compileFile("tests/pos/implicits1.scala", defaultOptions),
      compileFile("tests/pos/inferred.scala", defaultOptions),
      compileFile("tests/pos/selftypes.scala", defaultOptions),
      compileFile("tests/pos/varargs.scala", defaultOptions),
      compileFile("tests/pos/vararg-pattern.scala", defaultOptions),
      compileFile("tests/pos/opassign.scala", defaultOptions),
      compileFile("tests/pos/typedapply.scala", defaultOptions),
      compileFile("tests/pos/nameddefaults.scala", defaultOptions),
      compileFile("tests/pos/desugar.scala", defaultOptions),
      compileFile("tests/pos/sigs.scala", defaultOptions),
      compileFile("tests/pos/typers.scala", defaultOptions),
      compileDir("tests/pos/typedIdents", defaultOptions),
      compileFile("tests/pos/assignments.scala", defaultOptions),
      compileFile("tests/pos/packageobject.scala", defaultOptions),
      compileFile("tests/pos/overloaded.scala", defaultOptions),
      compileFile("tests/pos/overrides.scala", defaultOptions),
      compileDir("tests/pos/java-override", defaultOptions),
      compileFile("tests/pos/templateParents.scala", defaultOptions),
      compileFile("tests/pos/overloadedAccess.scala", defaultOptions),
      compileFile("tests/pos/approximateUnion.scala", defaultOptions),
      compileFilesInDir("tests/pos/tailcall", defaultOptions),
      compileShallowFilesInDir("tests/pos/pos_valueclasses", defaultOptions),
      compileFile("tests/pos/subtyping.scala", defaultOptions),
      compileFile("tests/pos/i0239.scala", defaultOptions),
      compileFile("tests/pos/anonClassSubtyping.scala", defaultOptions),
      compileFile("tests/pos/extmethods.scala", defaultOptions),
      compileFile("tests/pos/companions.scala", defaultOptions)
    ).times(2).checkCompile()
  }

  // Negative tests ------------------------------------------------------------

  @Test def negAll: Unit = {
    implicit val testGroup: TestGroup = TestGroup("compileNeg")
    aggregateTests(
      compileFilesInDir("tests/neg", defaultOptions),
      compileFilesInDir("tests/neg-tailcall", defaultOptions),
      compileFilesInDir("tests/neg-strict", defaultOptions.and("-source", "3.1")),
      compileFilesInDir("tests/neg-no-kind-polymorphism", defaultOptions and "-Yno-kind-polymorphism"),
      compileFilesInDir("tests/neg-custom-args/deprecation", defaultOptions.and("-Xfatal-warnings", "-deprecation")),
      compileFilesInDir("tests/neg-custom-args/fatal-warnings", defaultOptions.and("-Xfatal-warnings")),
      compileFilesInDir("tests/neg-custom-args/erased", defaultOptions.and("-Yerased-terms")),
      compileFilesInDir("tests/neg-custom-args/allow-double-bindings", allowDoubleBindings),
      compileFilesInDir("tests/neg-custom-args/allow-deep-subtypes", allowDeepSubtypes),
      compileFilesInDir("tests/neg-custom-args/explicit-nulls", defaultOptions.and("-Yexplicit-nulls")),
      compileDir("tests/neg-custom-args/impl-conv", defaultOptions.and("-Xfatal-warnings", "-feature")),
      compileFile("tests/neg-custom-args/implicit-conversions.scala", defaultOptions.and("-Xfatal-warnings", "-feature")),
      compileFile("tests/neg-custom-args/implicit-conversions-old.scala", defaultOptions.and("-Xfatal-warnings", "-feature")),
      compileFile("tests/neg-custom-args/i3246.scala", scala2CompatMode),
      compileFile("tests/neg-custom-args/overrideClass.scala", scala2CompatMode),
      compileFile("tests/neg-custom-args/ovlazy.scala", scala2CompatMode.and("-migration", "-Xfatal-warnings")),
      compileFile("tests/neg-custom-args/autoTuplingTest.scala", defaultOptions.andLanguageFeature("noAutoTupling")),
      compileFile("tests/neg-custom-args/nopredef.scala", defaultOptions.and("-Yno-predef")),
      compileFile("tests/neg-custom-args/noimports.scala", defaultOptions.and("-Yno-imports")),
      compileFile("tests/neg-custom-args/noimports2.scala", defaultOptions.and("-Yno-imports")),
      compileFile("tests/neg-custom-args/i1650.scala", allowDeepSubtypes),
      compileFile("tests/neg-custom-args/i3882.scala", allowDeepSubtypes),
      compileFile("tests/neg-custom-args/i4372.scala", allowDeepSubtypes),
      compileFile("tests/neg-custom-args/i1754.scala", allowDeepSubtypes),
      compileFile("tests/neg-custom-args/interop-polytypes.scala", allowDeepSubtypes.and("-Yexplicit-nulls")),
      compileFile("tests/neg-custom-args/conditionalWarnings.scala", allowDeepSubtypes.and("-deprecation").and("-Xfatal-warnings")),
      compileFilesInDir("tests/neg-custom-args/isInstanceOf", allowDeepSubtypes and "-Xfatal-warnings"),
      compileFile("tests/neg-custom-args/i3627.scala", allowDeepSubtypes),
      compileFile("tests/neg-custom-args/matchtype-loop.scala", allowDeepSubtypes),
      compileFile("tests/neg-custom-args/sourcepath/outer/nested/Test1.scala", defaultOptions.and("-sourcepath", "tests/neg-custom-args/sourcepath")),
      compileDir("tests/neg-custom-args/sourcepath2/hi", defaultOptions.and("-sourcepath", "tests/neg-custom-args/sourcepath2", "-Xfatal-warnings")),
      compileList("duplicate source", List(
        "tests/neg-custom-args/toplevel-samesource/S.scala",
        "tests/neg-custom-args/toplevel-samesource/nested/S.scala"),
        defaultOptions),
      compileFile("tests/neg-custom-args/i6300.scala", allowDeepSubtypes),
      compileFile("tests/neg-custom-args/infix.scala", defaultOptions.and("-source", "3.1", "-deprecation", "-Xfatal-warnings")),
      compileFile("tests/neg-custom-args/missing-alpha.scala", defaultOptions.and("-source", "3.1", "-deprecation", "-Xfatal-warnings")),
      compileFile("tests/neg-custom-args/wildcards.scala", defaultOptions.and("-source", "3.1", "-deprecation", "-Xfatal-warnings")),
      compileFile("tests/neg-custom-args/indentRight.scala", defaultOptions.and("-noindent", "-Xfatal-warnings")),
      compileFile("tests/neg-custom-args/extmethods-tparams.scala", defaultOptions.and("-deprecation", "-Xfatal-warnings")),
      compileDir("tests/neg-custom-args/adhoc-extension", defaultOptions.and("-source", "3.1", "-feature", "-Xfatal-warnings")),
      compileFile("tests/neg/i7575.scala", defaultOptions.withoutLanguageFeatures.and("-language:_")),
      compileFile("tests/neg-custom-args/kind-projector.scala", defaultOptions.and("-Ykind-projector")),
      compileFile("tests/neg-custom-args/typeclass-derivation2.scala", defaultOptions.and("-Yerased-terms")),
      compileFile("tests/neg-custom-args/i5498-postfixOps.scala", defaultOptions withoutLanguageFeature "postfixOps"),
    ).checkExpectedErrors()
  }

  @Test def fuzzyAll: Unit = {
    implicit val testGroup: TestGroup = TestGroup("compileFuzzy")
    compileFilesInDir("tests/fuzzy", defaultOptions).checkNoCrash()
  }

  // Run tests -----------------------------------------------------------------

  @Test def runAll: Unit = {
    implicit val testGroup: TestGroup = TestGroup("runAll")
    aggregateTests(
      compileFile("tests/run-custom-args/tuple-cons.scala", allowDeepSubtypes),
      compileFile("tests/run-custom-args/i5256.scala", allowDeepSubtypes),
      compileFile("tests/run-custom-args/fors.scala", defaultOptions.and("-source", "3.1")),
      compileFile("tests/run-custom-args/no-useless-forwarders.scala", defaultOptions and "-Xmixin-force-forwarders:false"),
      compileFilesInDir("tests/run-custom-args/erased", defaultOptions.and("-Yerased-terms")),
      compileFilesInDir("tests/run-deep-subtype", allowDeepSubtypes),
      compileFilesInDir("tests/run", defaultOptions)
    ).checkRuns()
  }

  // Generic java signatures tests ---------------------------------------------

  @Test def genericJavaSignatures: Unit = {
    implicit val testGroup: TestGroup = TestGroup("genericJavaSignatures")
    compileFilesInDir("tests/generic-java-signatures", defaultOptions).checkRuns()
  }

  // Pickling Tests ------------------------------------------------------------

  @Test def pickling: Unit = {
    implicit val testGroup: TestGroup = TestGroup("testPickling")
    aggregateTests(
      compileFilesInDir("tests/new", picklingOptions),
      compileFilesInDir("tests/pos", picklingOptions, FileFilter.exclude(TestSources.posTestPicklingBlacklisted)),
      compileFilesInDir("tests/run", picklingOptions, FileFilter.exclude(TestSources.runTestPicklingBlacklisted))
    ).checkCompile()
  }

  /** The purpose of this test is two-fold, being able to compile dotty
   *  bootstrapped, and making sure that TASTY can link against a compiled
   *  version of Dotty
   */
  @Test def tastyBootstrap: Unit = {
    implicit val testGroup: TestGroup = TestGroup("tastyBootstrap/tests")
    val libGroup = TestGroup("tastyBootstrap/lib")
    val tastyCoreGroup = TestGroup("tastyBootstrap/tastyCore")
    val dotty1Group = TestGroup("tastyBootstrap/dotty1")
    val dotty2Group = TestGroup("tastyBootstrap/dotty2")

    // Make sure that the directory is clean
    dotty.tools.io.Directory(defaultOutputDir + "tastyBootstrap").deleteRecursively()

    val opt = TestFlags(
      List(
        // compile with bootstrapped library on cp:
        defaultOutputDir + libGroup + "/lib/",
        // and bootstrapped tasty-core:
        defaultOutputDir + tastyCoreGroup + "/tastyCore/",
        // as well as bootstrapped compiler:
        defaultOutputDir + dotty1Group + "/dotty1/",
        // and the other compiler dependencies:
        Properties.compilerInterface, Properties.scalaLibrary, Properties.scalaAsm,
        Properties.dottyInterfaces, Properties.jlineTerminal, Properties.jlineReader,
      ).mkString(File.pathSeparator),
      Array("-Ycheck-reentrant", "-Yemit-tasty-in-class", "-language:postfixOps")
    )

    val libraryDirs = List(Paths.get("library/src"), Paths.get("library/src-bootstrapped"))
    val librarySources = libraryDirs.flatMap(sources(_))

    val lib =
      compileList("lib", librarySources,
        defaultOptions.and("-Ycheck-reentrant",
          "-Yerased-terms", // support declaration of scala.compiletime.erasedValue
          //  "-source", "3.1",  // TODO: re-enable once we allow : @unchecked in pattern definitions. Right now, lots of narrowing pattern definitions fail.
          "-priorityclasspath", defaultOutputDir))(libGroup)

    val tastyCoreSources = sources(Paths.get("tasty/src"))
    val tastyCore = compileList("tastyCore", tastyCoreSources, opt)(tastyCoreGroup)

    val compilerSources = sources(Paths.get("compiler/src"))
    val compilerManagedSources = sources(Properties.dottyCompilerManagedSources)

    val dotty1 = compileList("dotty1", compilerSources ++ compilerManagedSources, opt)(dotty1Group)
    val dotty2 = compileList("dotty2", compilerSources ++ compilerManagedSources, opt)(dotty2Group)

    val tests = {
      lib.keepOutput :: tastyCore.keepOutput :: dotty1.keepOutput :: aggregateTests(
        dotty2,
        compileShallowFilesInDir("compiler/src/dotty/tools", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/ast", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/config", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/parsing", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/printing", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/reporting", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/rewrites", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/transform", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/typer", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/dotc/util", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/backend", opt),
        compileShallowFilesInDir("compiler/src/dotty/tools/backend/jvm", opt),
        compileList("managed-sources", compilerManagedSources, opt)
      ).keepOutput :: Nil
    }.map(_.checkCompile())

    def assertExists(path: String) = assertTrue(Files.exists(Paths.get(path)))
    assertExists(s"out/$libGroup/lib/")
    assertExists(s"out/$tastyCoreGroup/tastyCore/")
    assertExists(s"out/$dotty1Group/dotty1/")
    assertExists(s"out/$dotty2Group/dotty2/")
    compileList("idempotency", List("tests/idempotency/BootstrapChecker.scala", "tests/idempotency/IdempotencyCheck.scala"), defaultOptions).checkRuns()

    tests.foreach(_.delete())
  }

  // Explicit nulls tests
  @Test def explicitNullsNeg: Unit = {
    implicit val testGroup: TestGroup = TestGroup("explicitNullsNeg")
    aggregateTests(
      compileFilesInDir("tests/explicit-nulls/neg", explicitNullsOptions),
      compileFilesInDir("tests/explicit-nulls/neg-patmat", explicitNullsOptions and "-Xfatal-warnings")
    )
  }.checkExpectedErrors()

  @Test def explicitNullsPos: Unit = {
    implicit val testGroup: TestGroup = TestGroup("explicitNullsPos")
    aggregateTests(
      compileFilesInDir("tests/explicit-nulls/pos", explicitNullsOptions),
      compileFilesInDir("tests/explicit-nulls/pos-separate", explicitNullsOptions)
    )
  }.checkCompile()

  @Test def explicitNullsRun: Unit = {
    implicit val testGroup: TestGroup = TestGroup("explicitNullsRun")
    compileFilesInDir("tests/explicit-nulls/run", explicitNullsOptions)
  }.checkRuns()

  // initialization tests
  @Test def checkInitNeg: Unit = {
    implicit val testGroup: TestGroup = TestGroup("checkInit")
    val options = defaultOptions.and("-Ycheck-init", "-Xfatal-warnings")
    compileFilesInDir("tests/init/neg/", options)
  }.checkExpectedErrors()

  @Test def checkInitCrash: Unit = {
    implicit val testGroup: TestGroup = TestGroup("checkInit")
    val options = defaultOptions.and("-Ycheck-init")
    compileFilesInDir("tests/init/crash", options)
  }.checkCompile()

  @Test def checkInitPos: Unit = {
    implicit val testGroup: TestGroup = TestGroup("checkInit")
    val options = defaultOptions.and("-Ycheck-init", "-Xfatal-warnings")
    compileFilesInDir("tests/init/pos", options)
  }.checkCompile()

}

object CompilationTests {
  implicit val summaryReport: SummaryReporting = new SummaryReport
  @AfterClass def cleanup(): Unit = summaryReport.echoSummary()
}
