import scala.quoted._



object Macros {

  inline def (inline self: StringContext) xyz(args: => String*): String = ${impl('self, 'args)}

  private def impl(self: Expr[StringContext], args: Expr[Seq[String]])(using QuoteContext): Expr[String] = {
    self match {
      case '{ StringContext(${Varargs(parts)}: _*) } =>
        val parts2 = Expr.ofList(parts.map(x => '{ $x.reverse }))
        '{ StringContext($parts2: _*).s($args: _*) }
      case _ =>
        '{ "ERROR" }
    }

  }

}
