package dotty.tools
package dotc
package reporting

import core.Contexts.Context
import collection.mutable
import config.Printers.typr
import Diagnostic._

/** This class implements a Reporter that stores all messages
  *
  * Beware that this reporter can leak memory, and force messages in two
  * scenarios:
  *
  * - During debugging `config.Printers.typr` is set from `noPrinter` to `new
  *   Printer`, which forces the message
  * - The reporter is not flushed and the message containers capture a
  *   `Context` (about 4MB)
  */
class StoreReporter(outer: Reporter) extends Reporter {

  protected var infos: mutable.ListBuffer[Diagnostic] = null

  def doReport(dia: Diagnostic)(implicit ctx: Context): Unit = {
    typr.println(s">>>> StoredError: ${dia.message}") // !!! DEBUG
    if (infos == null) infos = new mutable.ListBuffer
    infos += dia
  }

  override def hasUnreportedErrors: Boolean =
    outer != null && infos != null && infos.exists(_.isInstanceOf[Error])

  override def hasStickyErrors: Boolean =
    infos != null && infos.exists(_.isInstanceOf[StickyError])

  override def removeBufferedMessages(implicit ctx: Context): List[Diagnostic] =
    if (infos != null) try infos.toList finally infos = null
    else Nil

  override def pendingMessages(using Context): List[Diagnostic] = infos.toList

  override def errorsReported: Boolean = hasErrors || (outer != null && outer.errorsReported)
}
