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
package coprocessing.kernel.primitives

import unsafe._

import cats.kernel.Eq


/** Type of scalar values */
type Scalar = Float

given Eq[Scalar] = {
  import cats.kernel.instances.float._
  summon[Eq[Scalar]]
}

def cloneA(a: IArray[Scalar]): Array[Scalar] =
  Array.copyOf(a.unfreeze, a.length)

def mulSA(s: Scalar, a: IArray[Scalar]): IArray[Scalar] =
  val r = cloneA(a)
  for i <- 0 until r.length do
    r(i) *= s
  r.freeze

def addAA(a1: IArray[Scalar], a2: IArray[Scalar]): IArray[Scalar] =
  assert(a1.length == a2.length)
  val r = cloneA(a1)
  for i <- 0 until r.length do
    r(i) += a2(i)
  r.freeze
