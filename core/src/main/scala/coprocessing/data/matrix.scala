package coprocessing.data

import coprocessing.primitives.{_, given _}

import opaques.arrayOps._
import cats.Show

type Matrix3D = Matrix
object Matrix3D {
  def apply(n00: Scalar, n01: Scalar, n02: Scalar, n03: Scalar)
           (n10: Scalar, n11: Scalar, n12: Scalar, n13: Scalar)
           (n20: Scalar, n21: Scalar, n22: Scalar, n23: Scalar)
           (n30: Scalar, n31: Scalar, n32: Scalar, n33: Scalar): Matrix3D =
    Matrix(n00, n01, n02, n03)(n10, n11, n12, n13)(n20, n21, n22, n23)(n30, n31, n32, n33)
  val Identity: Matrix3D = IdentityMatrix
}

extension Matrix3DOps on (self: Matrix3D) {
  def toArray: IArray[Scalar] = unwrapM(self)

  def apply(v: Vector3D): Vector3D = mulMV(self, v)
  def compose(other: Matrix3D): Matrix3D = mulMM(self, other)
  def andThen(other: Matrix3D): Matrix3D = other compose self

  def  _1 = toArray( 0)
  def  _2 = toArray( 1)
  def  _3 = toArray( 2)
  def  _4 = toArray( 3)
  def  _5 = toArray( 4)
  def  _6 = toArray( 5)
  def  _7 = toArray( 6)
  def  _8 = toArray( 7)
  def  _9 = toArray( 8)
  def _10 = toArray( 9)
  def _11 = toArray(10)
  def _12 = toArray(11)
  def _13 = toArray(12)
  def _14 = toArray(13)
  def _15 = toArray(14)
  def _16 = toArray(15)
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
