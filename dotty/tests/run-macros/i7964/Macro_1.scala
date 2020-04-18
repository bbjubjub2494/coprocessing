import scala.quoted._

enum Num { // TODO derive a quoted.Unliftable
  case One
  case Two
}

inline def foo(inline num: Num): Int = ${ fooExpr('num) }

private def fooExpr(numExpr: Expr[Num]) (using QuoteContext): Expr[Int] =
  val num = numExpr match {
    case '{ Num.One } => Num.One
    case '{ Num.Two } => Num.Two
  }
  Expr(toInt(num))

private def toInt(num: Num): Int = num match {
  case Num.One => 1
  case Num.Two => 2
}
