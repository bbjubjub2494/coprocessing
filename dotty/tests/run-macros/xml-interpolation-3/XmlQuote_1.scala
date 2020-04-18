import scala.quoted._
import scala.quoted.autolift

import scala.language.implicitConversions

case class Xml(parts: String, args: List[Any])

object XmlQuote {

  implicit object SCOps {
    inline def (inline ctx: StringContext) xml (args: => Any*): Xml =
      ${XmlQuote.impl('ctx, 'args)}
  }

  def impl(receiver: Expr[StringContext], args: Expr[Seq[Any]])(using QuoteContext): Expr[Xml] = {
    val string = receiver.unliftOrError.parts.mkString("??")
    '{new Xml(${string}, $args.toList)}
  }
}
