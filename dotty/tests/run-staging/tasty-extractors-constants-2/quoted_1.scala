import scala.quoted._
import scala.quoted.staging._
import scala.quoted.autolift

object Macros {

  inline def testMacro: Unit = ${impl}

  def impl(using QuoteContext): Expr[Unit] = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)
    // 2 is a lifted constant
    val show1 = withQuoteContext(power(2, 3.0).show)
    val run1  = run(power(2, 3.0))

    // n is a lifted constant
    val n = 2
    val show2 = withQuoteContext(power(n, 4.0).show)
    val run2  = run(power(n, 4.0))

    // n is a constant in a quote
    val show3 = withQuoteContext(power('{2}, 5.0).show)
    val run3 =  run(power('{2}, 5.0))

    // n2 is not a constant
    def n2(using QuoteContext) = '{ println("foo"); 2 }
    val show4 = withQuoteContext(power(n2, 6.0).show)
    val run4  = run(power(n2, 6.0))

    '{
      println(${show1})
      println(${run1})
      println()
      println(${show2})
      println(${run2})
      println()
      println(${show3})
      println(${run3})
      println()
      println(${show4})
      println(${run4})
    }
  }

  def power(n: Expr[Int], x: Expr[Double])(using QuoteContext): Expr[Double] = {
    n.unlift match {
      case Some(n1) => powerCode(n1, x)
      case _ => '{ dynamicPower($n, $x) }
    }
  }

  def powerCode(n: Int, x: Expr[Double])(using QuoteContext): Expr[Double] =
    if (n == 0) '{1.0}
    else if (n == 1) x
    else if (n % 2 == 0) '{ { val y = $x * $x; ${powerCode(n / 2, 'y)} } }
    else '{ $x * ${powerCode(n - 1, x)} }

  def dynamicPower(n: Int, x: Double): Double =
    if (n == 0) 1.0
    else if (n % 2 == 0) dynamicPower(n / 2, x * x)
    else x * dynamicPower(n - 1, x)
}
