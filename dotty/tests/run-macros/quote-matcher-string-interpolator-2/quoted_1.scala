import scala.quoted._



object Macros {

  inline def (inline self: StringContext) xyz(inline args: String*): String = ${impl('self, 'args)}

  private def impl(self: Expr[StringContext], args: Expr[Seq[String]])(using QuoteContext): Expr[String] = {
    (self, args) match {
      case ('{ StringContext(${Varargs(parts)}: _*) }, Varargs(args1)) =>
        val strParts = parts.map { case Const(str) => str.reverse }
        val strArgs = args1.map { case Const(str) => str }
        Expr(StringContext(strParts: _*).s(strArgs: _*))
      case _ => ???
    }

  }

}
