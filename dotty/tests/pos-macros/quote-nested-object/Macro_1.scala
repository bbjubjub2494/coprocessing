
import scala.quoted._
import scala.quoted.autolift

object Macro {


  object Implementation {

    inline def plus(inline n: Int, m: Int): Int = ${ plus('n, 'm) }

    def plus(n: Expr[Int], m: Expr[Int]) (using QuoteContext): Expr[Int] =
      if (n.unliftOrError == 0) m
      else '{ ${n} + $m }

    object Implementation2 {

      inline def plus(inline n: Int, m: Int): Int = ${ plus('n, 'm) }

      def plus(n: Expr[Int], m: Expr[Int]) (using QuoteContext): Expr[Int] =
        if (n.unliftOrError == 0) m
        else '{ ${n} + $m }
    }
  }

}
