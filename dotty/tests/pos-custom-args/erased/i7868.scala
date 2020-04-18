import scala.compiletime._

final case class Coproduct[+Set, +Value, Index <: Int](value: Value & Set, index: Index)

object Coproduct {
  opaque type +:[+A, +B] = A | B

  trait At[+Set, -Value, Index <: Int] {
    def cast: Value <:< Set
  }

  object At {

    given atHead[Head, Tail] as At[Head +: Tail, Head, 0] {
      def cast: Head <:< Head +: Tail = summon[Head <:< Head +: Tail]
    }

    given atTail[Head, Tail, Value, NextIndex <: Int]
          (using atNext: At[Tail, Value, NextIndex])
          as At[Head +: Tail, Value, S[NextIndex]]:
      val cast: Value <:< Head +: Tail = atNext.cast

    given [A](using A) as (() => A)= { () => summon[A]}
  }

  def upCast[A, B](a: A)(using erased evidence: (A <:< B) ): B = a.asInstanceOf[B]

  def from[Set, Value, Index <: Int](value: Value)(using erased at: At[Set, Value, Index]) : ValueOf[Index] ?=> Coproduct[Set, Value, Index] = {
    Coproduct[Set, Value, Index](upCast(value: Value)(using at.cast.liftCo[[X] =>> Value & X]), valueOf[Index])
  }

}

object Test extends App {
  import Coproduct._

  // Error: No singleton value available for scala.compiletime.S[scala.compiletime.S[(0 : Int)]].
  val c = from[Set = Int +: String +: Seq[Double] +: Nothing](Nil)
}
