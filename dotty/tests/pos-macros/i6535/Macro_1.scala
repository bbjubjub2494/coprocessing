import scala.quoted._

object scalatest {

  inline def assert(condition: => Boolean): Unit = ${ assertImpl('condition) }

  def assertImpl(cond: Expr[Boolean])(using qctx: QuoteContext) : Expr[Unit] = {
    import qctx.tasty._
    import util._

    cond.unseal.underlyingArgument match {
      case t @ Apply(Select(lhs, op), rhs :: Nil) =>
        let(lhs) { left =>
          let(rhs) { right =>
            val app = Select.overloaded(left, op, Nil, right :: Nil)
            let(app) { result =>
              val l = left.seal
              val r = right.seal
              val b = result.seal.cast[Boolean]
              val code = '{ scala.Predef.assert($b) }
              code.unseal
            }
          }
        }.seal.cast[Unit]
    }
  }

}
