import scala.Predef.Set // unimport Predef.wrapRefArray

import java.nio.file.Paths

class i4785 {
  def bar(xs: String*) = xs.length

  def test(xs: Seq[String], ys: Array[String]) = {
    Paths.get("Hello", xs: _*)
    Paths.get("Hello", ys: _*)

    bar(xs: _*)
    bar(ys: _*)
  }
}
