object Test {

  import Macros._

  def main(args: Array[String]): Unit = {
    val x = 1
    assert2(x != 0)
    assert2(x == 0)
    assert2 {
      Macros.printStack("hi")
      Macros.printStack("hi again")
      x == 0
    }
  }
}
