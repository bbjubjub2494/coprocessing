package coprocessing.core

import coprocessing.kernel.primitives.{_, given _}

import cats.Show

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
  def toArray: IArray[Scalar] = unwrapV(self)

  @annotation.infix def dot(other: Vector3D): Scalar = dotVV(self, other)

  def _1: Scalar = toArray(0)
  def _2: Scalar = toArray(1)
  def _3: Scalar = toArray(2)
  def _4: Scalar = toArray(3)

  def x = _1
  def y = _2
  def z = _3
  def w = _4

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
    Matrix(n00, n01, n02, n03)(n10, n11, n12, n13)(n20, n21, n22, n23)(n30, n31, n32, n33)
  val Identity: Matrix3D = IdentityMatrix
}

extension Matrix3DOps on (self: Matrix3D) {
  def toArray: IArray[Scalar] = unwrapM(self)

  def apply(v: Vector3D): Vector3D = mulMV(self, v)
  def compose(other: Matrix3D): Matrix3D = mulMM(self, other)
  def andThen(other: Matrix3D): Matrix3D = other compose self

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
