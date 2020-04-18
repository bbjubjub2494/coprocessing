package dotty.tools.dotc.ast

import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.TypeError

import scala.annotation.tailrec

/** A TreeMap that maintains the necessary infrastructure to support
 *  contextual implicit searches (type-scope implicits are supported anyway).
 *
 *  This incudes implicits defined in scope as well as imported implicits.
 */
class TreeMapWithImplicits extends tpd.TreeMap {
  import tpd._

  def transformSelf(vd: ValDef)(implicit ctx: Context): ValDef =
    cpy.ValDef(vd)(tpt = transform(vd.tpt))

  /** Transform statements, while maintaining import contexts and expression contexts
   *  in the same way as Typer does. The code addresses additional concerns:
   *   - be tail-recursive where possible
   *   - don't re-allocate trees where nothing has changed
   */
  def transformStats(stats: List[Tree], exprOwner: Symbol)(implicit ctx: Context): List[Tree] = {

    @tailrec def traverse(curStats: List[Tree])(implicit ctx: Context): List[Tree] = {

      def recur(stats: List[Tree], changed: Tree, rest: List[Tree])(implicit ctx: Context): List[Tree] =
        if (stats eq curStats) {
          val rest1 = transformStats(rest, exprOwner)
          changed match {
            case Thicket(trees) => trees ::: rest1
            case tree => tree :: rest1
          }
        }
        else stats.head :: recur(stats.tail, changed, rest)

      curStats match {
        case stat :: rest =>
          val statCtx = stat match {
            case stat: DefTree => ctx
            case _ => ctx.exprContext(stat, exprOwner)
          }
          val restCtx = stat match {
            case stat: Import => ctx.importContext(stat, stat.symbol)
            case _ => ctx
          }
          val stat1 = transform(stat)(using statCtx)
          if (stat1 ne stat) recur(stats, stat1, rest)(restCtx)
          else traverse(rest)(restCtx)
        case nil =>
          stats
      }
    }
    traverse(stats)
  }

  private def nestedScopeCtx(defs: List[Tree])(implicit ctx: Context): Context = {
    val nestedCtx = ctx.fresh.setNewScope
    defs foreach {
      case d: DefTree if d.symbol.isOneOf(GivenOrImplicit) => nestedCtx.enter(d.symbol)
      case _ =>
    }
    nestedCtx
  }

  private def patternScopeCtx(pattern: Tree)(implicit ctx: Context): Context = {
    val nestedCtx = ctx.fresh.setNewScope
    new TreeTraverser {
      def traverse(tree: Tree)(implicit ctx: Context): Unit = {
        tree match {
          case d: DefTree if d.symbol.isOneOf(GivenOrImplicit) => nestedCtx.enter(d.symbol)
          case _ =>
        }
        traverseChildren(tree)
      }
    }.traverse(pattern)
    nestedCtx
  }

  override def transform(tree: Tree)(using Context): Tree = {
    def localCtx =
      if (tree.hasType && tree.symbol.exists) ctx.withOwner(tree.symbol) else ctx
    try tree match {
      case tree: Block =>
        super.transform(tree)(using nestedScopeCtx(tree.stats))
      case tree: DefDef =>
        given Context = localCtx
        cpy.DefDef(tree)(
          tree.name,
          transformSub(tree.tparams),
          tree.vparamss mapConserve (transformSub(_)),
          transform(tree.tpt),
          transform(tree.rhs)(using nestedScopeCtx(tree.vparamss.flatten)))
      case EmptyValDef =>
        tree
      case _: PackageDef | _: MemberDef =>
        super.transform(tree)(using localCtx)
      case impl @ Template(constr, parents, self, _) =>
        cpy.Template(tree)(
          transformSub(constr),
          transform(parents)(using ctx.superCallContext),
          Nil,
          transformSelf(self),
          transformStats(impl.body, tree.symbol))
      case tree: CaseDef =>
        val patCtx = patternScopeCtx(tree.pat)(using ctx)
        cpy.CaseDef(tree)(
          transform(tree.pat),
          transform(tree.guard)(using patCtx),
          transform(tree.body)(using patCtx)
        )
      case _ =>
        super.transform(tree)
    }
    catch {
      case ex: TypeError =>
        ctx.error(ex, tree.sourcePos)
        tree
    }
  }
}

