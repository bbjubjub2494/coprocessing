package test

import org.junit.Assert._
import org.junit.{Before, After, Test}

import scala.io.Source
import scala.sys.process.{Process, ProcessLogger}
import java.io.{File => JFile, FileNotFoundException}

class TestScripts {
  private val lineSep = util.Properties.lineSeparator
  private def doUnlessWindows(op: => Unit) =
    if (!System.getProperty("os.name").toLowerCase.contains("windows"))
      op
    else
      Console.err.println("[warn] Could not perform test, windows batch-scripts not available")

  private def executeScript(script: String): (Int, String) = {
    val sb = new StringBuilder
    val ret = Process(script) ! ProcessLogger { line => println(line); sb.append(line + "\n") }
    val output = sb.toString
    (ret, output)
  }

  private def delete(path: String) = {
    val file = new JFile(path)
    if (file.exists) file.delete()
  }

  private def deletePackages: Unit = {
    try {
      for (jar <- Source.fromFile("./.packages").getLines())
        delete(jar)

      delete("./.packages")
      delete("./compiler/src/dotty/tools/dotc/Dummy.scala")
      delete("./HelloWorld.class")
      delete("./HelloWorld$.class")
    } catch {
      case _: FileNotFoundException => ()
    }
  }

  @Before def buildUp  = deletePackages
  @After  def tearDown = deletePackages

  /** bin/dotc script should be able to build hello world and successfully
   *  execute it using dotr
   */
  @Test def buildAndRunHelloWorld = doUnlessWindows {
    val (retDotc, dotcOutput) = executeScript("./bin/dotc ./tests/pos/HelloWorld.scala")

    // Check correct output of building and running dotc
    assert(
      retDotc == 0,
      s"bin/dotc script did not run properly. Output:$lineSep$dotcOutput"
    )

    val (retDotr, dotrOutput) = executeScript("./bin/dotr HelloWorld")
    assert(
      retDotr == 0 && dotrOutput == "hello world\n",
      s"Running hello world exited with status: $retDotr and output: $dotrOutput"
    )
  }

  /** bin/dotc script should be able to detect changes in dotty sources and
   *  rebuild dotty if needed
   */
  @Test def rebuildIfNecessary = doUnlessWindows {
    val (retFirstBuild, out1) = executeScript("./bin/dotc ./tests/pos/HelloWorld.scala")
    assert(retFirstBuild == 0, s"building dotc failed: $out1")

    // Create a new file to force rebuild
    new JFile("./compiler/src/dotty/tools/dotc/Dummy.scala").createNewFile()

    val (retSecondBuild, output) = executeScript("./bin/dotc ./tests/pos/HelloWorld.scala")
    assert(
      retSecondBuild == 0 && output.contains("rebuilding"),
      s"Rebuilding the tool should result in jar files being rebuilt. Status: $retSecondBuild, output:$lineSep$output")
  }

  /** if no changes to dotty, dotc script should be fast */
  @Test def beFastOnNoChanges = doUnlessWindows {
    val (retFirstBuild, _) = executeScript("./bin/dotc ./tests/pos/HelloWorld.scala")
    assert(retFirstBuild == 0, "building dotc failed")

    val (ret, output) = executeScript("./bin/dotc ./tests/pos/HelloWorld.scala")
    assert(
      ret == 0 && !output.contains("rebuilding"),
      s"Project recompiled when it didn't need to be. Status $ret, output:$lineSep$output")
  }

  /** dotc script should work after corrupting .packages */
  @Test def reCreatesPackagesIfNecessary = doUnlessWindows {
    import java.nio.file.{Paths, Files}
    import java.nio.charset.StandardCharsets
    val contents =
      """|/Users/fixel/Projects/dotty/interfaces/target/dotty-interfaces-0.1.1-bin-SNAPSHOT-X.jar
         |/Users/fixel/Projects/dotty/compiler/target/scala-2.11/dotty-compiler_2.1X-0.1.1-bin-SNAPSHOT.jar
         |/Users/fixel/Projects/dotty/library/target/scala-2.11/dotty-library_2.1X-0.1.1-bin-SNAPSHOT.jar
         |/Users/fixel/Projects/dotty/doc-tool/target/scala-2.11/dotty-doc_2.1X-0.1.1-bin-SNAPSHOT-tests.jar"""
      .stripMargin

    Files.write(Paths.get("./.packages"), contents.getBytes(StandardCharsets.UTF_8))

    val (retFirstBuild, output) = executeScript("./bin/dotc ./tests/pos/HelloWorld.scala")
    assert(output.contains(".packages file corrupted"))
    assert(retFirstBuild == 0, "building dotc failed")
  }
}
