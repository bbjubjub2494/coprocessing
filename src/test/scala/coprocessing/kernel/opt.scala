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
}
