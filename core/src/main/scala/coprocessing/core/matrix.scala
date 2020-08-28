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
package coprocessing.core

import coprocessing.primitives._

import cats.data.Chain, cats.Show, cats.syntax.foldable.{given _}

opaque type Vector3D = Vector
object Vector3D {
  def apply(n0: Scalar, n1: Scalar, n2: Scalar, n3: Scalar): Vector3D =
    Vector(n0, n1, n2, n3)

  val Zero = Vector3D(0,0,0,0)
  val UnitX = Vector3D(1,0,0,0)
  val UnitY = Vector3D(0,1,0,0)
  val UnitZ = Vector3D(0,0,1,0)
  val UnitW = Vector3D(0,0,0,1)
}

extension Vector3DOps on (self: Vector3D) {
  def toArray: IArray[Scalar] = self

  @annotation.infix def dot(other: Vector3D): Scalar = dotVV(self, other)

  def _1: Scalar = toArray(0)
  def _2: Scalar = toArray(1)
  def _3: Scalar = toArray(2)
  def _4: Scalar = toArray(3)

  def x: Scalar = _1
  def y: Scalar = _2
  def z: Scalar = _3
  def w: Scalar = _4

  def apply(i: Int): Scalar =
    require(i >= 0 && i < 4)
    toArray(i)
}

opaque type Matrix3D = Matrix
object Matrix3D {
  def apply(n00: Scalar, n01: Scalar, n02: Scalar, n03: Scalar)
           (n10: Scalar, n11: Scalar, n12: Scalar, n13: Scalar)
           (n20: Scalar, n21: Scalar, n22: Scalar, n23: Scalar)
           (n30: Scalar, n31: Scalar, n32: Scalar, n33: Scalar): Matrix3D =
    Matrix(n00, n01, n02, n03,n10, n11, n12, n13,n20, n21, n22, n23,n30, n31, n32, n33)
  def identity: Matrix3D = IdentityMatrix
}

extension Matrix3DOps on (self: Matrix3D) {
  def toArray: IArray[Scalar] = self

  def apply(v: Vector3D): Vector3D = mulMV(self, v)
  def compose(other: Matrix3D): Matrix3D = mulMM(self, other)
  def andThen(other: Matrix3D): Matrix3D = other compose self

  def andThen(t: Transformation): Transformation = t compose self
  def compose(t: Transformation): Transformation = t andThen self

  def  _1: Scalar = toArray( 0)
  def  _2: Scalar = toArray( 1)
  def  _3: Scalar = toArray( 2)
  def  _4: Scalar = toArray( 3)
  def  _5: Scalar = toArray( 4)
  def  _6: Scalar = toArray( 5)
  def  _7: Scalar = toArray( 6)
  def  _8: Scalar = toArray( 7)
  def  _9: Scalar = toArray( 8)
  def _10: Scalar = toArray( 9)
  def _11: Scalar = toArray(10)
  def _12: Scalar = toArray(11)
  def _13: Scalar = toArray(12)
  def _14: Scalar = toArray(13)
  def _15: Scalar = toArray(14)
  def _16: Scalar = toArray(15)

  def apply(i: Int, j: Int): Scalar =
    require(i >= 0 && i < 4)
    require(j >= 0 && j < 4)
    toArray(i*4+j)
}

given Show[Matrix3D] {
  private val Format = "Matrix3D" + "(%+.5f, %+.5f, %+.5f, %+.5f)" * 4
  def show(self: Matrix3D) =
    String.format(Format,
      self._1, self._2, self._3, self._4,
      self._5, self._6, self._7, self._8,
      self._9, self._10, self._11, self._12,
      self._13, self._14, self._15, self._16,
    ).nn
}

given Show[Vector3D] {
  private val Format = "Vector3D(%+.5f, %+.5f, %+.5f, %+.5f)"
  def show(self: Vector3D) =
    String.format(Format, self._1, self._2, self._3, self._4).nn
}

opaque type Transformation = Chain[Matrix3D]

/** Lazy composition of matrices in `andThen` order.
 */
object Transformation:
  def fromSeq(ms: Seq[Matrix3D]): Transformation =
    Chain.fromSeq(ms)
  def apply(ms: Matrix3D*) = fromSeq(ms)
  def identity: Transformation = Chain.nil
  given (using Show[Matrix3D]) as Show[Transformation]:
    def show(self: Transformation) =
      self.mkString_("Transformation(", ", ", ")")

extension TransformationOps on (self: Transformation):
  def apply(v: Vector3D): Vector3D =
    self.foldLeft(v)((v, m) => mulMV(m, v))  // TODO: optimize

  def andThen(other: Transformation): Transformation = self ++ other
  def compose(other: Transformation): Transformation = other ++ self

  def andThen(m: Matrix3D): Transformation = self :+ m
  def compose(m: Matrix3D): Transformation = m +: self

  def toMatrix: Matrix3D = foldMulMs(self.iterator)
