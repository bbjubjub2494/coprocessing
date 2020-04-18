trait T
class B extends T
class C extends T

object A {
  given b as B
  given c as C
}

object Test extends App {
  import A._
  import A.{given B}

  val x: B = b // OK
  println(c) // error: not found

  summon[C] // error

}