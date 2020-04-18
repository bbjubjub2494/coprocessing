object Test {

  implicit object O {
    def (x: String).l1 = x.length
    def l1(x: Int) = x * x
    def l2(x: String) = x.length
  }

  "".l1 // OK
  "".l2 // error
  1.l1 // error

  extension on [T](xs: List[T]) {
    def (x: Int).f1: T = ???  // error: No extension method allowed here, since collective parameters are given
    def f2[T]: T = ???        // error: T is already defined as type T
    def f3(xs: List[T]) = ??? // error: xs is already defined as value xs
  }
}