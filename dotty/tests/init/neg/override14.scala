abstract class A {
  val x = f(this)     // error
  val y = 10

  def f(a: A): Int
}

class B extends A {
  def f(a: A): Int = a.y
}
