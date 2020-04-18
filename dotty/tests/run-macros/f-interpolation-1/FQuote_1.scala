import scala.quoted._
import scala.quoted.autolift

import scala.language.implicitConversions

object FQuote {

  implicit class SCOps(ctx: StringContext) {
    inline def ff(args: => Any*): String = ${impl('this, 'args)}
  }

  /*private*/ def impl(receiver: Expr[SCOps], args: Expr[Seq[Any]])(using qctx: QuoteContext) : Expr[String] = {
    import qctx.tasty._

    def liftListOfAny(lst: List[Term]): Expr[List[Any]] = lst match {
      case x :: xs  =>
        val head = x.seal
        val tail = liftListOfAny(xs)
        '{ $head :: $tail }
      case Nil => '{Nil}
    }

    def isStringConstant(tree: Term) = tree match {
      case Literal(_) => true
      case _ => false
    }

    def isSCOpsConversion(tree: Term) =
      tree.symbol.fullName == "FQuote$.SCOps"

    def isStringContextApply(tree: Term) =
      tree.symbol.fullName == "scala.StringContext$.apply"

    // FQuote.SCOps(StringContext.apply([p0, ...]: String*)
    val parts = receiver.unseal.underlyingArgument match {
      case Apply(conv, List(Apply(fun, List(Typed(Repeated(values, _), _)))))
          if isSCOpsConversion(conv) &&
             isStringContextApply(fun) &&
             values.forall(isStringConstant) =>
        values.collect { case Literal(Constant(value: String)) => value }
      case tree =>
        qctx.error(s"String literal expected, but ${tree.showExtractors} found")
        return '{???}
    }

    // [a0, ...]: Any*
    val Typed(Repeated(allArgs, _), _) = args.unseal.underlyingArgument

    for ((arg, part) <- allArgs.zip(parts.tail)) {
      if (part.startsWith("%d") && !(arg.tpe <:< defn.IntType)) {
        return '{s"`${${arg.show}}` is not of type Int"}
      }

    }

    val string = parts.mkString("")
    '{ new collection.immutable.StringOps(${string}).format($args: _*) }
  }
}
