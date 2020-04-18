import scala.quoted._

object Foo {

  inline def myMacro(): Unit = ${ aMacroImplementation }

  def aMacroImplementation(using QuoteContext): Expr[Unit] = '{ println("Hello") }

}