import scala.quoted._


inline def test(inline f: (Int, Int) => Int): String = ${ impl('f) }

def impl(using QuoteContext)(f: Expr[(Int, Int) => Int]): Expr[String] = {
  Expr(f match {
    case Lambda(body) => body('{1}, '{2}).show
    case _ => f.show
  })
}
