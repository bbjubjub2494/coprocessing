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

/** Primitive operations on 3D homogenous coordinate spaces.
*
* Opaque types are used to indicate matrix shape.
* Hungarian notation is needed because of type erasure.
*/
package coprocessing.kernel.primitives

import unsafe.freeze

import cats.kernel.Eq

private def dot4(v1: IArray[Scalar], off1: Int)(v2: IArray[Scalar], off2: Int): Scalar =
  v1(off1  ) * v2(off2  ) +
  v1(off1+1) * v2(off2+1) +
  v1(off1+2) * v2(off2+2) +
  v1(off1+3) * v2(off2+3)

/** Row-major 4x4 matrix */
opaque type Matrix <: IArray[Scalar] = IArray[Scalar]
/** 4-long column vector */
opaque type Vector <: IArray[Scalar] = IArray[Scalar]
/** Column-major 4xN */
opaque type VectorArray <: IArray[Scalar] = IArray[Scalar]
/** mutable column-major 4xN */
opaque type MVectorArray <: Array[Scalar] = Array[Scalar]

given (using Eq[Scalar]) as Eq[Vector] = Eq.instance {
    (v1, v2) =>
      Eq.eqv(v1(0), v2(0)) &
      Eq.eqv(v1(1), v2(1)) &
      Eq.eqv(v1(2), v2(2)) &
      Eq.eqv(v1(3), v2(3))
}

given (using Eq[Scalar]) as Eq[Matrix] = Eq.instance {
    (m1, m2) =>
      Eq.eqv(m1( 0), m2( 0)) &
      Eq.eqv(m1( 1), m2( 1)) &
      Eq.eqv(m1( 2), m2( 2)) &
      Eq.eqv(m1( 3), m2( 3)) &&
      Eq.eqv(m1( 4), m2( 4)) &
      Eq.eqv(m1( 5), m2( 5)) &
      Eq.eqv(m1( 6), m2( 6)) &
      Eq.eqv(m1( 7), m2( 7)) &&
      Eq.eqv(m1( 8), m2( 8)) &
      Eq.eqv(m1( 9), m2( 9)) &
      Eq.eqv(m1(10), m2(10)) &
      Eq.eqv(m1(11), m2(11)) &&
      Eq.eqv(m1(12), m2(12)) &
      Eq.eqv(m1(13), m2(13)) &
      Eq.eqv(m1(14), m2(14)) &
      Eq.eqv(m1(15), m2(15))
}

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
  inline def swap(i: Int, j: Int) =
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
  cloneA(v)

def cloneCols(m: Matrix): MVectorArray =
  val r: MVectorArray = cloneA(m)
  r.transposeInplace()  // column major
  r

def mulSV(s: Scalar, v: Vector): Vector =
  mulSA(s, v)

def mulSM(s: Scalar, m: Matrix): Matrix =
  mulSA(s, m)

def mulMV(m: Matrix, v: Vector): Vector =
  val r = cloneV(v)
  r.batchTransformInplace(m)
  freeze(r)

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

def addVV(v1: Vector, v2: Vector): Vector =
  addAA(v1, v2)

def addMM(v1: Matrix, v2: Matrix): Matrix =
  addAA(v1, v2)
