/** Adapted from https://github.com/sbt/sbt/blob/0.13/compile/interface/src/test/scala/xsbt/ScalaCompilerForUnitTesting.scala */
package xsbt

import xsbti.compile.SingleOutput
import java.io.File
import xsbti._
import sbt.io.IO
import xsbti.api.{ ClassLike, Def, DependencyContext }
import DependencyContext._
import xsbt.api.SameAPI
import sbt.internal.util.ConsoleLogger

import TestCallback.ExtractedClassDependencies

/**
 * Provides common functionality needed for unit tests that require compiling
 * source code using Scala compiler.
 */
class ScalaCompilerForUnitTesting {
  import scala.language.reflectiveCalls

  /**
   * Compiles given source code using Scala compiler and returns API representation
   * extracted by ExtractAPI class.
   */
  def extractApiFromSrc(src: String): Seq[ClassLike] = {
    val (Seq(tempSrcFile), analysisCallback) = compileSrcs(src)
    analysisCallback.apis(tempSrcFile)
  }

  /**
   * Compiles given source code using Scala compiler and returns API representation
   * extracted by ExtractAPI class.
   */
  def extractApisFromSrcs(reuseCompilerInstance: Boolean)(srcs: List[String]*): Seq[Seq[ClassLike]] = {
    val (tempSrcFiles, analysisCallback) = compileSrcs(srcs.toList, reuseCompilerInstance)
    tempSrcFiles.map(analysisCallback.apis)
  }

  /**
   * Extract used names from src provided as the second argument.
   * If `assertDefaultScope` is set to true it will fail if there is any name used in scope other then Default
   *
   * The purpose of the first argument is to define names that the second
   * source is going to refer to. Both files are compiled in the same compiler
   * Run but only names used in the second src file are returned.
   */
  def extractUsedNamesFromSrc(
      definitionSrc: String,
      actualSrc: String,
      assertDefaultScope: Boolean = true
  ): Map[String, Set[String]] = {
    // we drop temp src file corresponding to the definition src file
    val (Seq(_, tempSrcFile), analysisCallback) = compileSrcs(definitionSrc, actualSrc)

    if (assertDefaultScope) for {
      (className, used) <- analysisCallback.usedNamesAndScopes
      analysisCallback.TestUsedName(name, scopes) <- used
    } assert(scopes.size() == 1 && scopes.contains(UseScope.Default), s"$className uses $name in $scopes")

    val classesInActualSrc = analysisCallback.classNames(tempSrcFile).map(_._1)
    classesInActualSrc.map(className => className -> analysisCallback.usedNames(className)).toMap
  }

  /**
   * Extract used names from the last source file in `sources`.
   *
   * The previous source files are provided to successfully compile examples.
   * Only the names used in the last src file are returned.
   */
  def extractUsedNamesFromSrc(sources: String*): Map[String, Set[String]] = {
    val (srcFiles, analysisCallback) = compileSrcs(sources: _*)
    srcFiles
      .map { srcFile =>
        val classesInSrc = analysisCallback.classNames(srcFile).map(_._1)
        classesInSrc.map(className => className -> analysisCallback.usedNames(className)).toMap
      }
      .reduce(_ ++ _)
  }

  /**
   * Compiles given source code snippets (passed as Strings) using Scala compiler and returns extracted
   * dependencies between snippets. Source code snippets are identified by symbols. Each symbol should
   * be associated with one snippet only.
   *
   * Snippets can be grouped to be compiled together in the same compiler run. This is
   * useful to compile macros, which cannot be used in the same compilation run that
   * defines them.
   *
   * Symbols are used to express extracted dependencies between source code snippets. This way we have
   * file system-independent way of testing dependencies between source code "files".
   */
  def extractDependenciesFromSrcs(srcs: List[List[String]]): ExtractedClassDependencies = {
    val (_, testCallback) = compileSrcs(srcs, reuseCompilerInstance = true)

    val memberRefDeps = testCallback.classDependencies collect {
      case (target, src, DependencyByMemberRef) => (src, target)
    }
    val inheritanceDeps = testCallback.classDependencies collect {
      case (target, src, DependencyByInheritance) => (src, target)
    }
    val localInheritanceDeps = testCallback.classDependencies collect {
      case (target, src, LocalDependencyByInheritance) => (src, target)
    }
    ExtractedClassDependencies.fromPairs(memberRefDeps, inheritanceDeps, localInheritanceDeps)
  }

