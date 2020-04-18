import scala.quoted._

object scalatest {

  inline def assertCompile(inline code: String): Unit = ${ assertImpl('code, '{compiletime.testing.typeChecks(code)}, true) }
  inline def assertNotCompile(inline code: String): Unit = ${ assertImpl('code, '{compiletime.testing.typeChecks(code)}, false) }

  def assertImpl(code: Expr[String], actual: Expr[Boolean], expect: Boolean)(using qctx: QuoteContext) : Expr[Unit] = {
    '{ assert(${Expr(expect)} == $actual) }
  }
}
