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
package coprocessing.core

trait Sketch {
  def settings(): (size) ?=> Unit = ()
  def setup(): (frameRate) ?=> Unit = ()
  def draw(): (frameRate) ?=> Unit = ()
}

trait size {
    def size(width: Int, height: Int): Unit
}
inline def size(width: Int, height: Int)(using size) =
  summon[size].size(width, height)

trait frameRate {
    def frameRate: Float
    def frameRate(fps: Float): Unit
}
inline def frameRate(using frameRate) =
  summon[frameRate].frameRate
inline def frameRate(fps: Float)(using frameRate) =
  summon[frameRate].frameRate(fps)

trait legacy[C] {
    def legacy[A](f: C ?=> A): A
}
inline def legacy[C, A](f: C ?=> A)(using legacy[C]): A =
  summon[legacy[C]].legacy(f)

trait BackendAPI[C] extends size with frameRate with legacy[C]
