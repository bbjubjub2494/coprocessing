package dotty.tools.benchmarks.tuples

import org.openjdk.jmh.annotations._
import scala.runtime.DynamicTuple

@State(Scope.Thread)
class Split {
  @Param(Array("0"))
  var size: Int = _
  var tuple: Tuple = _
  var array: Array[Object] = _
  var half: Int = _

  @Setup
  def setup(): Unit = {
    tuple = ()
    half = size / 2

    for (i <- 1 to size)
      tuple = "elem" *: tuple

    array = new Array[Object](size)
  }

  @Benchmark
  def tupleSplit(): (Tuple, Tuple) = {
    runtime.Tuple.splitAt(tuple, half)
  }

  @Benchmark
  def arraySplit(): (Array[Object], Array[Object]) = {
    array.splitAt(half)
  }
}
