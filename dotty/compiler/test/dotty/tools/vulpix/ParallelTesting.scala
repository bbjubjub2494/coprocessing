package dotty
package tools
package vulpix

import java.io.{File => JFile}
import java.lang.System.{lineSeparator => EOL}
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{Files, NoSuchFileException, Path, Paths}
import java.text.SimpleDateFormat
import java.util.{HashMap, Timer, TimerTask}
import java.util.concurrent.{TimeUnit, TimeoutException, Executors => JExecutors}

import scala.collection.mutable
import scala.io.Source
import scala.util.{Random, Try, Failure => TryFailure, Success => TrySuccess}
import scala.util.control.NonFatal
import scala.util.matching.Regex

import dotc.{Compiler, Driver}
import dotc.core.Contexts._
import dotc.decompiler
import dotc.interfaces.Diagnostic.ERROR
import dotc.reporting.{Reporter, TestReporter}
import dotc.reporting.Diagnostic
import dotc.util.DiffUtil
import dotty.tools.vulpix.TestConfiguration.defaultOptions

/** A parallel testing suite whose goal is to integrate nicely with JUnit
 *
 *  This trait can be mixed in to offer parallel testing to compile runs. When
 *  using this, you should be running your JUnit tests **sequentially**, as the
 *  test suite itself runs with a high level of concurrency.
 */
trait ParallelTesting extends RunnerOrchestration { self =>

  import ParallelTesting._

  /** If the running environment supports an interactive terminal, each `Test`
   *  will be run with a progress bar and real time feedback
   */
  def isInteractive: Boolean

  /** A string which is used to filter which tests to run, if `None` will run
   *  all tests. All absolute paths that contain the substring `testFilter`
   *  will be run
   */
  def testFilter: Option[String]

  /** Tests should override the checkfiles with the current output */
  def updateCheckFiles: Boolean

  /** A test source whose files or directory of files is to be compiled
   *  in a specific way defined by the `Test`
   */
  sealed trait TestSource { self =>
    def name: String
    def outDir: JFile
    def flags: TestFlags
    def sourceFiles: Array[JFile]

    def runClassPath: String = outDir.getPath + JFile.pathSeparator + flags.runClassPath

    def title: String = self match {
      case self: JointCompilationSource =>
        if (self.files.length > 1) name
        else self.files.head.getPath

      case self: SeparateCompilationSource =>
        self.dir.getPath
    }

    /** Adds the flags specified in `newFlags0` if they do not already exist */
    def withFlags(newFlags0: String*) = {
      val newFlags = newFlags0.toArray
      if (!flags.options.containsSlice(newFlags)) self match {
        case self: JointCompilationSource =>
          self.copy(flags = flags.and(newFlags:_*))
        case self: SeparateCompilationSource =>
          self.copy(flags = flags.and(newFlags:_*))
      }
      else self
    }

    /** Generate the instructions to redo the test from the command line */
    def buildInstructions(errors: Int, warnings: Int): String = {
      val sb = new StringBuilder
      val maxLen = 80
      var lineLen = 0
      val delimiter = " "

      sb.append(
        s"""|
            |Test '$title' compiled with $errors error(s) and $warnings warning(s),
            |the test can be reproduced by running from SBT (prefix it with ./bin/ if you
            |want to run from the command line):""".stripMargin
      )
      sb.append("\n\ndotc ")
      flags.all.foreach { arg =>
        if (lineLen > maxLen) {
          sb.append(delimiter)
          lineLen = 4
        }
        sb.append(arg)
        lineLen += arg.length
        sb += ' '
      }

      self match {
        case source: JointCompilationSource => {
          source.sourceFiles.map(_.getPath).foreach { path =>
            sb.append(delimiter)
            sb += '\''
            sb.append(path)
            sb += '\''
            sb += ' '
          }
          sb.toString + "\n\n"
        }
        case self: SeparateCompilationSource => {
          val command = sb.toString
          val fsb = new StringBuilder(command)
          self.compilationGroups.foreach { files =>
            files.map(_.getPath).foreach { path =>
              fsb.append(delimiter)
              lineLen = 8
              fsb += '\''
              fsb.append(path)
              fsb += '\''
              fsb += ' '
            }
            fsb.append("\n\n")
            fsb.append(command)
          }
          fsb.toString + "\n\n"
        }
      }
    }
  }

  /** A group of files that may all be compiled together, with the same flags
   *  and output directory
   */
  private case class JointCompilationSource(
    name: String,
    files: Array[JFile],
    flags: TestFlags,
    outDir: JFile,
    fromTasty: Boolean = false,
    decompilation: Boolean = false
  ) extends TestSource {
    def sourceFiles: Array[JFile] = files.filter(isSourceFile)

    override def toString() = outDir.toString
  }

  /** A test source whose files will be compiled separately according to their
   *  suffix `_X`
   */
  case class SeparateCompilationSource(
    name: String,
    dir: JFile,
    flags: TestFlags,
    outDir: JFile
  ) extends TestSource {

    /** Get the files grouped by `_X` as a list of groups, files missing this
     *  suffix will be put into the same group.
     *  Files in each group are sorted alphabetically.
     *
     *  Filters out all none source files
     */
    def compilationGroups: List[Array[JFile]] =
      dir
      .listFiles
      .groupBy { file =>
        val name = file.getName
        Try {
          val potentialNumber = name
            .substring(0, name.lastIndexOf('.'))
            .reverse.takeWhile(_ != '_').reverse

          potentialNumber.toInt.toString
        }
        .toOption
        .getOrElse("")
      }
      .toList.sortBy(_._1).map(_._2.filter(isSourceFile).sorted)

    def sourceFiles: Array[JFile] = compilationGroups.flatten.toArray
  }

  private trait CompilationLogic { this: Test =>
    def suppressErrors = false

    /**
     * Compiles the test source.
     * @return The reporters containing the results of all the compilation runs for this test source.
     */
    private final def compileTestSource(testSource: TestSource): Try[List[TestReporter]] =
      Try(testSource match {
        case testSource @ JointCompilationSource(name, files, flags, outDir, fromTasty, decompilation) =>
          val reporter =
            if (fromTasty) compileFromTasty(flags, suppressErrors, outDir)
            else compile(testSource.sourceFiles, flags, suppressErrors, outDir)
          List(reporter)

        case testSource @ SeparateCompilationSource(_, dir, flags, outDir) =>
          testSource.compilationGroups.map(files => compile(files, flags, suppressErrors, outDir))  // TODO? only `compile` option?
      })

