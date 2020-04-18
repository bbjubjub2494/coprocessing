class Test {
  trait A[+T]
  class B[T] extends A[T]
  class C[T] extends B[Any] with A[T]

  def foo[T](c: C[T]): Unit = c match {
    case _: B[T] => // error
  }

  def bar[T](b: B[T]): Unit = b match {
    case _: A[T] =>
  }

  def quux[T](a: A[T]): Unit = a match {
    case _: B[T] => // should be an error!!
  }

  quux(new C[Int])
}
