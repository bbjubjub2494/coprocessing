object Test extends App {

  implicit object O {
    def (x: Int).em: Boolean = x > 0
  }

  assert(1.em == O.em(1))

  case class Circle(x: Double, y: Double, radius: Double)

  extension circleOps on (c: Circle):
    def circumference: Double = c.radius * math.Pi * 2

  val circle = new Circle(1, 1, 2.0)

  assert(circle.circumference == circleOps.circumference(circle))

  extension stringOps on (xs: Seq[String]):
    def longestStrings: Seq[String] =
      val maxLength = xs.map(_.length).max
      xs.filter(_.length == maxLength)

  val names = List("hi", "hello", "world")
  assert(names.longestStrings == List("hello", "world"))

  extension on [T](xs: Seq[T]):
    def second = xs.tail.head

  assert(names.longestStrings.second == "world")

  extension listListOps on [T](xs: List[List[T]]):
    def flattened = xs.foldLeft[List[T]](Nil)(_ ++ _)

  extension prepend:
    def [T](x: T) :: (xs: Seq[T]) = x +: xs

  val ss: Seq[Int] = List(1, 2, 3)
  val ss1 = 0 :: ss
  assert(ss1 == List(0, 1, 2, 3))

  assert(List(names, List("!")).flattened == names :+ "!")
  assert(Nil.flattened == Nil)

  trait SemiGroup[T]:
    def (x: T).combine(y: T): T

  trait Monoid[T] extends SemiGroup[T]:
    def unit: T

  given StringMonoid as Monoid[String]:
    def (x: String).combine(y: String): String = x.concat(y)
    def unit: String = ""

  // Abstracting over a typeclass with a context bound:
  def sum[T: Monoid](xs: List[T]): T =
    xs.foldLeft(implicitly[Monoid[T]].unit)(_.combine(_))

  println(sum(names))

  trait Ord[T]:
    def (x: T).compareTo(y: T): Int
    def (x: T) < (y: T) = x.compareTo(y) < 0
    def (x: T) > (y: T) = x.compareTo(y) > 0
    val minimum: T
  end Ord

  given Ord[Int]:
    def (x: Int).compareTo(y: Int) =
      if (x < y) -1 else if (x > y) +1 else 0
    val minimum = Int.MinValue

  given listOrd[T: Ord] as Ord[List[T]]:
    def (xs: List[T]).compareTo(ys: List[T]): Int = (xs, ys).match
      case (Nil, Nil) => 0
      case (Nil, _) => -1
      case (_, Nil) => +1
      case (x :: xs1, y :: ys1) =>
        val fst = x.compareTo(y)
        if (fst != 0) fst else xs1.compareTo(ys1)
    val minimum: List[T] = Nil
  end listOrd

  def max[T: Ord](x: T, y: T): T = if (x < y) y else x

  def max[T: Ord](xs: List[T]): T = (implicitly[Ord[T]].minimum /: xs)(max(_, _))

  println(max(List[Int]()))
  println(max(List(1, 2, 3)))

  println(max(List(1, 2, 3), List(2)))

  trait Functor[F[_]]:
    def [A, B](x: F[A]) map (f: A => B): F[B]
  end Functor

  trait Monad[F[_]] extends Functor[F]:
    def [A, B](x: F[A]) flatMap (f: A => F[B]): F[B]
    def [A, B](x: F[A]) map (f: A => B) = x.flatMap(f `andThen` pure)

    def pure[A](x: A): F[A]
  end Monad

  given listMonad as Monad[List]:
    def [A, B](xs: List[A]) flatMap (f: A => List[B]): List[B] =
      xs.flatMap(f)
    def pure[A](x: A): List[A] =
      List(x)

  given readerMonad[Ctx] as Monad[[X] =>> Ctx => X]:
    def [A, B](r: Ctx => A) flatMap (f: A => Ctx => B): Ctx => B =
      ctx => f(r(ctx))(ctx)
    def pure[A](x: A): Ctx => A =
      ctx => x

  def mapAll[F[_]: Monad, T](x: T, fs: List[T => T]): F[T] =
    fs.foldLeft(summon[Monad[F]].pure(x))((x: F[T], f: T => T) =>
      if true then summon[Monad[F]].map(x)(f)
      else if true then x.map(f)
      else x.map[T, T](f)
    )
}