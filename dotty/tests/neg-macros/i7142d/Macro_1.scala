package macros
import scala.quoted._

def oops(using QuoteContext) = {
  var v = '{0};
  val q = '{ ??? match { case x => ${ v = '{x}; v } } }
  v
}
inline def test = ${oops}
