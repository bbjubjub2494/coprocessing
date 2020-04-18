package dotty.tools.dotc
package transform

import core._
import dotty.tools.dotc.transform.MegaPhase._
import Contexts.Context

/** A transformer that eliminates SeqLiteral's, transforming `SeqLiteral(elems)` to an operation
 *  equivalent to
 *
 *      JavaSeqLiteral(elems).toSeq
 *
 *  Instead of `toSeq`, which takes an implicit, the appropriate "wrapArray" method
 *  is called directly. The reason for this step is that JavaSeqLiterals, being arrays
 *  keep a precise type after erasure, whereas SeqLiterals only get the erased type `Seq`,
 */
class SeqLiterals extends MiniPhase {
  import ast.tpd._

  override def phaseName: String = "seqLiterals"
  override def runsAfter: Set[String] = Set(PatternMatcher.name)

  override def checkPostCondition(tree: Tree)(implicit ctx: Context): Unit = tree match {
    case tpd: SeqLiteral => assert(tpd.isInstanceOf[JavaSeqLiteral])
    case _ =>
  }

  override def transformSeqLiteral(tree: SeqLiteral)(implicit ctx: Context): Tree = tree match {
    case tree: JavaSeqLiteral => tree
    case _ =>
      val arr = JavaSeqLiteral(tree.elems, tree.elemtpt)
      //println(i"trans seq $tree, arr = $arr: ${arr.tpe} ${arr.tpe.elemType}")
      val elemtp = tree.elemtpt.tpe
      wrapArray(arr, elemtp).withSpan(tree.span).ensureConforms(tree.tpe)
  }
}
