/** Primitive operations for linear algebra.
*
* Opaque types are used to indicate matrix shape.
* Hungarian notation is needed because of type erasure.
*/
package coprocessing.primitives

type Scalar = Float  /// Scalar value

opaque type Matrix = IArray[Scalar] /// Row-major 4x4 matrix
opaque type Vector = IArray[Scalar] /// 4-long column vector
opaque type VectorArray = IArray[Scalar] /// Column-major 4xN
opaque type MVectorArray = Array[Scalar] /// mutable Column-major 4xN

@inline private def dot4(v1: IArray[Scalar], off1: Int)(v2: IArray[Scalar], off2: Int): Scalar =
  v1(off1) * v2(off2) +
  v1(off1+1) * v2(off2+1) +
  v1(off1+2) * v2(off2+2) +
  v1(off1+3) * v2(off2+3)

// Obtain a immutable-typed reference to a mutable array.
// To be used cautiously and with ownership in mind.
private def (self: Array[Scalar]).freeze: IArray[Scalar] =
  self.asInstanceOf[IArray[Scalar]]
// Obtain a mutable-typed reference to an immutable array.
// To be used VERY cautiously!
private def (self: IArray[Scalar]).unfreeze: Array[Scalar] =
  self.asInstanceOf[Array[Scalar]]


def Matrix(n00: Scalar, n01: Scalar, n02: Scalar, n03: Scalar)
          (n10: Scalar, n11: Scalar, n12: Scalar, n13: Scalar)
          (n20: Scalar, n21: Scalar, n22: Scalar, n23: Scalar)
          (n30: Scalar, n31: Scalar, n32: Scalar, n33: Scalar): Matrix =
  IArray[Scalar](n00, n01, n02, n03,
                 n10, n11, n12, n13,
                 n20, n21, n22, n23,
                 n30, n31, n32, n33)

def Vector(n0: Scalar, n1: Scalar, n2: Scalar, n3: Scalar): Vector =
  IArray[Scalar](n0, n1, n2, n3)

given unwrapM as Conversion[Matrix, IArray[Scalar]] = identity
given unwrapV as Conversion[Vector, IArray[Scalar]] = identity

/** Shared instance of the identity matrix.
 */
final val IdentityMatrix: Matrix =
  Matrix(1,0,0,0)(0,1,0,0)(0,0,1,0)(0,0,0,1)

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

def cloneV(v: Vector): MVectorArray =
  val r = new MVectorArray(4)
  Array.copy(v.unfreeze, 0, r, 0, 4)
  r
def cloneM(v: Matrix): MVectorArray =
  val r = new MVectorArray(16)
  Array.copy(v.unfreeze, 0, r, 0, 16)
  r.transposeInplace()  // column major
  r

def mulMV(m: Matrix, v: Vector): Vector =
  val r = cloneV(v)
  r.batchTransformInplace(m)
  r.freeze

def mulMM(m1: Matrix, m2: Matrix): Matrix =
  val r = cloneM(m2)
  r.batchTransformInplace(m1)
  r.transposeInplace()  // row major
  r.freeze


def foldMulMs(i: Iterator[Matrix]): Matrix =
  if !i.hasNext then
    return IdentityMatrix
  val m0 = i.next()
  if !i.hasNext
    return m0
  val r = cloneM(m0)
  while i.hasNext do
    r.batchTransformInplace(i.next())
  r.transposeInplace()  // row major
  r.freeze

def dotVV(v1: Vector, v2: Vector): Scalar =
  dot4(v1, 0)(v2, 0)
