/* Copyright 2020 Louis Bettens
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
package coprocessing.primitives

import opaques.arrayOps
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.util.Pretty

import spire.algebra._

given Signed[Scalar] =
  import spire.std.float.{given _}
  summon[Signed[Scalar]]

given Field[Scalar] =
  import spire.std.float.{given _}
  summon[Field[Scalar]]

given (using Eq[Scalar]) as Eq[Vector] = _ zip _ forall Eq.eqv
given (using Eq[Scalar]) as Eq[Matrix] = _ zip _ forall Eq.eqv

given (using Arbitrary[Scalar]) as Arbitrary[Vector] = Arbitrary(Gen.resultOf(Vector))
given (using Arbitrary[Scalar]) as Arbitrary[Matrix] = Arbitrary(Gen.resultOf(Matrix))
given (using Cogen[Array[Scalar]]) as Cogen[Vector] = Cogen[Array[Scalar]].contramap(unsafe.unfreeze)
given (using Cogen[Array[Scalar]]) as Cogen[Matrix] = Cogen[Array[Scalar]].contramap(unsafe.unfreeze)

given (IArray[Scalar] => Pretty) =
  unsafe.unfreeze(_).mkString("[",", ","]")

given InnerProductSpace[Vector, Scalar] {
  val scalar = summon[Field[Scalar]]

  def negate(v: Vector) = mulSV(-1, v)
  val zero = Vector(0,0,0,0)
  def plus(u: Vector, v: Vector) = addVV(u,v)
  def timesl(a: Scalar, v: Vector) = mulSV(a,v)

  def dot(u: Vector, v: Vector): Scalar = dotVV(u, v)
}

given Ring[Matrix] with MultiplicativeGroup[Matrix] {
  def negate(m: Matrix) = mulSM(-1, m)
  val zero = Matrix(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)
  def plus(m1: Matrix, m2: Matrix) = addMM(m1, m2)

  def one = IdentityMatrix
  def times(m1: Matrix, m2: Matrix) = mulMM(m1, m2)
  override def isZero(m: Matrix)(using Eq[Matrix]) = determinantM(m) == 0
  def div(m1: Matrix, m2: Matrix)= mulMM(m1, invertM(m2).nn)
}
