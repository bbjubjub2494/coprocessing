import scala.quoted._

trait IsExpr {
  class Underlying
}

val foo: IsExpr = ???

def g(e: IsExpr)(using tu: Type[e.Underlying]): Unit = ???

def mcrImpl(using QuoteContext): Unit = {
  g(foo)
}
