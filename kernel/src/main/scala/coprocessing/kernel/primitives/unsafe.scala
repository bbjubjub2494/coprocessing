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
package coprocessing.kernel.primitives

/** Unsafe casts that are sometimes needed internally
 */
private object unsafe {
  /** Obtain a immutable-typed reference to a mutable array.
   * To be used cautiously and with ownership in mind.
   */
  def [T <: Array[Scalar]](self: T).freeze: IArray[Scalar] =
    self.asInstanceOf[IArray[Scalar]]
  /** Obtain a mutable-typed reference to an immutable array.
   * To be used VERY cautiously!
   */
  def (self: IArray[Scalar]).unfreeze: Array[Scalar] =
    self.asInstanceOf[Array[Scalar]]
}