    final def countErrorsAndWarnings(reporters: Seq[TestReporter]): (Int, Int) =
      reporters.foldLeft((0, 0)) { case ((err, warn), r) => (err + r.errorCount, warn + r.warningCount) }

    final def countErrors  (reporters: Seq[TestReporter]) = countErrorsAndWarnings(reporters)._1
    final def countWarnings(reporters: Seq[TestReporter]) = countErrorsAndWarnings(reporters)._2
    final def reporterFailed(r: TestReporter) = r.compilerCrashed || r.errorCount > 0

    /**
     * For a given test source, returns a check file against which the result of the test run
     * should be compared. Is used by implementations of this trait.
     */
    final def checkFile(testSource: TestSource): Option[JFile] = (testSource match {
      case ts: JointCompilationSource =>
        ts.files.collectFirst { case f if !f.isDirectory => new JFile(f.getPath.replaceFirst("\\.scala$", ".check")) }

      case ts: SeparateCompilationSource =>
        Option(new JFile(ts.dir.getPath + ".check"))
    }).filter(_.exists)

    /**
     * Checks if the given actual lines are the same as the ones in the check file.
     * If not, fails the test.
     */
    final def diffTest(testSource: TestSource, checkFile: JFile, actual: List[String], reporters: Seq[TestReporter], logger: LoggedRunnable) = {
      for (msg <- FileDiff.check(testSource.title, actual, checkFile.getPath)) {
        onFailure(testSource, reporters, logger, Some(msg))

        if (updateCheckFiles) {
          FileDiff.dump(checkFile.toPath.toString, actual)
          echo("Updated checkfile: " + checkFile.getPath)
        } else {
          val outFile = checkFile.toPath.resolveSibling(s"${checkFile.toPath.getFileName}.out").toString
          FileDiff.dump(outFile, actual)
          echo(FileDiff.diffMessage(checkFile.getPath, outFile))
        }
      }
    }

    /** Entry point: runs the test */
    final def encapsulatedCompilation(testSource: TestSource) = new LoggedRunnable { self =>
      def checkTestSource(): Unit = tryCompile(testSource) {
        val reportersOrCrash = compileTestSource(testSource)
        onComplete(testSource, reportersOrCrash, self)
        registerCompletion()
      }
    }

    /** This callback is executed once the compilation of this test source finished */
    private final def onComplete(testSource: TestSource, reportersOrCrash: Try[Seq[TestReporter]], logger: LoggedRunnable): Unit =
      reportersOrCrash match {
        case TryFailure(exn) => onFailure(testSource, Nil, logger, Some(s"Fatal compiler crash when compiling: ${testSource.title}:\n${exn.getMessage}\n${exn.getStackTrace.mkString("\n")}"))
        case TrySuccess(reporters) => maybeFailureMessage(testSource, reporters) match {
          case Some(msg) => onFailure(testSource, reporters, logger, Option(msg).filter(_.nonEmpty))
          case None => onSuccess(testSource, reporters, logger)
        }
      }

    /**
     * Based on the reporters obtained after the compilation, determines if this test has failed.
     * If it has, returns a Some with an error message. Otherwise, returns None.
     * As the conditions of failure are different for different test types, this method should be
     * overridden by the concrete implementations of this trait.
     */
    def maybeFailureMessage(testSource: TestSource, reporters: Seq[TestReporter]): Option[String] =
      if (reporters.exists(reporterFailed)) Some(s"Compilation failed for: '${testSource.title}'")
      else None

    /**
     * If the test has compiled successfully, this callback will be called. You can still fail the test from this callback.
     */
    def onSuccess(testSource: TestSource, reporters: Seq[TestReporter], logger: LoggedRunnable): Unit = ()

    /**
     * If the test failed to compile or the compiler crashed, this callback will be called.
     */
    def onFailure(testSource: TestSource, reporters: Seq[TestReporter], logger: LoggedRunnable, message: Option[String]): Unit = {
      message.foreach(echo)
      reporters.filter(reporterFailed).foreach(logger.logReporterContents)
      logBuildInstructions(testSource, reporters)
      failTestSource(testSource)
    }
  }

  /** Each `Test` takes the `testSources` and performs the compilation and assertions
   *  according to the implementing class "neg", "run" or "pos".
   */
  private class Test(testSources: List[TestSource], times: Int, threadLimit: Option[Int], suppressAllOutput: Boolean)(implicit val summaryReport: SummaryReporting) extends CompilationLogic { test =>

    import summaryReport._

    protected final val realStdout = System.out
    protected final val realStderr = System.err

    /** A runnable that logs its contents in a buffer */
    trait LoggedRunnable extends Runnable {
      /** Instances of `LoggedRunnable` implement this method instead of the
       *  `run` method
       */
      def checkTestSource(): Unit

      private val logBuffer = mutable.ArrayBuffer.empty[String]
      def log(msg: String): Unit = logBuffer.append(msg)

      def logReporterContents(reporter: TestReporter): Unit =
        reporter.messages.foreach(log)

      def echo(msg: String): Unit = {
        log(msg)
        test.echo(msg)
      }

      final def run(): Unit = {
        checkTestSource()
        summaryReport.echoToLog(logBuffer.iterator)
      }
    }

    /** All testSources left after filtering out */
    private val filteredSources =
      if (!testFilter.isDefined) testSources
      else testSources.filter {
        case JointCompilationSource(_, files, _, _, _, _) =>
          files.exists(file => file.getPath.contains(testFilter.get))
        case SeparateCompilationSource(_, dir, _, _) =>
          dir.getPath.contains(testFilter.get)
      }

    /** Total amount of test sources being compiled by this test */
    val sourceCount = filteredSources.length

    private var _testSourcesCompleted = 0
    private def testSourcesCompleted: Int = _testSourcesCompleted

    /** Complete the current compilation with the amount of errors encountered */
    protected final def registerCompletion() = synchronized {
      _testSourcesCompleted += 1
    }

    sealed trait Failure
    case class JavaCompilationFailure(reason: String) extends Failure
    case class TimeoutFailure(title: String) extends Failure
    case object Generic extends Failure

    private var _failures = Set.empty[Failure]
    private var _failureCount = 0

    /** Fail the current test */
    protected final def fail(failure: Failure = Generic): Unit = synchronized {
      _failures = _failures + failure
      _failureCount = _failureCount + 1
    }
    def didFail: Boolean = _failureCount != 0

