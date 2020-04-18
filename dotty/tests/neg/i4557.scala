class C0[A]
class C1[A, B]

object O {
  type T0 = C0
  type T1 = C0[String, Int] // error
  type T2[A] = C0[A, Int] // error

  type S0 = C1
  type S1 = C1[Int] // error

  class D0 extends T0 // error
  class D1 extends T0[Int]
  class D2 extends T0[String, Int] // error

  class E0 extends S0 // error
  class E1 extends S0[Int] // error
  class E2 extends S0[String, Int]
}
