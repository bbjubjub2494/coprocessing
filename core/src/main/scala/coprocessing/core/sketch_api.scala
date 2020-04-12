package coprocessing.core

import processing.core.PApplet

trait Sketch {
  def settings(): (LegacyOps, SizeOps) ?=> Unit = ()
  def setup(): (FrameRateOps, LegacyOps) ?=> Unit = ()
  def draw(): (FrameRateOps, LegacyOps) ?=> Unit = ()
}

trait SizeOps {
    def size(width: Int, height: Int): Unit
}
def size(width: Int, height: Int)(using SizeOps) = summon[SizeOps].size(width, height)

trait LegacyOps {
    def legacy[A](f: PApplet ?=> A): A
}
def legacy[A](f: PApplet ?=> A)(using LegacyOps): A = summon[LegacyOps].legacy(f)

trait FrameRateOps {
    def frameRate: Float
    def frameRate(fps: Float): Unit
}
def frameRate(using FrameRateOps) = summon[FrameRateOps].frameRate
def frameRate(fps: Float)(using FrameRateOps) = summon[FrameRateOps].frameRate(fps)

def thePApplet(using pa: PApplet) = pa
