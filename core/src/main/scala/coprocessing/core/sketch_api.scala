package coprocessing.core

import processing.core.PApplet

trait Sketch {
  val settings: (LegacyOps, SizeOps) ?=> Unit
  val setup: LegacyOps ?=> Unit
  val draw: LegacyOps ?=> Unit
}

trait SizeOps {
    def size(width: Int, height: Int): Unit
}
def size(width: Int, height: Int)(using SizeOps) = summon[SizeOps].size(width, height)

trait LegacyOps {
    def legacy[A](f: PApplet ?=> A): A
}

def thePApplet(using pa: PApplet) = pa
