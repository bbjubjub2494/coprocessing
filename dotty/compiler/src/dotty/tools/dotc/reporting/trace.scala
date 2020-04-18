package dotty.tools
package dotc
package reporting

import core.Contexts.Context
import config.Config
import config.Printers
import core.Mode

/** Exposes the {{{ trace("question") { op } }}} syntax.
  *
  * Traced operations will print indented messages if enabled.
  * Tracing depends on [[Config.tracingEnabled]] and [[dotty.tools.dotc.config.ScalaSettings.Ylog]].
  * Tracing can be forced by replacing [[trace]] with [[trace.force]] (see below).
  */
object trace extends TraceSyntax {
  final val isForced = false

  /** Forces a particular trace to be printed out regardless of tracing being enabled. */
  object force extends TraceSyntax {
    final val isForced = true
  }
}

abstract class TraceSyntax {
  val isForced: Boolean

  inline def onDebug[TD](inline question: String)(inline op: TD)(implicit ctx: Context): TD =
    conditionally(ctx.settings.YdebugTrace.value, question, false)(op)

  inline def conditionally[TC](inline cond: Boolean, inline question: String, inline show: Boolean)(op: => TC)(implicit ctx: Context): TC =
    inline if (isForced || Config.tracingEnabled) {
      if (cond) apply[TC](question, Printers.default, show)(op)
      else op
    }
    else op

  inline def apply[T](inline question: String, inline printer: Printers.Printer, inline showOp: Any => String)(op: => T)(implicit ctx: Context): T =
    inline if (isForced || Config.tracingEnabled) {
      if (!isForced && printer.eq(config.Printers.noPrinter)) op
      else doTrace[T](question, printer, showOp)(op)
    }
    else op

  inline def apply[T](inline question: String, inline printer: Printers.Printer, inline show: Boolean)(op: => T)(implicit ctx: Context): T =
    inline if (isForced || Config.tracingEnabled) {
      if (!isForced && printer.eq(config.Printers.noPrinter)) op
      else doTrace[T](question, printer, if (show) showShowable(_) else alwaysToString)(op)
    }
    else op

  inline def apply[T](inline question: String, inline printer: Printers.Printer)(inline op: T)(implicit ctx: Context): T =
    apply[T](question, printer, false)(op)

  inline def apply[T](inline question: String, inline show: Boolean)(inline op: T)(implicit ctx: Context): T =
    apply[T](question, Printers.default, show)(op)

  inline def apply[T](inline question: String)(inline op: T)(implicit ctx: Context): T =
    apply[T](question, Printers.default, false)(op)

  private def showShowable(x: Any)(implicit ctx: Context) = x match {
    case x: printing.Showable => x.show
    case _ => String.valueOf(x)
  }

  private val alwaysToString = (x: Any) => String.valueOf(x)

  private def doTrace[T](question: => String,
                         printer: Printers.Printer = Printers.default,
                         showOp: Any => String = alwaysToString)
                        (op: => T)(implicit ctx: Context): T = {
    // Avoid evaluating question multiple time, since each evaluation
    // may cause some extra logging output.
    lazy val q: String = question
    apply[T](s"==> $q?", (res: Any) => s"<== $q = ${showOp(res)}")(op)
  }

  def apply[T](leading: => String, trailing: Any => String)(op: => T)(implicit ctx: Context): T = {
    val log: String => Unit = if (isForced) Console.println else {
      var logctx = ctx
      while (logctx.reporter.isInstanceOf[StoreReporter]) logctx = logctx.outer
      logctx.log(_)
    }
    doApply(leading, trailing, log)(op)
  }

  def doApply[T](leading: => String, trailing: Any => String, log: String => Unit)(op: => T)(implicit ctx: Context): T =
    if (ctx.mode.is(Mode.Printing)) op
    else {
      var finalized = false
      def finalize(result: Any, note: String) =
        if (!finalized) {
          ctx.base.indent -= 1
          log(s"${ctx.base.indentTab * ctx.base.indent}${trailing(result)}$note")
          finalized = true
        }
      try {
        log(s"${ctx.base.indentTab * ctx.base.indent}$leading")
        ctx.base.indent += 1
        val res = op
        finalize(res, "")
        res
      }
      catch {
        case ex: Throwable =>
          finalize("<missing>", s" (with exception $ex)")
          throw ex
      }
    }
}
