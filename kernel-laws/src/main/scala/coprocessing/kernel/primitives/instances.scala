package coprocessing.kernel.primitives

import unsafe._

import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.util.Pretty

import spire.algebra._

given Field[Scalar] = {
  import spire.std.float.{given _}
  summon[Field[Scalar]]
}

given (using Arbitrary[Scalar]) as Arbitrary[Vector] = Arbitrary(Gen.resultOf(Vector))
given (using Arbitrary[Scalar]) as Arbitrary[Matrix] = Arbitrary(Gen.resultOf(Matrix))
given (using Cogen[Array[Scalar]]) as Cogen[Vector] = Cogen[Array[Scalar]].contramap(_.unfreeze)
given (using Cogen[Array[Scalar]]) as Cogen[Matrix] = Cogen[Array[Scalar]].contramap(_.unfreeze)

given (IArray[Scalar] => Pretty) =
  _.unfreeze.mkString("[",", ","]")

given VectorSpace[Vector, Scalar] {
  val scalar = summon[Field[Scalar]]

  def negate(v: Vector) = mulSV(-1, v)
  val zero = Vector(0,0,0,0)
  def plus(u: Vector, v: Vector) = addVV(u,v)
  def timesl(a: Scalar, v: Vector) = mulSV(a,v)
}
// TODO can be a Ring once invert matrix is implemented
given Rng[Matrix] {
  def negate(m: Matrix) = mulSM(-1, m)
  val zero = Matrix(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)
  def plus(m1: Matrix, m2: Matrix) = addMM(m1, m2)
  def times(m1: Matrix, m2: Matrix) = mulMM(m1, m2)
}
