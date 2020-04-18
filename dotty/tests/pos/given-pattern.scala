

class Test {
  import scala.collection.immutable.{TreeSet, HashSet}

  inline def trySummon[S, T](f: PartialFunction[S, T]) <: T = ???

  inline def setFor[T]: Set[T] = trySummon {
    case given ord: Ordering[T] => new TreeSet[T]
    case given _: Ordering[T] => new TreeSet[T]
    case _                => new HashSet[T]
  }

  def f1[T](x: Ordering[T]) = (x, x) match {
    case (given y: Ordering[T], _) => new TreeSet[T]
  }
  def f2[T](x: Ordering[T]) = {
    val xs = List(x, x, x)
    for given y: Ordering[T] <- xs
    yield new TreeSet[T]
  }
  def f3[T](x: Ordering[T]) = (x, x) match {
    case (given _: Ordering[T], _) => new TreeSet[T]
  }
  def f4[T](x: Ordering[T]) = {
    val xs = List(x, x, x)
    for given _: Ordering[T] <- xs
    yield new TreeSet[T]
  }
}