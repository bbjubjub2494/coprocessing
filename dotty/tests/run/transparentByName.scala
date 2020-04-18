object Test {

  class Range(from: Int, end: Int) {
    inline def foreach(op: => Int => Unit): Unit = {
      var i = from
      while (i < end) {
        op(i)
        i += 1
      }
    }
  }
  inline def twice(op: => Int => Unit): Unit = {
    op(1)
    op(2)
  }
  inline def thrice(op: => Unit): Unit = {
    op
    op
    op
  }

  def main(args: Array[String]) = {
    var j = 0
    new Range(1, 10).foreach(j += _)
    assert(j == 45, j)
    twice { x => j = j - x }
    thrice { j = j + 1 }
    val f = (g: Int => Unit) => new Range(1, 10).foreach(g)
    f(j -= _)
    assert(j == 0, j)
    new Range(1, 10).foreach { i1 =>
      new Range(2, 11).foreach { i2 =>
        j += i1 * i2
      }
    }
  }
}
