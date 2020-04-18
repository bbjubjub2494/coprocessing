
import scala.quoted._
import scala.language.implicitConversions
import scala.quoted.autolift

object Macro {

  class StringContextOps(strCtx: => StringContext) {
    inline def s2(args: Any*): String = ${SIntepolator('strCtx, 'args)}
    inline def raw2(args: Any*): String = ${RawIntepolator('strCtx, 'args)}
    inline def foo(args: Any*): String = ${FooIntepolator('strCtx, 'args)}
  }
  implicit inline def SCOps(strCtx: => StringContext): StringContextOps = new StringContextOps(strCtx)
}

object SIntepolator extends MacroStringInterpolator[String] {
  protected def interpolate(strCtx: StringContext, args: List[Expr[Any]]) (using QuoteContext): Expr[String] =
    '{(${strCtx}).s(${Expr.ofList(args)}: _*)}
}

object RawIntepolator extends MacroStringInterpolator[String] {
  protected def interpolate(strCtx: StringContext, args: List[Expr[Any]]) (using QuoteContext): Expr[String] =
    '{(${strCtx}).raw(${Expr.ofList(args)}: _*)}
}

object FooIntepolator extends MacroStringInterpolator[String] {
  protected def interpolate(strCtx: StringContext, args: List[Expr[Any]]) (using QuoteContext): Expr[String] =
    '{(${strCtx}).s(${Expr.ofList(args.map(_ => '{"foo"}))}: _*)}
}

// TODO put this class in the stdlib or separate project?
abstract class MacroStringInterpolator[T] {

  final def apply(strCtxExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using qctx: QuoteContext) : Expr[T] = {
    try interpolate(strCtxExpr, argsExpr)
    catch {
      case ex: NotStaticlyKnownError =>
        // TODO use ex.expr to recover the position
        qctx.error(ex.getMessage)
        '{???}
      case ex: StringContextError =>
        // TODO use ex.idx to recover the position
        qctx.error(ex.getMessage)
        '{???}
      case ex: ArgumentError =>
        // TODO use ex.idx to recover the position
        qctx.error(ex.getMessage)
        '{???}
    }
  }

  protected def interpolate(strCtxExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]]) (using QuoteContext): Expr[T] =
    interpolate(getStaticStringContext(strCtxExpr), getArgsList(argsExpr))

  protected def interpolate(strCtx: StringContext, argExprs: List[Expr[Any]]) (using QuoteContext): Expr[T]

  protected def getStaticStringContext(strCtxExpr: Expr[StringContext])(using qctx: QuoteContext) : StringContext = {
    import qctx.tasty._
    strCtxExpr.unseal.underlyingArgument match {
      case Select(Typed(Apply(_, List(Apply(_, List(Typed(Repeated(strCtxArgTrees, _), Inferred()))))), _), _) =>
        val strCtxArgs = strCtxArgTrees.map {
          case Literal(Constant(str: String)) => str
          case tree => throw new NotStaticlyKnownError("Expected statically known StringContext", tree.seal)
        }
        StringContext(strCtxArgs: _*)
      case tree =>
        throw new NotStaticlyKnownError("Expected statically known StringContext", tree.seal)
    }
  }

  protected def getArgsList(argsExpr: Expr[Seq[Any]])(using qctx: QuoteContext) : List[Expr[Any]] = {
    import qctx.tasty._
    argsExpr.unseal.underlyingArgument match {
      case Typed(Repeated(args, _), _) => args.map(_.seal)
      case tree => throw new NotStaticlyKnownError("Expected statically known argument list", tree.seal)
    }
  }

  protected implicit def StringContextIsLiftable: Liftable[StringContext] = new Liftable[StringContext] {
    def toExpr(strCtx: StringContext) = '{StringContext(${strCtx.parts.toSeq}: _*)}
  }

  protected class NotStaticlyKnownError(msg: String, expr: Expr[Any]) extends Exception(msg)
  protected class StringContextError(msg: String, idx: Int, start: Int = -1, end: Int = -1) extends Exception(msg)
  protected class ArgumentError(msg: String, idx: Int) extends Exception(msg)

}
