package example

import scala.quoted._

object MyClassMaker {
  inline def make: MyClass = ${ makeImpl }
  def makeImpl(using qctx: QuoteContext): Expr[MyClass] = {
    '{
      new MyClass {
        override def toString(): String = "MyClassMaker.make.MyClass"
      }
    }
  }
}