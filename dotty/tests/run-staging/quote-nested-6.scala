import quoted._
import scala.quoted.staging._

object Test {
  given Toolbox = Toolbox.make(getClass.getClassLoader)

  def main(args: Array[String]): Unit = withQuoteContext {
    val q = '{
      type T[X] = List[X]
      val x = "foo"
      ${
        val y = 'x
        '{ val z: T[String] = List($y) }
      }
      x
    }

    println(q.show)
  }
}
