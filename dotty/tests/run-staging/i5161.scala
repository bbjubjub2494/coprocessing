import scala.quoted._
import scala.quoted.staging._

object Test {
  given Toolbox = Toolbox.make(getClass.getClassLoader)

  enum Exp {
    case Int2(x: Int)
    case Add(e1: Exp, e2: Exp)
  }
  import Exp._

  def evalTest(e: Exp)(using QuoteContext): Expr[Option[Int]] = e match {
    case Int2(x) => '{ Some(${Expr(x)}) }
    case Add(e1, e2) =>
     '{
        (${evalTest(e1)}, ${evalTest(e2)}) match {
        case (Some(x), Some(y)) => Some(x+y)
        case _ => None
        }
      }
    case null => '{ None }
  }


  def main(args: Array[String]): Unit = {
    val test = Add(Int2(1), Int2(1))
    def res(using QuoteContext) = evalTest(test)
    println("run : " + run(res))
    println("show : " + withQuoteContext(res.show))
  }
}
