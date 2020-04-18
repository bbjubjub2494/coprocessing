package a
package b

object Givens:

  extension on [A](any: A):
    def sayHello = s"Hello, I am $any"

  extension on [B](any: B):
    def sayGoodbye = s"Goodbye, from $any"
    def saySoLong = s"So Long, from $any"

  val hello1 = 1.sayHello
  val goodbye1 = 1.sayGoodbye
  val soLong1 = 1.saySoLong

  trait Monoid[A]:
    def empty: A
    def (x: A).combine(y: A): A

  given Monoid[String]:
    def empty = ""
    def (x: String).combine(y: String) = x + y

  inline given int2String as Conversion[Int, String] = _.toString

  def foo[A](using A: Monoid[A]): A = A.combine(A.empty)(A.empty)
