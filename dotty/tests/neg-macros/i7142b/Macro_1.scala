package macros
import scala.quoted._
import scala.util.control.NonLocalReturns._

def oops(using QuoteContext): Expr[Int] =
  returning('{ { (x: Int) => ${ throwReturn('x) }} apply 0 })

inline def test = ${oops}
