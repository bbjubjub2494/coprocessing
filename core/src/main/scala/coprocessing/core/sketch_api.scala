package coprocessing.core

trait Sketch {
  def settings(): (SizeOps) ?=> Unit = ()
  def setup(): (FrameRateOps) ?=> Unit = ()
  def draw(): (FrameRateOps) ?=> Unit = ()
}

trait SizeOps {
    def size(width: Int, height: Int): Unit
}
def size(width: Int, height: Int)(using SizeOps) = summon[SizeOps].size(width, height)

trait FrameRateOps {
    def frameRate: Float
    def frameRate(fps: Float): Unit
}
def frameRate(using FrameRateOps) = summon[FrameRateOps].frameRate
def frameRate(fps: Float)(using FrameRateOps) = summon[FrameRateOps].frameRate(fps)

trait LegacyOps[C] {
    def legacy[A](f: C ?=> A): A
}
def legacy[C, A](f: C ?=> A)(using LegacyOps[C]): A = summon[LegacyOps[C]].legacy(f)
