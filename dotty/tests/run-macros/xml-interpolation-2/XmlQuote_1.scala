
import scala.quoted._
import scala.quoted.autolift


import scala.language.implicitConversions

case class Xml(parts: String, args: List[Any])

object XmlQuote {

  class SCOps(ctx: => StringContext) {
    inline def xml(args: Any*): Xml = ${ XmlQuote.impl('this, 'args) }
  }
  implicit inline def SCOps(ctx: => StringContext): SCOps = new SCOps(ctx)

  def impl(receiver: Expr[SCOps], args: Expr[Seq[Any]])(using qctx: QuoteContext) : Expr[Xml] = {
    import qctx.tasty._

    // for debugging purpose
    def pp(tree: Tree): Unit = {
      println(tree.showExtractors)
      println(tree.show)
    }

    def isSCOpsConversion(tree: Term) =
      tree.symbol.fullName == "XmlQuote$.SCOps" ||
      tree.symbol.fullName == "XmlQuote$.SCOps.<init>"

    def isStringContextApply(tree: Term) =
      tree.symbol.fullName == "scala.StringContext$.apply" ||
      tree.symbol.fullName == "scala.StringContext.<init>"

    def stripTyped(t: Term) = t match {
      case Typed(expr, _) => expr
      case _ => t
    }

    // XmlQuote.SCOps(StringContext.apply([p0, ...]: String*)
    val parts: List[String] = stripTyped(receiver.unseal.underlying) match {
      case Apply(conv, List(ctx1)) if isSCOpsConversion(conv) =>
        ctx1 match {
          case Apply(fun, List(Typed(Repeated(values, _), _))) if isStringContextApply(fun) =>
            values.iterator.map {
              case Literal(Constant(value: String)) => value
              case _ =>
                qctx.error("Expected statically known String")
                return '{???}
            }.toList
          case _ =>
            qctx.error("Expected statically known StringContext")
            return '{???}
        }
      case _ =>
        qctx.error("Expected statically known SCOps")
        return '{???}
    }

    // [a0, ...]: Any*
    val args2: Expr[List[Any]] = args.unseal.underlyingArgument match {
      case Typed(Repeated(args0, _), _) => // statically known args, make list directly
        Expr.ofList(args0.map(_.seal))
      case _ =>
        '{$args.toList}

    }

    val string = parts.mkString("??")
    '{new Xml(${string}, $args2)}
  }
}
