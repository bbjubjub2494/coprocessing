import scala.quoted._
import scala.quoted.staging._

object Test {
  given Toolbox = Toolbox.make(getClass.getClassLoader)

  def main(args: Array[String]): Unit = {
    def res(using QuoteContext) = '{
      val x: Option[Int] = Option(3)
      if (x.isInstanceOf[Some[_]]) Option(1)
      else None
    }
    println("show0 : " + withQuoteContext(res.show))
    println("run1 : " + run(res))
    println("run2 : " + run(res))
    println("show3 : " + withQuoteContext(res.show))
  }
}
