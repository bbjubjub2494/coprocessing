package dotty.tools.dotc.core

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.util.Property
import dotty.tools.dotc.transform.PCPCheckAndHeal

import scala.collection.mutable

object StagingContext {

  /** A key to be used in a context property that tracks the quoteation level */
  private val QuotationLevel = new Property.Key[Int]

  /** A key to be used in a context property that tracks the quoteation stack.
   *  Stack containing the QuoteContext references recieved by the surrounding quotes.
   */
  private val QuoteContextStack = new Property.Key[List[tpd.Tree]]

  private val TaggedTypes = new Property.Key[PCPCheckAndHeal.QuoteTypeTags]

  /** All enclosing calls that are currently inlined, from innermost to outermost. */
  def level(implicit ctx: Context): Int =
    ctx.property(QuotationLevel).getOrElse(0)

  /** Context with an incremented quotation level. */
  def quoteContext(implicit ctx: Context): Context =
    ctx.fresh.setProperty(QuotationLevel, level + 1)

  /** Context with an incremented quotation level and pushes a refecence to a QuoteContext on the quote context stack */
  def pushQuoteContext(qctxRef: tpd.Tree)(implicit ctx: Context): Context =
    val old = ctx.property(QuoteContextStack).getOrElse(List.empty)
    ctx.fresh.setProperty(QuotationLevel, level + 1)
             .setProperty(QuoteContextStack, qctxRef :: old)

  /** Context with a decremented quotation level. */
  def spliceContext(implicit ctx: Context): Context =
    ctx.fresh.setProperty(QuotationLevel, level - 1)

  def contextWithQuoteTypeTags(taggedTypes: PCPCheckAndHeal.QuoteTypeTags)(implicit ctx: Context) =
    ctx.fresh.setProperty(TaggedTypes, taggedTypes)

  def getQuoteTypeTags(implicit ctx: Context): PCPCheckAndHeal.QuoteTypeTags =
    ctx.property(TaggedTypes).get

  /** Context with a decremented quotation level and pops the Some of top of the quote context stack or None if the stack is empty.
   *  The quotation stack could be empty if we are in a top level splice or an eroneous splice directly witin a top level splice.
   */
  def popQuoteContext()(implicit ctx: Context): (Option[tpd.Tree], Context) =
    val ctx1 = ctx.fresh.setProperty(QuotationLevel, level - 1)
    val head =
      ctx.property(QuoteContextStack) match
        case Some(x :: xs) =>
          ctx1.setProperty(QuoteContextStack, xs)
          Some(x)
        case _ =>
          None // Splice at level 0 or lower
    (head, ctx1)
}
