
class Test {

  def foo0(): implicit Int => Int = ???     // error
  val foo01: implicit (String, Int) => Int = ???  // error

  def foo1(f: Int => implicit Int): Int = ??? // error
  def foo2(): implicit Int = ??? // error
  def foo3(): Int => implicit Int = ??? // error
}
