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
package coprocessing.primitives

import unsafe.freeze

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

def determinantM(m: Matrix): Scalar =
  m(0) * det3(m, 0)
  - m(1) * det3(m, 1)
  + m(2) * det3(m, 2)
  - m(3) * det3(m, 3)

def det3(m: Matrix, i: Int): Scalar =
  val j = i >> 2
  inline def get(i: Int, j: Int) =
    m(i % 4 | (j % 4) << 2)
  // Sarrus's rule
  (
    + get(i + 1, j + 1) * get(i + 2, j + 2) * get(i + 3, j + 3)
    + get(i + 1, j + 2) * get(i + 2, j + 3) * get(i + 3, j + 5)
    + get(i + 1, j + 3) * get(i + 2, j + 5) * get(i + 3, j + 6)
    - get(i + 1, j + 3) * get(i + 2, j + 2) * get(i + 3, j + 1)
    - get(i + 1, j + 5) * get(i + 2, j + 3) * get(i + 3, j + 2)
    - get(i + 1, j + 6) * get(i + 2, j + 5) * get(i + 3, j + 3)
  )

def invertM(m: Matrix): Matrix | Null =
  val d = determinantM(m)
  if d == 0 then
    null
  else
    Matrix(
      +det3(m, 0)/d, -det3(m, 4)/d, +det3(m,  8)/d, -det3(m, 12)/d,
      -det3(m, 1)/d, +det3(m, 5)/d, -det3(m,  9)/d, +det3(m, 13)/d,
      +det3(m, 2)/d, -det3(m, 6)/d, +det3(m, 10)/d, -det3(m, 14)/d,
      -det3(m, 3)/d, +det3(m, 7)/d, -det3(m, 11)/d, +det3(m, 15)/d,
    )
