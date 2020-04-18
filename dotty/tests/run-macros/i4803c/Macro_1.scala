import scala.quoted._

object PowerMacro {
  def powerCode(x: Expr[Double], n: Expr[Long]) (using QuoteContext): Expr[Double] =
    powerCode(x, n.unliftOrError)

  def powerCode(x: Expr[Double], n: Long) (using QuoteContext): Expr[Double] =
    if (n == 0) '{1.0}
    else if (n % 2 == 0) '{ val y = $x * $x; ${powerCode('y, n / 2)} }
    else '{ $x * ${powerCode(x, n - 1)} }
}
