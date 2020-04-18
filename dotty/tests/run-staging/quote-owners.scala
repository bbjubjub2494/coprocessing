import quoted._
import scala.quoted.staging._

object Test {
  def main(args: Array[String]): Unit = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)
    def q(using QuoteContext) = f
    println(run(q))
    println(withQuoteContext(q.show))
  }

  def f(using QuoteContext): Expr[Int] = '{
    def ff: Int = {
      $g
    }
    ff
  }

  def g(using QuoteContext): Expr[Int] = '{
    val a = 9
    a + 0
  }
}
