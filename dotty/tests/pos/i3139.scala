trait Foo[T] {
    type Base[A]
}

object Test {
  def foo[T](ev: Foo[T]): Foo[T] { type Base[A] = ev.Base[A] } = ev
}

object Test2 {
  def foo[T](ev: Foo[T]): Foo[T] { type Base = ev.Base } = ev
}
