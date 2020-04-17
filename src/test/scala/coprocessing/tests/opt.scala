/* Copyright 2020 Julie Bettens
 * This file is part of Coprocessing.

 * Coprocessing is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.

 * Coprocessing is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.

 * You should have received a copy of the GNU Lesser General Public License
 * along with Coprocessing.  If not, see <https://www.gnu.org/licenses/>.
 */
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
