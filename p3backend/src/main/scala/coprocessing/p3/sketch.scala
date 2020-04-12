package coprocessing.p3

import processing.core.PApplet
import coprocessing.core._

def runSketch(s: P3LegacyOps ?=> Sketch): Unit =
  given PApplet = CoprocessingApplet(s)
  PApplet.runSketch(Array[String|Null]("coprocessing.core"), thePApplet)

// need a conventional class because Processing calls getClass.getSimpleName on it
private class CoprocessingApplet(s: Sketch) extends PApplet {
  given PApplet = this
  override def settings(): Unit = s.settings()
  override def setup(): Unit = s.setup()
  override def draw(): Unit = s.draw()
}

def thePApplet(using pa: PApplet) = pa

given (using PApplet) as SizeOps {
  def size(width: Int, height: Int) = thePApplet.size(width, height)
}

given (using PApplet) as FrameRateOps {
  def frameRate = thePApplet.frameRate
  def frameRate(fps: Float) = thePApplet.frameRate(fps)
}

type P3LegacyOps = LegacyOps[PApplet]
given (using PApplet) as P3LegacyOps {
  def legacy[A](f: PApplet ?=> A): A = f
}
