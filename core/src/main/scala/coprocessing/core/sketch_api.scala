package coprocessing.core

import processing.core.PApplet

trait Sketch {
  def settings(): (LegacyOps, SizeOps) ?=> Unit = ()
  def setup(): LegacyOps ?=> Unit = ()
  def draw(): LegacyOps ?=> Unit = ()
}

trait SizeOps {
    def size(width: Int, height: Int): Unit
}
def size(width: Int, height: Int)(using SizeOps) = summon[SizeOps].size(width, height)

trait LegacyOps {
    def legacy[A](f: PApplet ?=> A): A
}
def legacy[A](f: PApplet ?=> A)(using LegacyOps): A = summon[LegacyOps].legacy(f)

def thePApplet(using pa: PApplet) = pa
