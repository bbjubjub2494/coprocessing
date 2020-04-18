object `implicit-match-ambiguous-bind` {
  case class Box[T](value: T)
  implicit val ibox: Box[Int] = Box(0)
  implicit val sbox: Box[String] = Box("")
  inline def unbox = compiletime.summonFrom {
    case b: Box[t] => b.value
  }
  val unboxed = unbox // error
}
