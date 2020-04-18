import scala.quoted._
import scala.quoted.staging._
import scala.util.control.NonLocalReturns._

object Test {
  given Toolbox = Toolbox.make(getClass.getClassLoader)
  def main(args: Array[String]): Unit =
    try run {returning('{ { (x: Int) => ${ throwReturn('x) }} apply 0 })}
    catch {
      case ex: dotty.tools.dotc.reporting.Diagnostic.Error =>
        assert(ex.getMessage == "While expanding a macro, a reference to value x was used outside the scope where it was defined", ex.getMessage)
    }
}
