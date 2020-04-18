package dotty.tools.dotc
package typer

import dotty.tools.dotc.ast.{ Trees, tpd }
import core._
import Types._, Contexts._, Flags._, Symbols._, Trees._
import Decorators._
import Variances._
import NameKinds._
import util.Spans._
import util.SourcePosition
import config.Printers.variances
import config.Feature.migrateTo3
import reporting.trace

/** Provides `check` method to check that all top-level definitions
 *  in tree are variance correct. Does not recurse inside methods.
 *  The method should be invoked once for each Template.
 */
object VarianceChecker {
  case class VarianceError(tvar: Symbol, required: Variance)
  def check(tree: tpd.Tree)(using Context): Unit =
    new VarianceChecker(using ctx).Traverser.traverse(tree)

  /** Check that variances of type lambda correspond to their occurrences in its body.
   *  Note: this is achieved by a mechanism separate from checking class type parameters.
   *  Question: Can the two mechanisms be combined in one?
   */
  def checkLambda(tree: tpd.LambdaTypeTree, bounds: TypeBounds)(using Context): Unit =
    def checkType(tpe: Type): Unit = tpe match
      case tl: HKTypeLambda if tl.isDeclaredVarianceLambda =>
        val checkOK = new TypeAccumulator[Boolean] {
          def paramVarianceSign(tref: TypeParamRef) =
            tl.typeParams(tref.paramNum).paramVarianceSign
          def error(tref: TypeParamRef) = {
            val paramName = tl.paramNames(tref.paramNum).toTermName
            val v = paramVarianceSign(tref)
            val paramVarianceStr = if (v < 0) "contra" else "co"
            val occursStr = variance match {
              case -1 => "contra"
              case 0 => "in"
              case 1 => "co"
            }
            val pos = tree.tparams
              .find(_.name.toTermName == paramName)
              .map(_.sourcePos)
              .getOrElse(tree.sourcePos)
            accCtx.error(em"${paramVarianceStr}variant type parameter $paramName occurs in ${occursStr}variant position in ${tl.resType}", pos)
          }
          def apply(x: Boolean, t: Type) = x && {
            t match {
              case tref: TypeParamRef if tref.binder `eq` tl =>
                varianceConforms(variance, paramVarianceSign(tref))
                || { error(tref); false }
              case AnnotatedType(_, annot) if annot.symbol == defn.UncheckedVarianceAnnot =>
                x
              case _ =>
                foldOver(x, t)
            }
          }
        }
        checkOK(true, tl.resType)
      case _ =>
    end checkType

    checkType(bounds.lo)
    checkType(bounds.hi)
  end checkLambda

  private def varianceLabel(v: Variance): String =
    if (v is Covariant) "covariant"
    else if (v is Contravariant) "contravariant"
    else "invariant"
}

class VarianceChecker(using Context) {
  import VarianceChecker._
  import tpd._

  private object Validator extends TypeAccumulator[Option[VarianceError]] {
    private var base: Symbol = _

    /** The variance of a symbol occurrence of `tvar` seen at the level of the definition of `base`.
     *  The search proceeds from `base` to the owner of `tvar`.
     *  Initially the state is covariant, but it might change along the search.
     */
    def relativeVariance(tvar: Symbol, base: Symbol, v: Variance = Covariant): Variance = /*trace(i"relative variance of $tvar wrt $base, so far: $v")*/
      if base == tvar.owner then
        v
      else if base.is(Param) && base.owner.isTerm && !base.owner.isAllOf(PrivateLocal) then
        relativeVariance(tvar, paramOuter(base.owner), flip(v))
      else if base.owner.isTerm || base.owner.is(Package) || base.isAllOf(PrivateLocal) then
        Bivariant
      else if base.isAliasType then
        relativeVariance(tvar, base.owner, Invariant)
      else
        relativeVariance(tvar, base.owner, v)

    /** The next level to take into account when determining the
     *  relative variance with a method parameter as base. The method
     *  is always skipped. If the method is a constructor, we also skip
     *  its class owner, because constructors are not checked for variance
     *  relative to the type parameters of their own class. On the other
     *  hand constructors do count for checking the variance of type parameters
     *  of enclosing classes. I believe the Scala 2 rules are too lenient in
     *  that respect.
     */
    private def paramOuter(meth: Symbol) =
      if (meth.isConstructor) meth.owner.owner else meth.owner

