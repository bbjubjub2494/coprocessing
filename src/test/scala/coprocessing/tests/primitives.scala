package coprocessing.tests

import coprocessing.kernel.primitives.{_, given _}
import spire.laws.{_, given _}
import cats.Eq
import cats.kernel.laws.IsEqArrow
import cats.kernel.laws.discipline.catsLawsIsEqToProp
import cats.kernel.laws.discipline.EqTests
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
  test("properties of the identity matrix") {
    check1((v: Vector) => mulMV(IdentityMatrix, v) <-> v)
    check1((m: Matrix) => mulMM(IdentityMatrix, m) <-> m)
    check1((m: Matrix) => mulMM(m, IdentityMatrix) <-> m)
  }

  test("properties of scalar matrices") {
    check2((s: Scalar, v: Vector) => mulMV(scalarMatrix(s), v) <-> mulSV(s,v))
  }

  {
    given Eq[Scalar] = relaxedScalarEq
    // TODO: multiplicative inverse
    checkAll("Ring[Matrix]", RingLaws[Matrix].rng)
  }

  {
    given Eq[Scalar] = relaxedScalarEq
    given Arbitrary[Scalar] = associativeArbitraryScalar
    checkAll("VectorSpace[Vector]", VectorSpaceLaws[Vector, Scalar].vectorSpace)
  }
}
