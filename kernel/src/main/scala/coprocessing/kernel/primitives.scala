/** Primitive operations on 3D homogenous coordinate spaces.
*
* Opaque types are used to indicate matrix shape.
* Hungarian notation is needed because of type erasure.
*/
package coprocessing.kernel.primitives

import cats.kernel.Eq

/** Type of scalar values */
type Scalar = Float

/** Row-major 4x4 matrix */
opaque type Matrix <: IArray[Scalar] = IArray[Scalar]
/** 4-long column vector */
opaque type Vector <: IArray[Scalar] = IArray[Scalar]
/** Column-major 4xN */
opaque type VectorArray <: IArray[Scalar] = IArray[Scalar]
/** mutable column-major 4xN */
opaque type MVectorArray = Array[Scalar]

@inline private def dot4(v1: IArray[Scalar], off1: Int)(v2: IArray[Scalar], off2: Int): Scalar =
  v1(off1  ) * v2(off2  ) +
  v1(off1+1) * v2(off2+1) +
  v1(off1+2) * v2(off2+2) +
  v1(off1+3) * v2(off2+3)

/** Unsafe casts that are sometimes needed internally
 */
private object unsafe {
/** Obtain a immutable-typed reference to a mutable array.
 * To be used cautiously and with ownership in mind.
 */
def (self: Array[Scalar]).freeze: IArray[Scalar] =
  self.asInstanceOf[IArray[Scalar]]
/** Obtain a mutable-typed reference to an immutable array.
 * To be used VERY cautiously!
 */
def (self: IArray[Scalar]).unfreeze: Array[Scalar] =
  self.asInstanceOf[Array[Scalar]]
}

import unsafe._

/** Build a [Matrix](Matrix) from its scalar components. */
def Matrix(n00: Scalar, n01: Scalar, n02: Scalar, n03: Scalar,
           n10: Scalar, n11: Scalar, n12: Scalar, n13: Scalar,
           n20: Scalar, n21: Scalar, n22: Scalar, n23: Scalar,
           n30: Scalar, n31: Scalar, n32: Scalar, n33: Scalar): Matrix =
  IArray[Scalar](n00, n01, n02, n03,
                 n10, n11, n12, n13,
                 n20, n21, n22, n23,
                 n30, n31, n32, n33)

/** Build a [Vector](Vector) from its scalar components. */
def Vector(x: Scalar, y: Scalar, z: Scalar, w: Scalar): Vector =
  IArray[Scalar](x, y, z, w)

/** Multiples of the identity matrix */
def scalarMatrix(s: Scalar): Matrix =
  Matrix(s,0,0,0,0,s,0,0,0,0,s,0,0,0,0,s)
/** Shared instance of the identity matrix. */
final val IdentityMatrix: Matrix =
  scalarMatrix(1)

def (batch: MVectorArray).batchTransformInplace(mat: Matrix): Unit =
  for i <- 0 until batch.length by 4 do
    val x = dot4(mat,  0)(batch.freeze, i)
    val y = dot4(mat,  4)(batch.freeze, i)
    val z = dot4(mat,  8)(batch.freeze, i)
    val w = dot4(mat, 12)(batch.freeze, i)
    batch(i) = x
    batch(i+1) = y
    batch(i+2) = z
    batch(i+3) = w

def (mat: MVectorArray).transposeInplace(): Unit =
  require(mat.length == 16)
  @inline def swap(i: Int, j: Int) =
    val tmp = mat(i)
    mat(i) = mat(j)
    mat(j) = tmp
  swap( 1,  4)
  swap( 2,  8)
  swap( 3, 12)
  swap( 6,  9)
  swap( 7, 13)
  swap(11, 14)

def cloneA(a: IArray[Scalar]): Array[Scalar] =
  Array.copyOf(a.unfreeze, a.length)

def cloneV(v: Vector): MVectorArray =
  cloneA(v)

def cloneCols(m: Matrix): MVectorArray =
  val r: MVectorArray = cloneA(m)
  r.transposeInplace()  // column major
  r

def mulSA(s: Scalar, a: IArray[Scalar]): IArray[Scalar] =
  val r = cloneA(a)
  for i <- 0 until r.length do
    r(i) *= s
  r.freeze

def mulSV(s: Scalar, v: Vector): Vector =
  mulSA(s, v)

def mulSM(s: Scalar, m: Matrix): Matrix =
  mulSA(s, m)

def mulMV(m: Matrix, v: Vector): Vector =
  val r = cloneV(v)
  r.batchTransformInplace(m)
  r.freeze

def mulMM(m1: Matrix, m2: Matrix): Matrix =
  val r = cloneCols(m2)
  r.batchTransformInplace(m1)
  r.transposeInplace()  // row major
  r.freeze


def foldMulMs(i: Iterator[Matrix]): Matrix =
  if !i.hasNext then
    return IdentityMatrix
  val m0 = i.next()
  if !i.hasNext
    return m0
  val r = cloneCols(m0)
  while i.hasNext do
    r.batchTransformInplace(i.next())
  r.transposeInplace()  // row major
  r.freeze

def dotVV(v1: Vector, v2: Vector): Scalar =
  dot4(v1, 0)(v2, 0)

def addAA(a1: IArray[Scalar], a2: IArray[Scalar]): IArray[Scalar] =
  assert(a1.length == a2.length)
  val r = cloneA(a1)
  for i <- 0 until a1.length do
    r(i) += a2(i)
  r.freeze

def addVV(v1: Vector, v2: Vector): Vector =
  addAA(v1, v2)

def addMM(v1: Matrix, v2: Matrix): Matrix =
  addAA(v1, v2)

given (using Eq[Scalar]) as Eq[Vector] = Eq.instance {
    (v1, v2) => (0 until 4).forall { i => Eq.eqv(v1(i), v2(i)) }
}

given (using Eq[Scalar]) as Eq[Matrix] = Eq.instance {
    (m1, m2) => (0 until 16).forall { i => Eq.eqv(m1(i), m2(i)) }
}
