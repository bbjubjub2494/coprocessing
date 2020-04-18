
import java.nio.file.{Files, Paths}

import scala.quoted._
import scala.quoted.staging._

object Test {
  def main(args: Array[String]): Unit = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)
    def expr(using QuoteContext) = '{
      val a = 3
      println("foo")
      2 + a
    }
    println(withQuoteContext(expr.show))
    println(run(expr))
    println()

    val outDir = Paths.get("out/out-quoted-1")
    val classFile = outDir.resolve("Generated$Code$From$Quoted.class")

    Files.deleteIfExists(classFile)

    {
      implicit val settings = Toolbox.Settings.make(outDir = Some(outDir.toString))
      implicit val toolbox2: scala.quoted.staging.Toolbox = scala.quoted.staging.Toolbox.make(getClass.getClassLoader)
      println(run(expr))
      assert(Files.exists(classFile))
    }
  }
}
