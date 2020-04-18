import scala.quoted._
import scala.quoted.autolift

object Foo {

  inline def inspectBody(inline i: Int): String =
    ${ inspectBodyImpl('i) }

  def inspectBodyImpl(x: Expr[Int])(using qctx: QuoteContext) : Expr[String] = {
    import qctx.tasty._
    x.unseal match {
      case Inlined(None, Nil, arg) => arg.symbol.tree.showExtractors
      case arg => arg.symbol.tree.showExtractors // TODO should all by name parameters be in an inline node?
    }
  }
}
