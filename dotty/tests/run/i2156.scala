class Outer {

  case class Inner()

  val inner: Inner = new Inner

  def checkInstance(o: Outer) =
    o.inner.isInstanceOf[this.Inner]

  def checkPattern1(i: Any) =
    i match {
      case _: Inner => true
      case _ => false
    }

  def checkPattern2(i: Any) =
    i match {
      case Inner() => true
      case _ => false
    }

  def checkEquals(o: Outer) =
    o.inner == inner
}

object Test {

  def main(args: Array[String]) = {
    val o1 = new Outer
    val o2 = new Outer
    assert(o1.checkInstance(o2)) // ok
    assert(!o1.checkPattern1(o2.inner)) // ok under scalac, fails for dotc-compiled code
    assert(!o1.checkPattern2(o2.inner))  // ok under scalac, fails for dotc-compiled code
    assert(!o1.checkEquals(o2))  // ok under scalac, fails for dotc-compiled code
  }
}

