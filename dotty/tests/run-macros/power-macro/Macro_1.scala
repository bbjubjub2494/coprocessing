
import scala.quoted._

inline def power(x: Double, inline n: Int) = ${ powerCode1('x, 'n) }

private def powerCode1(using qctx: QuoteContext)(x: Expr[Double], n: Expr[Int]): Expr[Double] =
  powerCode(x, n.value)

private def powerCode(using qctx: QuoteContext)(x: Expr[Double], n: Int): Expr[Double] =
  if (n == 0) Expr(1.0)
  else if (n == 1) x
  else if (n % 2 == 0) '{ val y = $x * $x; ${ powerCode('y, n / 2) } }
  else '{ $x * ${ powerCode(x, n - 1) } }
