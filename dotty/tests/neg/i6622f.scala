import scala.compiletime._

object Test {

  def main(args: Array[String]): Unit = {
    fail(println("foo")) // error
  }

  inline def fail(inline p1: Any) = error(code"failed: $p1 ...")

}
