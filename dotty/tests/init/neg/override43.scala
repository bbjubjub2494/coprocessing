class Parent {
  def foo(): Int = 5
}

final class Child extends Parent {
  val a = 4

  def g() = foo()
  g() + b

  val b = 10   // error
  g()
}