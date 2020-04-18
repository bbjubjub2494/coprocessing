import scala.quoted._, scala.quoted.matching._
import scala.quoted.{given _}
import scala.tasty._

case class Box[T](v: T)

inline def mcr(expr: => Boolean): Unit = ${mcrProxy('expr)}

def mcrProxy(expr: Expr[Boolean])(using QuoteContext): Expr[Unit] = {
  val res = mcrImpl[Boolean]('{ (esx: Seq[Box[Boolean]]) => () }, expr)
  // println(s"Out: ${res.show}")
  res
}

def mcrImpl[T](func: Expr[Seq[Box[T]] => Unit], expr: Expr[T])(using ctx: QuoteContext, tt: Type[T]): Expr[Unit] = {
  import ctx.tasty._
  val arg = Varargs(Seq('{(Box($expr))}))
  Expr.betaReduce(func)(arg)
}