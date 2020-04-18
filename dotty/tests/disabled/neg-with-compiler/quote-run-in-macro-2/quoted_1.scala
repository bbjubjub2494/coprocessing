import scala.quoted._
import scala.quoted.autolift

object Macros {

  inline def foo(i: => Int): Int = ${ fooImpl('i) }
  def fooImpl(i: Expr[Int])(using QuoteContext): Expr[Int] = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)
    val y: Int = run(i)
    y
  }
}
