import scala.quoted._
import scala.quoted.show.SyntaxHighlight.ANSI

object api {
  inline def (inline x: String) reflect : String =
    ${ reflImpl('x) }

  private def reflImpl(x: Expr[String])(using qctx: QuoteContext) : Expr[String] = {
    import qctx.tasty._
    Expr(x.show)
  }

  inline def (x: => String) reflectColor : String =
    ${ reflImplColor('x) }

  private def reflImplColor(x: Expr[String])(using qctx: QuoteContext) : Expr[String] = {
    import qctx.tasty._
    Expr(x.show(ANSI))
  }
}
