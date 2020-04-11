package coprocessing.core

trait Sketch {
  val settings: SizeOps ?=> Unit
}

trait SizeOps {
    def size(width: Int, height: Int): Unit
}
def size(width: Int, height: Int)(using SizeOps) = summon[SizeOps].size(width, height)
