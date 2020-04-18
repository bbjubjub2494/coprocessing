sealed trait T
object T

case class A(x: Int, y: Int) extends T
case object B extends T

sealed trait U
case class C() extends U

object Test extends App {
  import deriving.{Mirror, EmptyProduct}

  case class AA[X >: Null <: AnyRef](x: X, y: X, z: String)

  println(summon[Mirror.ProductOf[A]].fromProduct(A(1, 2)))
  assert(summon[Mirror.SumOf[T]].ordinal(A(1, 2)) == 0)
  assert(summon[Mirror.Sum { type MirroredType = T }].ordinal(B) == 1)
  summon[Mirror.Of[A]] match {
    case m: Mirror.Product =>
      println(m.fromProduct(A(1, 2)))
  }
  summon[Mirror.Of[B.type]] match {
    case m: Mirror.Product =>
      println(m.fromProduct(EmptyProduct))
  }
  summon[Mirror.Of[T]] match {
    case m: Mirror.SumOf[T] =>
      println(m.ordinal(B))
  }
  summon[Mirror.Of[U]] match {
    case m: Mirror.SumOf[U] =>
      println(m.ordinal(C()))
  }
}
