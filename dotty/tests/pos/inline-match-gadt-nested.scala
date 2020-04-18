object `inline-match-gadt-nested` {
  import scala.compiletime._

  enum Gadt[A, B] {
    case Nested(gadt: Gadt[A, Int]) extends Gadt[A, Int]
    case Simple extends Gadt[String, Int]
  }
  import Gadt._

  inline def foo[A, B](g: Gadt[A, B]): (A, B) =
    inline g match {
      case Nested(Simple) => ("", 0)
    }
}
