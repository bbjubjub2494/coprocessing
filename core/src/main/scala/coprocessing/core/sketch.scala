package coprocessing.core

import processing.core.PApplet

def runSketch(s: Sketch): Unit =
  given PApplet = CoprocessingApplet(s)
  PApplet.runSketch(Array[String|Null]("coprocessing.core"), thePApplet)

// need a conventional class because Processing calls getClass.getSimpleName on it
private class CoprocessingApplet(s: Sketch) extends PApplet {
  given PApplet = this
  override def settings(): Unit = s.settings()
  override def setup(): Unit = s.setup()
  override def draw(): Unit = s.draw()
}

given (using PApplet) as SizeOps {
  def size(width: Int, height: Int) = thePApplet.size(width, height)
}

given (using PApplet) as LegacyOps {
  def legacy[A](f: PApplet ?=> A): A = f
}
