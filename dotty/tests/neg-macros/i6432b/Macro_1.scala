
import scala.quoted._
import scala.quoted.autolift


object Macro {
  inline def (inline sc: StringContext).foo(args: String*): Unit = ${ impl('sc) }

  def impl(sc: Expr[StringContext])(using qctx: QuoteContext) : Expr[Unit] = {
    import qctx.tasty._
    sc match {
      case '{ StringContext(${Varargs(parts)}: _*) } =>
        for (part @ Const(s) <- parts)
          error(s, part.unseal.pos)
    }
    '{}
  }
}
