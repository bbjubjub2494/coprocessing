case class A(x: Int) {
  def copy(x: Int = x) = A(x)
}

// NOTE: the companion inherits a public apply method from Function1!
case class NeedsCompanion private (x: Int)

object ClashNoSig { // ok
  private def apply(x: Int) = if (x > 0) new ClashNoSig(x) else ???
  def unapply(x: ClashNoSig) = x

  ClashNoSig(2) match {
    case c @ ClashNoSig(y) => c.copy(y + c._1)
  }
}
case class ClashNoSig private (x: Int) {
  def copy(y: Int) = ClashNoSig(y)
}

object Clash {
  private def apply(x: Int) = if (x > 0) new Clash(x) else ???
}
case class Clash private (x: Int)

object ClashSig {
  private def apply(x: Int): ClashSig = if (x > 0) new ClashSig(x) else ???
}
case class ClashSig private (x: Int)

object ClashOverload {
  private def apply(x: Int): ClashOverload = if (x > 0) new ClashOverload(x) else apply("")
  def apply(x: String): ClashOverload = ???
}
case class ClashOverload private (x: Int)

object NoClashSig {
  private def apply(x: Boolean): NoClashSig = if (x) NoClashSig(1) else ???
}
case class NoClashSig private (x: Int)

object NoClashOverload {
  // needs full sig
  private def apply(x: Boolean): NoClashOverload = if (x) NoClashOverload(1) else apply("")
  def apply(x: String): NoClashOverload = ???
}
case class NoClashOverload private (x: Int)

class BaseNCP[T] {
  def apply(x: T): NoClashPoly = if (???) NoClashPoly(1) else ???
}

object NoClashPoly extends BaseNCP[Boolean]
case class NoClashPoly (x: Int)

