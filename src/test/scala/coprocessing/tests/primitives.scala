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

import coprocessing.kernel.primitives.{_, given _}
import spire.laws.{_, given _}
import cats.Eq
import cats.kernel.laws.IsEqArrow
import cats.kernel.laws.discipline.catsLawsIsEqToProp
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.arbitrary.{given _}
import cats.data.NonEmptyList
import org.scalacheck._

object PrimitivesSuite extends BaseSuite {
  // More lenient Eq[Scalar] required for some properties to pass.
  // - Infinities and NaNs are equal to everything else
  // - approximately equal values are equal
  val relaxedScalarEq = Eq.instance[Scalar] {
    (s1, s2) =>
    s1 == s2 ||
    !s1.isFinite || !s2.isFinite || {
      import math._
      abs(log(s2/s1)) < .01
    }
  }
  // More conservative Arbitrary[Scalar] that should hopefully keep float operations associative.
  val associativeArbitraryScalar: Arbitrary[Scalar] = Arbitrary(for
    s <- Gen.choose(0, 1)
    e <- Gen.choose(110, 140)
    m <- Gen.choose(0, 0x7fffff)
  yield java.lang.Float.intBitsToFloat((s << 31) | (e << 23) | m))

  checkAll("Eq[Vector]", EqTests[Vector].eqv)
  checkAll("Eq[Matrix]", EqTests[Matrix].eqv)

  test("properties of scalar matrices") {
    check2((s: Scalar, v: Vector) => mulMV(scalarMatrix(s), v) <-> mulSV(s,v))
  }

  test("properties of foldMulMs") {
    given Eq[Scalar] = relaxedScalarEq
    check1((ms: NonEmptyList[Matrix]) => foldMulMs(ms.toList.reverseIterator) <-> ms.reduceLeft(mulMM))
  }

  {
    given Eq[Scalar] = relaxedScalarEq
    given Arbitrary[Scalar] = associativeArbitraryScalar

    checkAll("Ring[Matrix]", RingLaws[Matrix].ring)
    checkAll("MultiplicativeGroup[Matrix]", RingLaws[Matrix].multiplicativeGroup)
  }

  {
    given Eq[Scalar] = relaxedScalarEq
    given Arbitrary[Scalar] = associativeArbitraryScalar

    checkAll("VectorSpace[Vector]", VectorSpaceLaws[Vector, Scalar].innerProductSpace)
  }
}
