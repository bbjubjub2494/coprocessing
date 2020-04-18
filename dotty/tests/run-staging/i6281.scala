import scala.quoted._
import scala.quoted.staging._

object Test extends App {

  sealed trait HList
  sealed trait HNil extends HList
  sealed trait ::[E, T <: HList] extends HList

  type STM[A, L <: HList] = L match {
    case HNil => Expr[A]
    case e :: rs => (Expr[A] => STM[e, rs]) => STM[e, rs]
  }

  type Stm[A, L <: HList] = L match {
    case HNil => A
    case e :: rs => (A => Stm[e, rs]) => Stm[e, rs]
  }

  trait Effects[L <: HList] {
    def reify[A](using Type[A]): STM[A, L] => Expr[Stm[A, L]]
    def reflect[A](using Type[A]): Expr[Stm[A, L]] => STM[A, L]
  }
  given empty as Effects[HNil] {
    def reify[A](using Type[A]) = m => m
    def reflect[A](using Type[A]) = m => m
  }
  // for reify, we need type tags for E and also strangely for L.
  implicit def cons [E, L <: HList](using Effects[L])(using Type[E])(using Type[L])(using QuoteContext): Effects[E :: L] =  new Effects[E :: L] {
    def reify[A](using Type[A])   = m => '{ k => ${ Effects[L].reify[E] {   m(   a =>    Effects[L].reflect[E](Expr.betaReduce('k)(a))) } }}
    def reflect[A](using Type[A]) = m =>    k =>    Effects[L].reflect[E] { Expr.betaReduce(m)('{ a => ${ Effects[L].reify[E](   k('a)) } })}
  }
  def Effects[L <: HList](using Effects[L]): Effects[L] = summon[Effects[L]]

  type RS = Boolean :: RS2
  type RS2 = Int :: String :: HNil

  def m(using QuoteContext): STM[Int, RS] = k => k('{42})

  implicit val toolbox: scala.quoted.staging.Toolbox = scala.quoted.staging.Toolbox.make(getClass.getClassLoader)

  withQuoteContext {
     println(Effects[RS].reify[Int] { m }.show)

     val effects = cons[Boolean, RS2](using cons[Int, String :: HNil](using cons[String, HNil](using empty)))
     println(effects.reify[Int] { m }.show)

     val res : Expr[Stm[Int, RS]] = '{ k => ${ Effects[RS2].reify[Boolean] { m(a => Effects[RS2].reflect[Boolean](Expr.betaReduce('k)(a))) }}}
     println(res.show)
  }

}