  def extractDependenciesFromSrcs(srcs: String*): ExtractedClassDependencies = {
    extractDependenciesFromSrcs(List(srcs.toList))
  }

  /**
   * Compiles given source code snippets written to temporary files. Each snippet is
   * written to a separate temporary file.
   *
   * Snippets can be grouped to be compiled together in the same compiler run. This is
   * useful to compile macros, which cannot be used in the same compilation run that
   * defines them.
   *
   * The `reuseCompilerInstance` parameter controls whether the same Scala compiler instance
   * is reused between compiling source groups. Separate compiler instances can be used to
   * test stability of API representation (with respect to pickling) or to test handling of
   * binary dependencies.
   *
   * The sequence of temporary files corresponding to passed snippets and analysis
   * callback is returned as a result.
   */
  def compileSrcs(groupedSrcs: List[List[String]],
    reuseCompilerInstance: Boolean): (Seq[File], TestCallback) = {
    // withTemporaryDirectory { temp =>
    {
      val temp = IO.createTemporaryDirectory
      val analysisCallback = new TestCallback
      val classesDir = new File(temp, "classes")
      classesDir.mkdir()

      lazy val commonCompilerInstanceAndCtx = prepareCompiler(classesDir, analysisCallback, classesDir.toString)

      val files = for ((compilationUnit, unitId) <- groupedSrcs.zipWithIndex) yield {
        // use a separate instance of the compiler for each group of sources to
        // have an ability to test for bugs in instability between source and pickled
        // representation of types
        val (compiler, ctx) = if (reuseCompilerInstance) commonCompilerInstanceAndCtx else
          prepareCompiler(classesDir, analysisCallback, classesDir.toString)
        val run = compiler.newRun(ctx)
        val srcFiles = compilationUnit.toSeq.zipWithIndex map {
          case (src, i) =>
            val fileName = s"Test-$unitId-$i.scala"
            prepareSrcFile(temp, fileName, src)
        }
        val srcFilePaths = srcFiles.map(srcFile => srcFile.getAbsolutePath).toList

        run.compile(srcFilePaths)

        // srcFilePaths.foreach(f => new File(f).delete)
        srcFiles
      }
      (files.flatten.toSeq, analysisCallback)
    }
  }

  def compileSrcs(srcs: String*): (Seq[File], TestCallback) = {
    compileSrcs(List(srcs.toList), reuseCompilerInstance = true)
  }

  private def prepareSrcFile(baseDir: File, fileName: String, src: String): File = {
    val srcFile = new File(baseDir, fileName)
    IO.write(srcFile, src)
    srcFile
  }

  private def prepareCompiler(outputDir: File, analysisCallback: AnalysisCallback, classpath: String = ".") = {
    val args = Array.empty[String]

    import dotty.tools.dotc.{Compiler, Driver}
    import dotty.tools.dotc.core.Contexts._

    val driver = new TestDriver
    val ctx = (new ContextBase).initialCtx.fresh.setSbtCallback(analysisCallback)
    driver.getCompiler(Array("-classpath", classpath, "-usejavacp", "-d", outputDir.getAbsolutePath), ctx)
  }

  private object ConsoleReporter extends Reporter {
    def reset(): Unit = ()
    def hasErrors: Boolean = false
    def hasWarnings: Boolean = false
    def printWarnings(): Unit = ()
    def problems(): Array[xsbti.Problem] = Array.empty
    def log(problem: xsbti.Problem): Unit = println(problem.message)
    def comment(pos: Position, msg: String): Unit = ()
    def printSummary(): Unit = ()
  }

}

