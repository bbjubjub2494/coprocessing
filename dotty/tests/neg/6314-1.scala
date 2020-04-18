object G {
  final class X
  final class Y

  trait FooSig {
    type Type
    def apply[F[_]](fa: F[X & Y]): F[Y & Type]
  }
  val Foo: FooSig = new FooSig {
    type Type = X & Y
    def apply[F[_]](fa: F[X & Y]): F[Y & Type] = fa
  }
  type Foo = Foo.Type

  type Bar[A] = A match {
    case X & Y => String
    case Y => Int
  }

  def main(args: Array[String]): Unit = {
    val a: Bar[X & Y] = "hello" // error
    val i: Bar[Y & Foo] = Foo.apply[Bar](a)
    val b: Int = i // error
    println(b + 1)
  }
}
