import scala.quoted._
object Foo {
  inline def foo[X](x: X): Unit = ${fooImpl('x)}
  def fooImpl[X: Type](x: X)(using QuoteContext): Expr[Unit] = '{}
}
