import scala.quoted._
import scala.quoted.staging._
object Test {
  def main(args: Array[String]): Unit = {
    implicit def toolbox: scala.quoted.staging.Toolbox = scala.quoted.staging.Toolbox.make(getClass.getClassLoader)

    def x(using QuoteContext): Expr[Int] = '{3}

    def f3(using QuoteContext): Expr[Int => Int] = '{
      val f: (x: Int) => Int = x => x + x
      f
    }

    println(run(Expr.betaReduce(f3)(x)))
    println(withQuoteContext(Expr.betaReduce(f3)(x).show)) // TODO improve printer
  }
}
