class A { self : B =>
  val y = x
}

trait B {
  val x = 10  // error
}

class C extends A with B
