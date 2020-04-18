import scala.quoted._
import scala.quoted.staging._

object Test {
  given Toolbox = Toolbox.make(getClass.getClassLoader)
  def main(args: Array[String]): Unit = run {
    def asof[T: Type, U](x: Expr[T], t: Type[U]): Expr[U] =
      '{$x.asInstanceOf[$t]}

    println(asof('{}, '[Unit]).show)
    println(asof('{true}, '[Boolean]).show)
    println(asof('{0.toByte}, '[Byte]).show)
    println(asof('{ 'a' }, '[Char]).show)
    println(asof('{1.toShort}, '[Short]).show)
    println(asof('{2}, '[Int]).show)
    println(asof('{3L}, '[Long]).show)
    println(asof('{4f}, '[Float]).show)
    println(asof('{5d}, '[Double]).show)

    println(asof('{5d}, '[Boolean]).show) // Will clearly fail at runtime but the code can be generated
    '{}
  }
}
