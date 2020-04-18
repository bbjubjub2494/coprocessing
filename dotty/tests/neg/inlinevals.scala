object Test {

  def power0(x: Double, inline n: Int): Double = ???  // error: inline modifier can only be used for parameters of inline methods

  inline def power(x: Double, inline n: Int): Double = // ok
    inline if n == 0 then ??? else ???

  inline val N = 10
  def X = 20

  inline inline val twice = 30 // error: repeated modifier

  class C(inline x: Int, private inline val y: Int) { // error // error inline modifier can only be used for parameters of inline methods (both)
    inline val foo: Int
    inline def bar: Int
  }

  power(2.0, N) // ok, since it's a by-name parameter
  power(2.0, X) // error: cannot reduce inline if

  inline val M = X  // error: rhs must be constant expression

  inline val xs = List(1, 2, 3) // error: must be a constant expression

  inline def foo(x: Int) = {

    def f(inline xs: List[Int]) = xs // error: inline modifier can only be used for parameters of inline methods

    inline val y = { println("hi"); 1 }  // ok
    inline val z = x // ok

  }

  inline def byname(inline f: => String): Int = ??? // ok

}
