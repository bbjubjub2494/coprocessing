class A {
  object O {
    val x = 10
    class B {
      val y = n
      def f: Int = n
    }

    case class C(a: Int)
  }

  val n = 20
}

class B extends A {
  println((new O.B).f)
  O.C(4)                // error: leak due to potential length limit
  override val n = 50   // error
}