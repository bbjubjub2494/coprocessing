package coprocessing.tests

import coprocessing.kernel.Opt
import cats.Eq
import cats.instances.all._
import org.scalacheck.{Arbitrary, Gen, Cogen, Prop}
import Arbitrary.arbitrary
import cats.kernel.laws.discipline.EqTests

given [A] (using Arbitrary[A]) as Arbitrary[Opt[A]] = Arbitrary {
  arbitrary[Option[A]] map {
    case Some(v) => Opt(v)
    case None => Opt.empty
  }
}
given [A] (using Cogen[A]) as Cogen[Opt[A]] = Cogen.cogenOption.contramap(_.toOption)

object OptSuite extends BaseSuite {
  {
    checkAll("Eq[Opt[Boolean]]", EqTests[Opt[Boolean]].eqv)
  }
  {
    // good to know whether .equals "just works"
    def fromUniversalEquals[A](using Eql[A,A]) =
      given Eq[A] = Eq.fromUniversalEquals
      EqTests[A]
    checkAll("Eq.fromUniversalEquals[Opt[Boolean]]", fromUniversalEquals[Opt[Boolean]].eqv)
  }
}
