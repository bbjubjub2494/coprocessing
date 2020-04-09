package coprocessing.data

import coprocessing.kernel.primitives.{_, given _}

import opaques.arrayOps._
import cats.Show

type Vector3D = Vector
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

  def _1 = toArray(0)
  def _2 = toArray(1)
  def _3 = toArray(2)
  def _4 = toArray(3)
}

given Show[Vector3D] {
  private val Format = "Vector3D(%+.5f, %+.5f, %+.5f, %+.5f)"
  def show(self: Vector3D) =
    String.format(Format, self._1, self._2, self._3, self._4).nn
}
