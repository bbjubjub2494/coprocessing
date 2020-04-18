import scala.quoted._
import scala.quoted.staging._
import scala.quoted.autolift

enum Exp {
  case Num(n: Int)
  case Plus(e1: Exp, e2: Exp)
  case Var(x: String)
  case Let(x: String, e: Exp, in: Exp)
}

object Test {
  import Exp._

  def compile(e: Exp, env: Map[String, Expr[Int]], keepLets: Boolean)(using QuoteContext): Expr[Int] = {
    def compileImpl(e: Exp, env: Map[String, Expr[Int]]): Expr[Int] = e match {
      case Num(n) => n
      case Plus(e1, e2) => '{${compileImpl(e1, env)} + ${compileImpl(e2, env)}}
      case Var(x) => env(x)
      case Let(x, e, body) =>
        if (keepLets)
          '{ val y = ${compileImpl(e, env)}; ${compileImpl(body, env + (x -> 'y)) } }
        else
          compileImpl(body, env + (x -> compileImpl(e, env)))
    }
    compileImpl(e, env)
  }


  def main(args: Array[String]): Unit = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)
    val exp = Plus(Plus(Num(2), Var("x")), Num(4))
    val letExp = Let("x", Num(3), exp)

    def res1(using QuoteContext) = '{ (x: Int) => ${compile(exp, Map("x" -> 'x), false)} }


    println(withQuoteContext(res1.show))

    val fn = run(res1)
    println(fn(0))
    println(fn(2))
    println(fn(3))

    println("---")

    def res2(using QuoteContext) = compile(letExp, Map(), false)
    println(withQuoteContext(res2.show))
    println(run(res2))

    println("---")

    def res3(using QuoteContext) = compile(letExp, Map(), true)
    println(withQuoteContext(res3.show))
    println(run(res3))
  }
}
