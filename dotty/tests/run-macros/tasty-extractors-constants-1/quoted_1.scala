import scala.quoted._
import scala.quoted.autolift



object Macros {

  implicit inline def testMacro: Unit = ${impl}

  def impl(using QuoteContext): Expr[Unit] = {

    val buff = new StringBuilder
    def stagedPrintln(x: Any): Unit = buff append java.util.Objects.toString(x) append "\n"

    Expr(3) match { case Const(n) => stagedPrintln(n) }
    '{4} match { case Const(n) => stagedPrintln(n) }
    '{"abc"} match { case Const(n) => stagedPrintln(n) }
    '{null} match { case Const(n) => stagedPrintln(n) }

    '{new Object} match { case Const(n) => println(n); case _ => stagedPrintln("OK") }

    '{print(${buff.result()})}
  }
}
