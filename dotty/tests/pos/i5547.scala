import scala.quoted._

object scalatest {
  inline def assert1(condition: => Boolean): Unit =
   ${assertImpl('condition, '{""})}

  inline def assert2(condition: => Boolean): Unit =
    ${ assertImpl('condition, Expr("")) }

  def assertImpl(condition: Expr[Boolean], clue: Expr[Any])(using QuoteContext): Expr[Unit] =
    '{}
}
