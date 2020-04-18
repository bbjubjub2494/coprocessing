
import scala.quoted._
import scala.quoted.staging._

object Test {
  def main(args: Array[String]): Unit = {
    given Toolbox = Toolbox.make(getClass.getClassLoader)
    def classExpr(using QuoteContext) = '{
      class A {
        override def toString: String = "Foo"
      }
      new A
    }
    def classExpr2(using QuoteContext) = '{
      class A {
        override def toString: String = "Bar"
      }
      new A
    }
    println(run(classExpr))
    println(run(classExpr).getClass == run(classExpr).getClass)
    println(run(classExpr2))
    println(run(classExpr2).getClass)
  }
}
