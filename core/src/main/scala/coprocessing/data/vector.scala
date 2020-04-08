package coprocessing.data

import coprocessing.primitives.{_, given _}

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

  def (self: Vector3D) toArray: IArray[Scalar] = unwrapV(self)
}

given Show[Vector3D] {
  private val Format = "Vector3D(%+.5f, %+.5f, %+.5f, %+.5f)"
  def show(v: Vector3D) =
    String.format(Format, v(0), v(1), v(2), v(3)).nn
}

@annotation.infix def (v1: Vector3D) dot (v2: Vector3D): Scalar =
  dotVV(v1, v2)
