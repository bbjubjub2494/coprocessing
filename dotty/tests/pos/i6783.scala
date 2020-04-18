import scala.quoted._

def testImpl(f: Expr[(Int, Int) => Int])(using QuoteContext): Expr[Int] = Expr.betaReduce(f)('{1}, '{2})

inline def test(f: (Int, Int) => Int) = ${
  testImpl('f)
}
