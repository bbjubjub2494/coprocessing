import scala.quoted._

import scala.quoted.unsafe._

inline def sum(args: Int*): Int = ${ sumExpr('args) }

inline def sumShow(args: Int*): String = ${ sumExprShow('args) }

private def sumExprShow(argsExpr: Expr[Seq[Int]]) (using QuoteContext): Expr[String] =
  Expr(sumExpr(argsExpr).show)

private def sumExpr(argsExpr: Expr[Seq[Int]])(using qctx: QuoteContext) : Expr[Int] = {
  import qctx.tasty._
  UnsafeExpr.underlyingArgument(argsExpr) match {
    case Varargs(Consts(args)) => // args is of type Seq[Int]
      Expr(args.sum) // precompute result of sum
    case Varargs(argExprs) => // argExprs is of type Seq[Expr[Int]]
      val staticSum: Int = argExprs.map {
        case Const(arg) => arg
        case _ => 0
      }.sum
      val dynamicSum: Seq[Expr[Int]] = argExprs.filter {
        case Const(_) => false
        case arg => true
      }
      dynamicSum.foldLeft(Expr(staticSum))((acc, arg) => '{ $acc + $arg })
    case _ =>
      '{ $argsExpr.sum }
  }
}