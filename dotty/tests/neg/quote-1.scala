import scala.quoted._

class Test {

  def f[T](t: Type[T], x: Expr[T])(using QuoteContext) = '{
    val z2 = $x // error // error: wrong staging level
  }

  def g[T](implicit t: Type[T], x: Expr[T], qctx: QuoteContext) = '{
    val z2 = $x   // ok
  }

}
