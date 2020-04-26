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
package coprocessing.p3

import coprocessing.core._

//export processing.core.PApplet
type PApplet = processing.core.PApplet

def runSketch(s: legacy[PApplet] ?=> Sketch): Unit =
  given CoprocessingApplet = CoprocessingApplet(s)
  processing.core.PApplet.runSketch(Array[String|Null]("coprocessing.core"), thePApplet)

// need a conventional class because Processing calls getClass.getSimpleName on it
private class CoprocessingApplet(_s: legacy[PApplet] ?=> Sketch) extends PApplet with BackendAPI[PApplet] {
  given CoprocessingApplet = this
  lazy val s = _s
  override def settings(): Unit = s.settings()
  override def setup(): Unit = s.setup()
  override def draw(): Unit = s.draw()
  def legacy[A](f: PApplet ?=> A): A = f
}

def thePApplet(using pa: PApplet) = pa
