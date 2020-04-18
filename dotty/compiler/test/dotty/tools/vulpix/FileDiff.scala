package dotty.tools.vulpix

import scala.io.Source
import java.io.File
import java.lang.System.{lineSeparator => EOL}
import java.nio.file.{Files, Paths}

object FileDiff {
  def diffMessage(expectFile: String, actualFile: String): String =
      s"""Test output dumped in: $actualFile
          |  See diff of the checkfile (`brew install icdiff` for colored diff)
          |    > diff $expectFile $actualFile
          |  Replace checkfile with current output
          |    > mv $actualFile $expectFile
      """.stripMargin

  def check(sourceTitle: String, outputLines: Seq[String], checkFile: String): Option[String] = {
    val checkLines =
      if (!(new File(checkFile)).exists) Nil
      else Source.fromFile(checkFile, "UTF-8").getLines().toList

    def linesMatch =
      outputLines.length == checkLines.length &&
      outputLines.lazyZip(checkLines).forall(_ == _)

    if (!linesMatch) Some(
      s"""|Output from '$sourceTitle' did not match check file. Actual output:
          |${outputLines.mkString(EOL)}
          |""".stripMargin + "\n")
    else None
  }

  def dump(path: String, content: Seq[String]): Unit = {
    val outFile = dotty.tools.io.File(path)
    outFile.writeAll(content.mkString("", EOL, EOL))
  }

  def checkAndDump(sourceTitle: String, actualLines: Seq[String], checkFilePath: String): Boolean = {
    val outFilePath = checkFilePath + ".out"
    FileDiff.check(sourceTitle, actualLines, checkFilePath) match {
      case Some(msg) =>
        FileDiff.dump(outFilePath, actualLines)
        println(msg)
        println(FileDiff.diffMessage(checkFilePath, outFilePath))
        false
      case _ =>
        val jOutFilePath = Paths.get(outFilePath)
        Files.deleteIfExists(jOutFilePath)
        true
    }
  }

}