    /** A set of the different failures */
    def failureReasons: Set[Failure] = _failures

    /** Number of failed tests */
    def failureCount: Int = _failureCount

    protected def logBuildInstructions(testSource: TestSource, reporters: Seq[TestReporter]) = {
      val (errCount, warnCount) = countErrorsAndWarnings(reporters)
      val errorMsg = testSource.buildInstructions(errCount, warnCount)
      addFailureInstruction(errorMsg)
    }

    /** Instructions on how to reproduce failed test source compilations */
    private val reproduceInstructions = mutable.ArrayBuffer.empty[String]
    protected final def addFailureInstruction(ins: String): Unit =
      synchronized { reproduceInstructions.append(ins) }

    /** The test sources that failed according to the implementing subclass */
    private val failedTestSources = mutable.ArrayBuffer.empty[String]
    protected final def failTestSource(testSource: TestSource, reason: Failure = Generic) = synchronized {
      val extra = reason match {
        case TimeoutFailure(title) => s", test '$title' timed out"
        case JavaCompilationFailure(msg) => s", java test sources failed to compile with: \n$msg"
        case Generic => ""
      }
      failedTestSources.append(testSource.title + s" failed" + extra)
      fail(reason)
    }

    /** Prints to `System.err` if we're not suppressing all output */
    protected def echo(msg: String): Unit = if (!suppressAllOutput) {
      // pad right so that output is at least as large as progress bar line
      val paddingRight = " " * math.max(0, 80 - msg.length)
      realStderr.println(msg + paddingRight)
    }

    /** Print a progress bar for the current `Test` */
    private def updateProgressMonitor(start: Long): Unit = {
      val tCompiled = testSourcesCompleted
      if (tCompiled < sourceCount) {
        val timestamp = (System.currentTimeMillis - start) / 1000
        val progress = (tCompiled.toDouble / sourceCount * 40).toInt

        realStdout.print(
          "[" + ("=" * (math.max(progress - 1, 0))) +
            (if (progress > 0) ">" else "") +
            (" " * (39 - progress)) +
            s"] completed ($tCompiled/$sourceCount, $failureCount failed, ${timestamp}s)\r"
        )
      }
    }

    /** Wrapper function to make sure that the compiler itself did not crash -
     *  if it did, the test should automatically fail.
     */
    protected def tryCompile(testSource: TestSource)(op: => Unit): Unit =
      try op
      catch
        case e: Throwable =>
          // if an exception is thrown during compilation, the complete test
          // run should fail
          failTestSource(testSource)
          e.printStackTrace()
          registerCompletion()
          throw e

    protected def compile(files0: Array[JFile], flags0: TestFlags, suppressErrors: Boolean, targetDir: JFile): TestReporter = {
      val flags = flags0.and("-d", targetDir.getPath)
        .withClasspath(targetDir.getPath)

      def flattenFiles(f: JFile): Array[JFile] =
        if (f.isDirectory) f.listFiles.flatMap(flattenFiles)
        else Array(f)

      val files: Array[JFile] = files0.flatMap(flattenFiles)

      def compileWithJavac(fs: Array[String]) = if (fs.nonEmpty) {
        val fullArgs = Array(
          "javac",
          "-encoding", "UTF-8",
        ) ++ flags.javacFlags ++ fs

        val process = Runtime.getRuntime.exec(fullArgs)
        val output = Source.fromInputStream(process.getErrorStream).mkString

        if (process.waitFor() != 0) Some(output)
        else None
      } else None

      val reporter =
        TestReporter.reporter(realStdout, logLevel =
          if (suppressErrors || suppressAllOutput) ERROR + 1 else ERROR)

      val driver =
        if (times == 1) new Driver
        else new Driver {
          private def ntimes(n: Int)(op: Int => Reporter): Reporter =
            (1 to n).foldLeft(emptyReporter) ((_, i) => op(i))

          override def doCompile(comp: Compiler, files: List[String])(implicit ctx: Context) =
            ntimes(times) { run =>
              val start = System.nanoTime()
              val rep = super.doCompile(comp, files)
              ctx.echo(s"\ntime run $run: ${(System.nanoTime - start) / 1000000}ms")
              rep
            }
        }

      val allArgs = flags.all

      // If a test contains a Java file that cannot be parsed by Dotty's Java source parser, its
      // name must contain the string "JAVA_ONLY".
      val dottyFiles = files.filterNot(_.getName.contains("JAVA_ONLY")).map(_.getPath)
      driver.process(allArgs ++ dottyFiles, reporter = reporter)

      val javaFiles = files.filter(_.getName.endsWith(".java")).map(_.getPath)
      val javaErrors = compileWithJavac(javaFiles)

      if (javaErrors.isDefined) {
        echo(s"\njava compilation failed: \n${ javaErrors.get }")
        fail(failure = JavaCompilationFailure(javaErrors.get))
      }

      reporter
    }

    protected def compileFromTasty(flags0: TestFlags, suppressErrors: Boolean, targetDir: JFile): TestReporter = {
      val tastyOutput = new JFile(targetDir.getPath + "_from-tasty")
      tastyOutput.mkdir()
      val flags = flags0 and ("-d", tastyOutput.getPath) and "-from-tasty"

      def tastyFileToClassName(f: JFile): String = {
        val pathStr = targetDir.toPath.relativize(f.toPath).toString.replace(JFile.separatorChar, '.')
        pathStr.stripSuffix(".tasty").stripSuffix(".hasTasty")
      }
      val classes = flattenFiles(targetDir).filter(isTastyFile).map(tastyFileToClassName)

      val reporter =
        TestReporter.reporter(realStdout, logLevel =
          if (suppressErrors || suppressAllOutput) ERROR + 1 else ERROR)

      val driver = new Driver

      driver.process(flags.all ++ classes, reporter = reporter)

      reporter
    }

