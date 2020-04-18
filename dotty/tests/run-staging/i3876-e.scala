import scala.quoted._
import scala.quoted.staging._
object Test {
  def main(args: Array[String]): Unit = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)

    def x(using QuoteContext): Expr[Int] = '{ println(); 3 }

    def f4(using QuoteContext): Expr[Int => Int] = '{
      inlineLambda
    }
    println(run(Expr.betaReduce(f4)(x)))
    println(withQuoteContext(Expr.betaReduce(f4)(x).show))
  }

  inline def inlineLambda <: Int => Int = x => x + x
}