final class Foo(val value: Int)

object Foo {
  def unapplySeq(foo: Foo): Seq[Int] = List(foo.value)
}

object Test {
  def main(args: Array[String]): Unit = {
    (new Foo(3)) match {
      case Foo(x, _: _*) =>
        assert(x == 3)
    }
  }
}