    private[ParallelTesting] def executeTestSuite(): this.type = {
      assert(testSourcesCompleted == 0, "not allowed to re-use a `CompileRun`")

      if (filteredSources.nonEmpty) {
        val pool = threadLimit match {
          case Some(i) => JExecutors.newWorkStealingPool(i)
          case None => JExecutors.newWorkStealingPool()
        }

        val timer = new Timer()
        val logProgress = isInteractive && !suppressAllOutput
        val start = System.currentTimeMillis()
        if (logProgress) {
          val task = new TimerTask {
            def run(): Unit = updateProgressMonitor(start)
          }
          timer.schedule(task, 100, 200)
        }

        val eventualResults = filteredSources.map { target =>
          pool.submit(encapsulatedCompilation(target))
        }

        pool.shutdown()
        if (!pool.awaitTermination(20, TimeUnit.MINUTES)) {
          pool.shutdownNow()
          System.setOut(realStdout)
          System.setErr(realStderr)
          throw new TimeoutException("Compiling targets timed out")
        }

        eventualResults.foreach { x =>
          try x.get()
          catch {
            case ex: Exception =>
              System.err.println(ex.getMessage)
              ex.printStackTrace()
          }
        }

        if (logProgress) {
          timer.cancel()
          val timestamp = (System.currentTimeMillis - start) / 1000
          realStdout.println(
            s"[=======================================] completed ($sourceCount/$sourceCount, $failureCount failed, ${timestamp}s)"
          )
        }

        if (didFail) {
          reportFailed()
          failedTestSources.toSet.foreach(addFailedTest)
          reproduceInstructions.iterator.foreach(addReproduceInstruction)
        }
        else reportPassed()
      }
      else echo {
        testFilter
          .map(r => s"""No files matched "$r" in test""")
          .getOrElse("No tests available under target - erroneous test?")
      }

      this
    }

    /** Returns all files in directory or the file if not a directory */
    private def flattenFiles(f: JFile): Array[JFile] =
      if (f.isDirectory) f.listFiles.flatMap(flattenFiles)
      else Array(f)
  }

  private final class PosTest(testSources: List[TestSource], times: Int, threadLimit: Option[Int], suppressAllOutput: Boolean)(implicit summaryReport: SummaryReporting)
  extends Test(testSources, times, threadLimit, suppressAllOutput)

  private final class RunTest(testSources: List[TestSource], times: Int, threadLimit: Option[Int], suppressAllOutput: Boolean)(implicit summaryReport: SummaryReporting)
  extends Test(testSources, times, threadLimit, suppressAllOutput) {
    private var didAddNoRunWarning = false
    private def addNoRunWarning() = if (!didAddNoRunWarning) {
      didAddNoRunWarning = true
      summaryReport.addStartingMessage {
        """|WARNING
           |-------
           |Run tests were only compiled, not run - this is due to the `dotty.tests.norun`
           |property being set
           |""".stripMargin
      }
    }

    private def verifyOutput(checkFile: Option[JFile], dir: JFile, testSource: TestSource, warnings: Int, reporters: Seq[TestReporter], logger: LoggedRunnable) = {
      if (Properties.testsNoRun) addNoRunWarning()
      else runMain(testSource.runClassPath) match {
        case Success(output) => checkFile match {
          case Some(file) if file.exists => diffTest(testSource, file, output.linesIterator.toList, reporters, logger)
          case _ =>
        }
        case Failure(output) =>
          echo(s"Test '${testSource.title}' failed with output:")
          echo(output)
          failTestSource(testSource)
        case Timeout =>
          echo("failed because test " + testSource.title + " timed out")
          failTestSource(testSource, TimeoutFailure(testSource.title))
      }
    }

    override def onSuccess(testSource: TestSource, reporters: Seq[TestReporter], logger: LoggedRunnable) =
      verifyOutput(checkFile(testSource), testSource.outDir, testSource, countWarnings(reporters), reporters, logger)
  }

  private final class NegTest(testSources: List[TestSource], times: Int, threadLimit: Option[Int], suppressAllOutput: Boolean)(implicit summaryReport: SummaryReporting)
  extends Test(testSources, times, threadLimit, suppressAllOutput) {
    override def suppressErrors = true

    override def maybeFailureMessage(testSource: TestSource, reporters: Seq[TestReporter]): Option[String] = {
      def compilerCrashed = reporters.exists(_.compilerCrashed)
      lazy val (errorMap, expectedErrors) = getErrorMapAndExpectedCount(testSource.sourceFiles.toIndexedSeq)
      lazy val actualErrors = reporters.foldLeft(0)(_ + _.errorCount)
      def hasMissingAnnotations = getMissingExpectedErrors(errorMap, reporters.iterator.flatMap(_.errors))
      def showErrors = "-> following the errors:\n" +
        reporters.flatMap(_.allErrors.map(e => e.pos.toString + ": " + e.message)).mkString(start = "at ", sep = "\n at ", end = "")

      if (compilerCrashed) Some(s"Compiler crashed when compiling: ${testSource.title}")
      else if (actualErrors == 0) Some(s"\nNo errors found when compiling neg test $testSource")
      else if (expectedErrors == 0) Some(s"\nNo errors expected/defined in $testSource -- use // error or // nopos-error")
      else if (expectedErrors != actualErrors) Some(s"\nWrong number of errors encountered when compiling $testSource\nexpected: $expectedErrors, actual: $actualErrors " + showErrors)
      else if (hasMissingAnnotations) Some(s"\nErrors found on incorrect row numbers when compiling $testSource\n$showErrors")
      else if (!errorMap.isEmpty) Some(s"\nExpected error(s) have {<error position>=<unreported error>}: $errorMap")
      else None
    }

    override def onSuccess(testSource: TestSource, reporters: Seq[TestReporter], logger: LoggedRunnable): Unit =
      checkFile(testSource).foreach(diffTest(testSource, _, reporterOutputLines(reporters), reporters, logger))

    def reporterOutputLines(reporters: Seq[TestReporter]): List[String] =
      reporters.flatMap(_.consoleOutput.split("\n")).toList

    // In neg-tests we allow two types of error annotations,
    // "nopos-error" which doesn't care about position and "error" which
    // has to be annotated on the correct line number.
    //
    // We collect these in a map `"file:row" -> numberOfErrors`, for
    // nopos errors we save them in `"file" -> numberOfNoPosErrors`
    def getErrorMapAndExpectedCount(files: Seq[JFile]): (HashMap[String, Integer], Int) = {
      val errorMap = new HashMap[String, Integer]()
      var expectedErrors = 0
      files.filter(_.getName.endsWith(".scala")).foreach { file =>
        Source.fromFile(file, "UTF-8").getLines().zipWithIndex.foreach { case (line, lineNbr) =>
          val errors = line.toSeq.sliding("// error".length).count(_.unwrap == "// error")
          if (errors > 0)
            errorMap.put(s"${file.getPath}:$lineNbr", errors)

          val noposErrors = line.toSeq.sliding("// nopos-error".length).count(_.unwrap == "// nopos-error")
          if (noposErrors > 0) {
            val nopos = errorMap.get("nopos")
            val existing: Integer = if (nopos eq null) 0 else nopos
            errorMap.put("nopos", noposErrors + existing)
          }

          val anyposErrors = line.toSeq.sliding("// anypos-error".length).count(_.unwrap == "// anypos-error")
          if (anyposErrors > 0) {
            val anypos = errorMap.get("anypos")
            val existing: Integer = if (anypos eq null) 0 else anypos
            errorMap.put("anypos", anyposErrors + existing)
          }

          val possibleTypos = List("//error" -> "// error", "//nopos-error" -> "// nopos-error", "//anypos-error" -> "// anypos-error")
          for ((possibleTypo, expected) <- possibleTypos) {
            if (line.contains(possibleTypo))
              echo(s"Warning: Possible typo in error tag in file ${file.getCanonicalPath}:$lineNbr: found `$possibleTypo` but expected `$expected`")
          }

          expectedErrors += anyposErrors + noposErrors + errors
        }
      }

      (errorMap, expectedErrors)
    }

    def getMissingExpectedErrors(errorMap: HashMap[String, Integer], reporterErrors: Iterator[Diagnostic]) = !reporterErrors.forall { error =>
      val pos1 = error.pos.nonInlined
      val key = if (pos1.exists) {
        def toRelative(path: String): String =  // For some reason, absolute paths leak from the compiler itself...
          path.split("/").dropWhile(_ != "tests").mkString("/")
        val fileName = toRelative(pos1.source.file.toString)
        s"$fileName:${pos1.line}"

      } else "nopos"

      val errors = errorMap.get(key)

      def missing = { echo(s"Error reported in ${pos1.source}, but no annotation found") ; false }

      if (errors ne null) {
        if (errors == 1) errorMap.remove(key)
        else errorMap.put(key, errors - 1)
        true
      }
      else if key == "nopos" then
        missing
      else
        errorMap.get("anypos") match
          case null  => missing
          case 1     => errorMap.remove("anypos") ; true
          case slack => if slack < 1 then missing
                        else errorMap.put("anypos", slack - 1) ; true
    }
  }

