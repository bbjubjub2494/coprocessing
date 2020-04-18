import scala.quoted._

object scalatest {

  inline def assert(condition: => Boolean): Unit = ${ assertImpl('condition) }

  def assertImpl(cond: Expr[Boolean])(using qctx: QuoteContext) : Expr[Unit] = {
    import qctx.tasty._
    import util._

    cond.unseal.underlyingArgument match {
      case t @ Apply(TypeApply(Select(lhs, op), targs), rhs) =>
        let(lhs) { left =>
          lets(rhs) { rs =>
            val app = Select.overloaded(left, op, targs.map(_.tpe), rs)
            val b = app.seal.cast[Boolean]
            '{ scala.Predef.assert($b) }.unseal
          }
        }.seal.cast[Unit]
    }
  }
}
