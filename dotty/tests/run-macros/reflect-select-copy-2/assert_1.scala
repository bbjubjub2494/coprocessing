import scala.quoted._

object scalatest {

  inline def assert(condition: => Boolean): Unit = ${ assertImpl('condition, '{""}) }

  def assertImpl(cond: Expr[Boolean], clue: Expr[Any])(using qctx: QuoteContext) : Expr[Unit] = {
    import qctx.tasty._
    import util._

    def isImplicitMethodType(tp: Type): Boolean = tp match
      case tp: MethodType => tp.isImplicit
      case _ => false

    cond.unseal.underlyingArgument match {
      case Apply(sel @ Select(lhs, op), rhs :: Nil) =>
        let(lhs) { left =>
          let(rhs) { right =>
            let(Apply(Select.copy(sel)(left, op), right :: Nil)) { result =>
              val l = left.seal
              val r = right.seal
              val b = result.seal.cast[Boolean]
              val code = '{ scala.Predef.assert(${b}) }
              code.unseal
            }
          }
        }.seal.cast[Unit]
      case Apply(f @ Apply(sel @ Select(Apply(qual, lhs :: Nil), op), rhs :: Nil), implicits)
      if isImplicitMethodType(f.tpe) =>
        let(lhs) { left =>
          let(rhs) { right =>
            let(Apply(Apply(Select.copy(sel)(Apply(qual, left :: Nil), op), right :: Nil), implicits)) { result =>
              val l = left.seal
              val r = right.seal
              val b = result.seal.cast[Boolean]
              val code = '{ scala.Predef.assert(${b}) }
              code.unseal
            }
          }
        }.seal.cast[Unit]
    }
  }

}
