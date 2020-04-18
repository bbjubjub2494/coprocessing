import scala.quoted._
import scala.quoted.autolift

// This test checks the correct interpretation of the inlined value class

object FInterpolation {

  implicit class FInterpolatorHelper(val sc: StringContext) extends AnyVal {
    inline def ff(arg1: Any): String = ${fInterpolation(sc, Seq('arg1))} // error // error
    inline def ff(arg1: Any, arg2: Any): String = ${fInterpolation(sc, Seq('arg1, 'arg2))} // error // error
    inline def ff(arg1: Any, arg2: Any, arg3: Any): String = ${fInterpolation(sc, Seq('arg1, 'arg2, 'arg3))} // error // error
    // ...
  }

  private def liftSeq(args: Seq[Expr[Any]])(using QuoteContext): Expr[Seq[Any]] = args match {
    case x :: xs  => '{ ($x) +: ${liftSeq(xs)}  }
    case Nil => '{Seq(): Seq[Any]}
  }

  def fInterpolation(sc: StringContext, args: Seq[Expr[Any]])(using QuoteContext): Expr[String] = {
    val str: Expr[String] = sc.parts.mkString("")
    val args1: Expr[Seq[Any]] = liftSeq(args)
    '{ $str.format($args1: _*) }
  }

  def hello = "hello"

}