  private final class NoCrashTest(testSources: List[TestSource], times: Int, threadLimit: Option[Int], suppressAllOutput: Boolean)(implicit summaryReport: SummaryReporting)
  extends Test(testSources, times, threadLimit, suppressAllOutput) {
    override def suppressErrors = true
    override def maybeFailureMessage(testSource: TestSource, reporters: Seq[TestReporter]): Option[String] = None
  }


  /** The `CompilationTest` is the main interface to `ParallelTesting`, it
   *  can be instantiated via one of the following methods:
   *
   *  - `compileFile`
   *  - `compileDir`
   *  - `compileList`
   *  - `compileFilesInDir`
   *  - `compileShallowFilesInDir`
   *
   *  Each compilation test can then be turned into either a "pos", "neg" or
   *  "run" test:
   *
   *  ```
   *  compileFile("tests/pos/i1103.scala", opts).pos()
   *  ```
   *
   *  These tests can be customized before calling one of the execution
   *  methods, for instance:
   *
   *  ```
   *  compileFile("tests/pos/i1103.scala", opts).times(2).verbose.pos()
   *  ```
   *
   *  Which would compile `i1103.scala` twice with the verbose flag as a "pos"
   *  test.
   *
   *  pos tests
   *  =========
   *  Pos tests verify that the compiler is able to compile the given
   *  `TestSource`s and that they generate no errors or exceptions during
   *  compilation
   *
   *  neg tests
   *  =========
   *  Neg tests are expected to generate a certain amount of errors - but not
   *  crash the compiler. In each `.scala` file, you specify the line on which
   *  the error will be generated, e.g:
   *
   *  ```
   *  val x: String = 1 // error
   *  ```
   *
   *  if a line generates multiple errors, you need to annotate it multiple
   *  times. For a line that generates two errors:
   *
   *  ```
   *  val y: String = { val y1: String = 1; 2 } // error // error
   *  ```
   *
   *  Certain errors have no position, if you need to check these annotate the
   *  file anywhere with `// nopos-error`
   *
   *  run tests
   *  =========
   *  Run tests are a superset of pos tests, they both verify compilation and
   *  that the compiler does not crash. In addition, run tests verify that the
   *  tests are able to run as expected.
   *
   *  Run tests need to have the following form:
   *
   *  ```
   *  object Test {
   *    def main(args: Array[String]): Unit = ()
   *  }
   *  ```
   *
   *  This is because the runner instantiates the `Test` class and calls the
   *  main method.
   *
   *  Other definitions are allowed in the same file, but the file needs to at
   *  least have the `Test` object with a `main` method.
   *
   *  To verify output you may use `.check` files. These files should share the
   *  name of the file or directory that they are testing. For instance:
   *
   *  ```none
   *  .
   *  └── tests
   *      ├── i1513.scala
   *      └── i1513.check
   *  ```
   *
   *  If you are testing a directory under separate compilation, you would
   *  have:
   *
   *  ```none
   *  .
   *  └── tests
   *      ├── myTestDir
   *      │   ├── T_1.scala
   *      │   ├── T_2.scala
   *      │   └── T_3.scala
   *      └── myTestDir.check
   *  ```
   *
   *  In the above example, `i1513.scala` and one of the files `T_X.scala`
   *  would contain a `Test` object with a main method.
   *
   *  Composing tests
   *  ===============
   *  Since this is a parallel test suite, it is essential to be able to
   *  compose tests to take advantage of the concurrency. This is done using
   *  the `+` function. This function will make sure that tests being combined
   *  are compatible according to the `require`s in `+`.
   */
  final class CompilationTest private (
    private[ParallelTesting] val targets: List[TestSource],
    private[ParallelTesting] val times: Int,
    private[ParallelTesting] val shouldDelete: Boolean,
    private[ParallelTesting] val threadLimit: Option[Int],
    private[ParallelTesting] val shouldFail: Boolean,
    private[ParallelTesting] val shouldSuppressOutput: Boolean
  ) {
    import org.junit.Assert.fail

    def this(target: TestSource) =
      this(List(target), 1, true, None, false, false)

    def this(targets: List[TestSource]) =
      this(targets, 1, true, None, false, false)

    /** Creates a "pos" test run, which makes sure that all tests pass
     *  compilation without generating errors and that they do not crash the
     *  compiler
     */
    def checkCompile()(implicit summaryReport: SummaryReporting): this.type = {
      val test = new PosTest(targets, times, threadLimit, shouldFail || shouldSuppressOutput).executeTestSuite()

      cleanup()

      if (!shouldFail && test.didFail) {
        fail(s"Expected no errors when compiling, failed for the following reason(s):\n${ reasonsForFailure(test) }")
      }
      else if (shouldFail && !test.didFail) {
        fail("Pos test should have failed, but didn't")
      }

      this
    }

    /** Creates a "neg" test run, which makes sure that each test generates the
     *  correct amount of errors at the correct positions. It also makes sure
     *  that none of these tests crash the compiler
     */
    def checkExpectedErrors()(implicit summaryReport: SummaryReporting): this.type = {
      val test = new NegTest(targets, times, threadLimit, shouldFail || shouldSuppressOutput).executeTestSuite()

      cleanup()

      if (shouldFail && !test.didFail) {
        fail(s"Neg test shouldn't have failed, but did. Reasons:\n${ reasonsForFailure(test) }")
      }
      else if (!shouldFail && test.didFail) {
        fail("Neg test should have failed, but did not")
      }

      this
    }

    /** Creates a "fuzzy" test run, which makes sure that each test compiles (or not) without crashing */
    def checkNoCrash()(implicit summaryReport: SummaryReporting): this.type = {
      val test = new NoCrashTest(targets, times, threadLimit, shouldSuppressOutput).executeTestSuite()

      cleanup()

      if (test.didFail) {
        fail("Fuzzy test shouldn't have crashed, but did")
      }

      this
    }

    /** Creates a "run" test run, which is a superset of "pos". In addition to
     *  making sure that all tests pass compilation and that they do not crash
     *  the compiler; it also makes sure that all tests can run with the
     *  expected output
     */
    def checkRuns()(implicit summaryReport: SummaryReporting): this.type = {
      val test = new RunTest(targets, times, threadLimit, shouldFail || shouldSuppressOutput).executeTestSuite()

      cleanup()

      if (!shouldFail && test.didFail) {
        fail(s"Run test failed, but should not, reasons:\n${ reasonsForFailure(test) }")
      }
      else if (shouldFail && !test.didFail) {
        fail("Run test should have failed, but did not")
      }

      this
    }

    /** Deletes output directories and files */
    private def cleanup(): this.type = {
      if (shouldDelete) delete()
      this
    }

    /** Extract `Failure` set and render from `Test` */
    private def reasonsForFailure(test: Test): String = {
      val failureReport =
        if (test.failureCount == 0) ""
        else s"\n  - encountered ${test.failureCount} test failures(s)"

      failureReport + test.failureReasons.collect {
        case test.TimeoutFailure(title) =>
          s"  - test '$title' timed out"
        case test.JavaCompilationFailure(msg) =>
          s"  - java compilation failed with:\n${ msg.linesIterator.map("      " + _).mkString("\n") }"
      }.mkString("\n")
    }

    /** Copies `file` to `dir` - taking into account if `file` is a directory,
     *  and if so copying recursively
     */
    private def copyToDir(dir: JFile, file: JFile): JFile = {
      val target = Paths.get(dir.getPath, file.getName)
      Files.copy(file.toPath, target, REPLACE_EXISTING)
      if (file.isDirectory) file.listFiles.map(copyToDir(target.toFile, _))
      target.toFile
    }

    /** Builds a new `CompilationTest` where we have copied the target files to
     *  the out directory. This is needed for tests that modify the original
     *  source, such as `-rewrite` tests
     */
    def copyToTarget(): CompilationTest = new CompilationTest (
      targets.map {
        case target @ JointCompilationSource(_, files, _, outDir, _, _) =>
          target.copy(files = files.map(copyToDir(outDir,_)))
        case target @ SeparateCompilationSource(_, dir, _, outDir) =>
          target.copy(dir = copyToDir(outDir, dir))
      },
      times, shouldDelete, threadLimit, shouldFail, shouldSuppressOutput
    )

    /** Builds a `CompilationTest` which performs the compilation `i` times on
     *  each target
     */
    def times(i: Int): CompilationTest =
      new CompilationTest(targets, i, shouldDelete, threadLimit, shouldFail, shouldSuppressOutput)

    /** Builds a `Compilationtest` which passes the verbose flag and logs the
     *  classpath
     */
    def verbose: CompilationTest = new CompilationTest(
      targets.map(t => t.withFlags("-verbose", "-Ylog-classpath")),
      times, shouldDelete, threadLimit, shouldFail, shouldSuppressOutput
    )

    /** Builds a `CompilationTest` which keeps the generated output files
     *
     *  This is needed for tests like `tastyBootstrap` which relies on first
     *  compiling a certain part of the project and then compiling a second
     *  part which depends on the first
     */
    def keepOutput: CompilationTest =
      new CompilationTest(targets, times, false, threadLimit, shouldFail, shouldSuppressOutput)

    /** Builds a `CompilationTest` with a limited level of concurrency with
     *  maximum `i` threads
     */
    def limitThreads(i: Int): CompilationTest =
      new CompilationTest(targets, times, shouldDelete, Some(i), shouldFail, shouldSuppressOutput)

    /** Builds a `CompilationTest` where the executed test is expected to fail
     *
     *  This behaviour is mainly needed for the tests that test the test suite.
     */
    def expectFailure: CompilationTest =
      new CompilationTest(targets, times, shouldDelete, threadLimit, true, shouldSuppressOutput)

    /** Builds a `CompilationTest` where all output is suppressed
     *
     *  This behaviour is mainly needed for the tests that test the test suite.
     */
    def suppressAllOutput: CompilationTest =
      new CompilationTest(targets, times, shouldDelete, threadLimit, shouldFail, true)

    /** Delete all output files generated by this `CompilationTest` */
    def delete(): Unit = targets.foreach(t => delete(t.outDir))

    private def delete(file: JFile): Unit = {
      if (file.isDirectory) file.listFiles.foreach(delete)
      try Files.delete(file.toPath)
      catch {
        case _: NoSuchFileException => // already deleted, everything's fine
      }
    }
  }

