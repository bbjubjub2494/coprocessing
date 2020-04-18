import scala.reflect.Selectable.reflectiveSelectable

object Test {
  class C { type S = String; type I }
  class D extends C { type I = Int }

  type Foo = {
    def sel0: Int
    def sel1: Int => Int
    def fun0(x: Int): Int

    def fun1(x: Int)(y: Int): Int
    def fun2(x: Int): Int => Int
    def fun3(a1: Int, a2: Int, a3: Int)
            (a4: Int, a5: Int, a6: Int)
            (a7: Int, a8: Int, a9: Int): Int

    def fun4(implicit x: Int): Int
    def fun5(x: Int)(implicit y: Int): Int

    def fun6(x: C, y: x.S): Int
    def fun7(x: C, y: x.I): Int
    def fun8(y: C): y.S
    def fun9(y: C): y.I
  }

  class FooI {
    def sel0: Int = 1
    def sel1: Int => Int = x => x
    def fun0(x: Int): Int = x

    def fun1(x: Int)(y: Int): Int = x + y
    def fun2(x: Int): Int => Int = y => x * y
    def fun3(a1: Int, a2: Int, a3: Int)
            (a4: Int, a5: Int, a6: Int)
            (a7: Int, a8: Int, a9: Int): Int = -1

    def fun4(implicit x: Int): Int = x
    def fun5(x: Int)(implicit y: Int): Int = x + y

    def fun6(x: C, y: x.S): Int = 1
    def fun7(x: C, y: x.I): Int = 2
    def fun8(y: C): y.S = "Hello"
    def fun9(y: C): y.I = 1.asInstanceOf[y.I]
  }

  def basic(x: Foo): Unit ={
    assert(x.sel0 == 1)
    assert(x.sel1(2) == 2)
    assert(x.fun0(3) == 3)

    val f = x.sel1
    assert(f(3) == 3)
  }

  def currying(x: Foo): Unit = {
    assert(x.fun1(1)(2) == 3)
    assert(x.fun2(1)(2) == 2)
    assert(x.fun3(1, 2, 3)(4, 5, 6)(7, 8, 9) == -1)
  }

  def etaExpansion(x: Foo): Unit = {
    val f0 = x.fun0(_)
    assert(f0(2) == 2)

    val f1 = x.fun0 _
    assert(f1(2) == 2)

    val f2 = x.fun1(1)(_)
    assert(f2(2) == 3)

    val f3 = x.fun1(1) _
    assert(f3(2) == 3)

    val f4 = x.fun1(1)
    assert(f4(2) == 3)
  }

  def implicits(x: Foo) = {
    implicit val y = 2
    assert(x.fun4 == 2)
    assert(x.fun5(1) == 3)
  }

  // Limited support for dependant methods
  def dependant(x: Foo) = {
    val y = new D

    assert(x.fun6(y, "Hello") == 1)
    // assert(x.fun7(y, 1) == 2) // error: No ClassTag available for x.I

    val s = x.fun8(y)
    assert((s: String) == "Hello")

    // val i = x.fun9(y) // error: rejected (blows up in pickler if not rejected)
    // assert((i: String) == "Hello") // error: Type mismatch: found: y.S(i); required: String
  }

  def main(args: Array[String]): Unit = {
    basic(new FooI)
    currying(new FooI)
    etaExpansion(new FooI)
    implicits(new FooI)
    dependant(new FooI)
  }
}
