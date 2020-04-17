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
package coprocessing.core

trait Sketch {
  def settings(): (SizeOps) ?=> Unit = ()
  def setup(): (FrameRateOps) ?=> Unit = ()
  def draw(): (FrameRateOps) ?=> Unit = ()
}

trait SizeOps {
    def size(width: Int, height: Int): Unit
}
inline def size(width: Int, height: Int)(using SizeOps) =
  summon[SizeOps].size(width, height)

trait FrameRateOps {
    def frameRate: Float
    def frameRate(fps: Float): Unit
}
inline def frameRate(using FrameRateOps) =
  summon[FrameRateOps].frameRate
inline def frameRate(fps: Float)(using FrameRateOps) =
  summon[FrameRateOps].frameRate(fps)

trait LegacyOps[C] {
    def legacy[A](f: C ?=> A): A
}
inline def legacy[C, A](f: C ?=> A)(using LegacyOps[C]): A =
  summon[LegacyOps[C]].legacy(f)