  object CompilationTest {

    /** Compose test targets from `tests`
      *
      *  It does this, only if the two tests are compatible. Otherwise it throws
      *  an `IllegalArgumentException`.
      *
      *  Grouping tests together like this allows us to take advantage of the
      *  concurrency offered by this test suite as each call to an executing
      *  method (`pos()` / `checkExpectedErrors()`/ `run()`) will spin up a thread pool with the
      *  maximum allowed level of concurrency. Doing this for only a few targets
      *  does not yield any real benefit over sequential compilation.
      *
      *  As such, each `CompilationTest` should contain as many targets as
      *  possible.
      */
    def aggregateTests(tests: CompilationTest*): CompilationTest = {
      assert(tests.nonEmpty)
      def aggregate(test1: CompilationTest, test2: CompilationTest) = {
        require(test1.times == test2.times, "can't combine tests that are meant to be benchmark compiled")
        require(test1.shouldDelete == test2.shouldDelete, "can't combine tests that differ on deleting output")
        require(test1.shouldFail == test2.shouldFail, "can't combine tests that have different expectations on outcome")
        require(test1.shouldSuppressOutput == test2.shouldSuppressOutput, "can't combine tests that both suppress and don't suppress output")
        new CompilationTest(test1.targets ++ test2.targets, test1.times, test1.shouldDelete, test1.threadLimit, test1.shouldFail, test1.shouldSuppressOutput)
      }
      tests.reduce(aggregate)
    }

  }

