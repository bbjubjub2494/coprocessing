import scala.quoted._
import scala.quoted.autolift

class LineNumber(val value: Int) {
  override def toString: String = value.toString
}

object LineNumber {

  implicit inline def line: LineNumber = ${lineImpl}

  def lineImpl(using qctx: QuoteContext) : Expr[LineNumber] = {
    import qctx.tasty._
    '{new LineNumber(${rootPosition.startLine})}
  }

}
