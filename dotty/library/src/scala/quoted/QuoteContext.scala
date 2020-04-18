package scala.quoted

import scala.quoted.show.SyntaxHighlight

/** Quotation context provided by a macro expansion or in the scope of `scala.quoted.run`.
 *  Used to perform all operations on quoted `Expr` or `Type`.
 *
 *  It contains the low-level Typed AST API `tasty` meta-programming API.
 *  This API does not have the static type guarantiees that `Expr` and `Type` provide.
 *
 *  @param tasty Typed AST API. Usage: `def f(qctx: QuoteContext) = { import qctx.tasty._; ... }`.
 */
class QuoteContext(val tasty: scala.tasty.Reflection) { self =>

  /** Type of a QuoteContext provided by a splice within a quote that took this context.
   *  It is only required if working with the reflection API.
   *
   *  Usually it is infered by the quotes an splices typing. But sometimes it is necessary
   *  to explicitly state that a context is nested as in the following example:
   *
   *  ```scala
   *  def run(using qctx: QuoteContext)(tree: qctx.tasty.Tree): Unit =
   *    def nested()(using qctx.NestedContext): Expr[Int] = '{  ${ makeExpr(tree) } + 1  }
   *    '{  ${ nested() } + 2 }
   *  def makeExpr(using qctx: QuoteContext)(tree: qctx.tasty.Tree): Expr[Int] = ???
   *  ```
   */
  type NestedContext = QuoteContext {
    val tasty: self.tasty.type
  }

  /** Report an error at the position of the macro expansion */
  def error(msg: => String): Unit =
    tasty.error(msg, tasty.rootPosition)

  /** Report an error at the on the position of `expr` */
  def error(msg: => String, expr: Expr[Any]): Unit =
    tasty.error(msg, expr.unseal(using this).pos)

  /** Report an error at the position of the macro expansion and throws a StopQuotedContext */
  def throwError(msg: => String): Nothing = {
    error(msg)
    throw new StopQuotedContext
  }
  /** Report an error at the on the position of `expr` and throws a StopQuotedContext */
  def throwError(msg: => String, expr: Expr[Any]): Nothing = {
    error(msg, expr)
    throw new StopQuotedContext
  }

  /** Report a warning */
  def warning(msg: => String): Unit =
    tasty.warning(msg, tasty.rootPosition)

  /** Report a warning at the on the position of `expr` */
  def warning(msg: => String, expr: Expr[_]): Unit =
    tasty.warning(msg, expr.unseal(using this).pos)

}