  /** Create out directory for directory `d` */
  def createOutputDirsForDir(d: JFile, sourceDir: JFile, outDir: String): JFile = {
    val targetDir = new JFile(outDir + s"${sourceDir.getName}/${d.getName}")
    targetDir.mkdirs()
    targetDir
  }

  /** Create out directory for `file` */
  private def createOutputDirsForFile(file: JFile, sourceDir: JFile, outDir: String): JFile = {
    val uniqueSubdir = file.getName.substring(0, file.getName.lastIndexOf('.'))
    val targetDir = new JFile(outDir + s"${sourceDir.getName}${JFile.separatorChar}$uniqueSubdir")
    targetDir.mkdirs()
    targetDir
  }

  /** Make sure that directory string is as expected */
  private def checkRequirements(f: String, sourceDir: JFile, outDir: String): Unit = {
    require(sourceDir.isDirectory && sourceDir.exists, "passed non-directory to `compileFilesInDir`: " + sourceDir)
    require(outDir.last == JFile.separatorChar, "please specify an `outDir` with a trailing file separator")
  }

  /** Separates directories from files and returns them as `(dirs, files)` */
  private def compilationTargets(sourceDir: JFile, fileFilter: FileFilter = FileFilter.NoFilter): (List[JFile], List[JFile]) =
    sourceDir.listFiles.foldLeft((List.empty[JFile], List.empty[JFile])) { case ((dirs, files), f) =>
      if (!fileFilter.accept(f.getName)) (dirs, files)
      else if (f.isDirectory) (f :: dirs, files)
      else if (isSourceFile(f)) (dirs, f :: files)
      else (dirs, files)
    }

  /** Compiles a single file from the string path `f` using the supplied flags */
  def compileFile(f: String, flags: TestFlags)(implicit testGroup: TestGroup): CompilationTest = {
    val sourceFile = new JFile(f)
    val parent = sourceFile.getParentFile
    val outDir =
      defaultOutputDir + testGroup + JFile.separator +
      sourceFile.getName.substring(0, sourceFile.getName.lastIndexOf('.')) + JFile.separator

    require(
      sourceFile.exists && !sourceFile.isDirectory &&
      (parent ne null) && parent.exists && parent.isDirectory,
      s"Source file: $f, didn't exist"
    )

    val target = JointCompilationSource(
      testGroup.name,
      Array(sourceFile),
      flags,
      createOutputDirsForFile(sourceFile, parent, outDir)
    )
    new CompilationTest(target)
  }

  /** Compiles a directory `f` using the supplied `flags`. This method does
   *  deep compilation, that is - it compiles all files and subdirectories
   *  contained within the directory `f`.
   *
   *  By default, files are compiled in alphabetical order. An optional seed
   *  can be used for randomization.
   */
  def compileDir(f: String, flags: TestFlags, randomOrder: Option[Int] = None, recursive: Boolean = true)(implicit testGroup: TestGroup): CompilationTest = {
    val outDir = defaultOutputDir + testGroup + JFile.separator
    val sourceDir = new JFile(f)
    checkRequirements(f, sourceDir, outDir)

    def flatten(f: JFile): Array[JFile] =
      if (f.isDirectory) {
        val files = f.listFiles
        if (recursive) files.flatMap(flatten) else files
      }
      else Array(f)

    // Sort files either alphabetically or randomly using the provided seed:
    val sortedFiles = flatten(sourceDir).sorted
    val randomized  = randomOrder match {
      case None       => sortedFiles
      case Some(seed) => new Random(seed).shuffle(sortedFiles.toList).toArray
    }

    // Directories in which to compile all containing files with `flags`:
    val targetDir = new JFile(outDir + JFile.separator + sourceDir.getName + JFile.separator)
    targetDir.mkdirs()

    val target = JointCompilationSource(s"compiling '$f' in test '$testGroup'", randomized, flags, targetDir)
    new CompilationTest(target)
  }

  /** Compiles all `files` together as a single compilation run. It is given a
   *  `testName` since files can be in separate directories and or be otherwise
   *  dissociated
   */
  def compileList(testName: String, files: List[String], flags: TestFlags)(implicit testGroup: TestGroup): CompilationTest = {
    val outDir = defaultOutputDir + testGroup + JFile.separator + testName + JFile.separator

    // Directories in which to compile all containing files with `flags`:
    val targetDir = new JFile(outDir)
    targetDir.mkdirs()
    assert(targetDir.exists, s"couldn't create target directory: $targetDir")

    val target = JointCompilationSource(s"$testName from $testGroup", files.map(new JFile(_)).toArray, flags, targetDir)

    // Create a CompilationTest and let the user decide whether to execute a pos or a neg test
    new CompilationTest(target)
  }

