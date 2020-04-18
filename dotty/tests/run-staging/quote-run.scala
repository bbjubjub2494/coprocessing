import scala.quoted._
import scala.quoted.staging._

object Test {
  def main(args: Array[String]): Unit = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)
    def expr(using QuoteContext) = '{
      val a = 3
      println("foo")
      2 + a
    }
    println(run(expr))
    println(run(expr))
    println(withQuoteContext(expr.show))
  }
}