    /** Check variance of abstract type `tvar` when referred from `base`. */
    private def checkVarianceOfSymbol(tvar: Symbol): Option[VarianceError] = {
      val relative = relativeVariance(tvar, base)
      if (relative == Bivariant) None
      else {
        val required = compose(relative, this.variance)
        def tvar_s = s"$tvar (${varianceLabel(tvar.flags)} ${tvar.showLocated})"
        def base_s = s"$base in ${base.owner}" + (if (base.owner.isClass) "" else " in " + base.owner.enclosingClass)
        accCtx.log(s"verifying $tvar_s is ${varianceLabel(required)} at $base_s")
        accCtx.log(s"relative variance: ${varianceLabel(relative)}")
        accCtx.log(s"current variance: ${this.variance}")
        accCtx.log(s"owner chain: ${base.ownersIterator.toList}")
        if (tvar.isOneOf(required)) None
        else Some(VarianceError(tvar, required))
      }
    }

    /** For PolyTypes, type parameters are skipped because they are defined
     *  explicitly (their TypeDefs will be passed here.) For MethodTypes, the
     *  same is true of the parameters (ValDefs).
     */
    def apply(status: Option[VarianceError], tp: Type): Option[VarianceError] = trace(s"variance checking $tp of $base at $variance", variances) {
      try
        if (status.isDefined) status
        else tp.normalized match {
          case tp: TypeRef =>
            val sym = tp.symbol
            if (sym.isOneOf(VarianceFlags) && base.isContainedIn(sym.owner)) checkVarianceOfSymbol(sym)
            else sym.info match {
              case MatchAlias(_) => foldOver(status, tp)
              case TypeAlias(alias) => this(status, alias)
              case _ => foldOver(status, tp)
            }
          case tp: MethodOrPoly =>
            this(status, tp.resultType) // params will be checked in their TypeDef or ValDef nodes.
          case AnnotatedType(_, annot) if annot.symbol == defn.UncheckedVarianceAnnot =>
            status
          case tp: ClassInfo =>
            foldOver(status, tp.classParents)
          case _ =>
            foldOver(status, tp)
        }
      catch {
        case ex: Throwable => handleRecursive("variance check of", tp.show, ex)
      }
    }

    def validateDefinition(base: Symbol): Option[VarianceError] = {
      val saved = this.base
      this.base = base
      try apply(None, base.info)
      finally this.base = saved
    }
  }

  private object Traverser extends TreeTraverser {
    def checkVariance(sym: Symbol, pos: SourcePosition) = Validator.validateDefinition(sym) match {
      case Some(VarianceError(tvar, required)) =>
        def msg = i"${varianceLabel(tvar.flags)} $tvar occurs in ${varianceLabel(required)} position in type ${sym.info} of $sym"
        if (migrateTo3 &&
            (sym.owner.isConstructor || sym.ownersIterator.exists(_.isAllOf(ProtectedLocal))))
          ctx.migrationWarning(
            s"According to new variance rules, this is no longer accepted; need to annotate with @uncheckedVariance:\n$msg",
            pos)
            // patch(Span(pos.end), " @scala.annotation.unchecked.uncheckedVariance")
            // Patch is disabled until two TODOs are solved:
            // TODO use an import or shorten if possible
            // TODO need to use a `:' if annotation is on term
        else ctx.error(msg, pos)
      case None =>
    }

    override def traverse(tree: Tree)(using Context) = {
      def sym = tree.symbol
      // No variance check for private/protected[this] methods/values.
      def skip = !sym.exists
        || sym.name.is(InlineAccessorName) // TODO: should we exclude all synthetic members?
        || sym.isAllOf(LocalParamAccessor) // local class parameters are construction only
        || sym.is(TypeParam) && sym.owner.isClass // already taken care of in primary constructor of class
      try tree match {
        case defn: MemberDef if skip =>
          ctx.debuglog(s"Skipping variance check of ${sym.showDcl}")
        case tree: TypeDef =>
          checkVariance(sym, tree.sourcePos)
          tree.rhs match {
            case rhs: Template => traverseChildren(rhs)
            case _ =>
          }
        case tree: ValDef =>
          checkVariance(sym, tree.sourcePos)
        case DefDef(_, tparams, vparamss, _, _) =>
          checkVariance(sym, tree.sourcePos)
          tparams foreach traverse
          vparamss foreach (_ foreach traverse)
        case _ =>
      }
      catch {
        case ex: TypeError => ctx.error(ex, tree.sourcePos.focus)
      }
    }
  }
}
