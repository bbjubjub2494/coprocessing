import scala.quoted._
import scala.quoted.staging._
import scala.reflect.ClassTag

object Arrays {
  implicit def ArrayIsLiftable[T: Liftable](implicit t: Type[T], ct: Expr[ClassTag[T]]): Liftable[Array[T]] = {
    new Liftable[Array[T]] {
      def toExpr(arr: Array[T]) = '{
        new Array[$t](${Expr(arr.length)})($ct)
        // TODO add elements
      }
    }
  }
}

object Test {
  implicit val toolbox: scala.quoted.staging.Toolbox = scala.quoted.staging.Toolbox.make(this.getClass.getClassLoader)
  def main(args: Array[String]): Unit = withQuoteContext {
    import Arrays._
    implicit val ct: Expr[ClassTag[Int]] = '{ClassTag.Int}
    val arr: Expr[Array[Int]] = Expr(Array[Int](1, 2, 3))
    println(arr.show)
  }
}
