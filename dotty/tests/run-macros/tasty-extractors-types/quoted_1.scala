import scala.quoted._
import scala.quoted.autolift

object Macros {

  implicit inline def printType[T]: Unit = ${ impl('[T]) }

  def impl[T](x: Type[T])(using qctx: QuoteContext) : Expr[Unit] = {
    import qctx.tasty._

    val tree = x.unseal
    '{
      println(${tree.showExtractors})
      println(${tree.tpe.showExtractors})
      println()
    }
  }
}