  /** This function compiles the files and folders contained within directory
   *  `f` in a specific way.
   *
   *  - Each file is compiled separately as a single compilation run
   *  - Each directory is compiled as a `SeparateCompilationTarget`, in this
   *    target all files are grouped according to the file suffix `_X` where `X`
   *    is a number. These groups are then ordered in ascending order based on
   *    the value of `X` and each group is compiled one after the other.
   *
   *  For this function to work as expected, we use the same convention for
   *  directory layout as the old partest. That is:
   *
   *  - Single files can have an associated check-file with the same name (but
   *    with file extension `.check`)
   *  - Directories can have an associated check-file, where the check file has
   *    the same name as the directory (with the file extension `.check`)
   */
  def compileFilesInDir(f: String, flags: TestFlags, fileFilter: FileFilter = FileFilter.NoFilter)(implicit testGroup: TestGroup): CompilationTest = {
    val outDir = defaultOutputDir + testGroup + JFile.separator
    val sourceDir = new JFile(f)
    checkRequirements(f, sourceDir, outDir)

    val (dirs, files) = compilationTargets(sourceDir, fileFilter)

    val isPicklerTest = flags.options.contains("-Ytest-pickler")
    def ignoreDir(dir: JFile): Boolean = {
      // Pickler tests stop after pickler not producing class/tasty files. The second part of the compilation
      // will not be able to compile due to the missing artifacts from the first part.
      isPicklerTest && dir.listFiles().exists(file => file.getName.endsWith("_2.scala") || file.getName.endsWith("_2.java"))
    }
    val targets =
      files.map(f => JointCompilationSource(testGroup.name, Array(f), flags, createOutputDirsForFile(f, sourceDir, outDir))) ++
      dirs.collect { case dir if !ignoreDir(dir) => SeparateCompilationSource(testGroup.name, dir, flags, createOutputDirsForDir(dir, sourceDir, outDir)) }

    // Create a CompilationTest and let the user decide whether to execute a pos or a neg test
    new CompilationTest(targets)
  }

  /** This function compiles the files and folders contained within directory
   *  `f` in a specific way. Once compiled, they are recompiled/run from tasty as sources.
   *
   *  - Each file is compiled separately as a single compilation run
   *  - Each directory is compiled as a `SeparateCompilationTarget`, in this
   *    target all files are grouped according to the file suffix `_X` where `X`
   *    is a number. These groups are then ordered in ascending order based on
   *    the value of `X` and each group is compiled one after the other.
   *
   *  For this function to work as expected, we use the same convention for
   *  directory layout as the old partest. That is:
   *
   *  - Single files can have an associated check-file with the same name (but
   *    with file extension `.check`)
   *  - Directories can have an associated check-file, where the check file has
   *    the same name as the directory (with the file extension `.check`)
   *
   *  Tests in the first part of the tuple must be executed before the second.
   *  Both testsRequires explicit delete().
   */
  def compileTastyInDir(f: String, flags0: TestFlags, fromTastyFilter: FileFilter)(
      implicit testGroup: TestGroup): TastyCompilationTest = {
    val outDir = defaultOutputDir + testGroup + JFile.separator
    val flags = flags0 and "-Yretain-trees"
    val sourceDir = new JFile(f)
    checkRequirements(f, sourceDir, outDir)

    val (dirs, files) = compilationTargets(sourceDir, fromTastyFilter)

    val filteredFiles = testFilter match {
      case Some(str) => files.filter(_.getPath.contains(str))
      case None => files
    }

    class JointCompilationSourceFromTasty(
       name: String,
       file: JFile,
       flags: TestFlags,
       outDir: JFile,
       fromTasty: Boolean = false,
    ) extends JointCompilationSource(name, Array(file), flags, outDir, fromTasty) {

      override def buildInstructions(errors: Int, warnings: Int): String = {
        val runOrPos = if (file.getPath.startsWith(s"tests${JFile.separator}run${JFile.separator}")) "run" else "pos"
        val listName = if (fromTasty) "from-tasty" else "decompilation"
        s"""|
            |Test '$title' compiled with $errors error(s) and $warnings warning(s),
            |the test can be reproduced by running:
            |
            |  sbt "testCompilation --from-tasty $file"
            |
            |This tests can be disabled by adding `${file.getName}` to `compiler${JFile.separator}test${JFile.separator}dotc${JFile.separator}$runOrPos-$listName.blacklist`
            |
            |""".stripMargin
      }

    }

    val targets = filteredFiles.map { f =>
      val classpath = createOutputDirsForFile(f, sourceDir, outDir)
      new JointCompilationSourceFromTasty(testGroup.name, f, flags.withClasspath(classpath.getPath), classpath, fromTasty = true)
    }
    // TODO add SeparateCompilationSource from tasty?

    // Create a CompilationTest and let the user decide whether to execute a pos or a neg test
    val generateClassFiles = compileFilesInDir(f, flags0, fromTastyFilter)

    new TastyCompilationTest(
      generateClassFiles.keepOutput,
      new CompilationTest(targets).keepOutput,
      shouldDelete = true
    )
  }

  class TastyCompilationTest(step1: CompilationTest, step2: CompilationTest, shouldDelete: Boolean)(implicit testGroup: TestGroup) {

    def keepOutput: TastyCompilationTest =
      new TastyCompilationTest(step1, step2, shouldDelete)

    def checkCompile()(implicit summaryReport: SummaryReporting): this.type = {
      step1.checkCompile() // Compile all files to generate the class files with tasty
      step2.checkCompile() // Compile from tasty

      if (shouldDelete)
        CompilationTest.aggregateTests(step1, step2).delete()

      this
    }

    def checkRuns()(implicit summaryReport: SummaryReporting): this.type = {
      step1.checkCompile() // Compile all files to generate the class files with tasty
      step2.checkRuns() // Compile from tasty

      if (shouldDelete)
        CompilationTest.aggregateTests(step1, step2).delete()

      this
    }
  }

  /** This function behaves similar to `compileFilesInDir` but it ignores
   *  sub-directories and as such, does **not** perform separate compilation
   *  tests.
   */
  def compileShallowFilesInDir(f: String, flags: TestFlags)(implicit testGroup: TestGroup): CompilationTest = {
    val outDir = defaultOutputDir + testGroup + JFile.separator
    val sourceDir = new JFile(f)
    checkRequirements(f, sourceDir, outDir)

    val (_, files) = compilationTargets(sourceDir)

    val targets = files.map { file =>
      JointCompilationSource(testGroup.name, Array(file), flags, createOutputDirsForFile(file, sourceDir, outDir))
    }

    // Create a CompilationTest and let the user decide whether to execute a pos or a neg test
    new CompilationTest(targets)
  }
}

object ParallelTesting {

  def defaultOutputDir: String = "out"+JFile.separator

  def isSourceFile(f: JFile): Boolean = {
    val name = f.getName
    name.endsWith(".scala") || name.endsWith(".java")
  }

  def isTastyFile(f: JFile): Boolean =
    f.getName.endsWith(".hasTasty") || f.getName.endsWith(".tasty")
}
