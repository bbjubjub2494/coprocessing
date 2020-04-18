package dotty.tools
package dotc
package core

import Types._, Contexts._, Symbols._, Flags._, Names._, NameOps._, Denotations._
import Decorators._
import StdNames.nme
import collection.mutable
import util.Stats
import config.Config
import config.Feature.migrateTo3
import config.Printers.{constr, subtyping, gadts, noPrinter}
import TypeErasure.{erasedLub, erasedGlb}
import TypeApplications._
import Variances.{Variance, variancesConform}
import Constants.Constant
import transform.TypeUtils._
import transform.SymUtils._
import scala.util.control.NonFatal
import typer.ProtoTypes.constrained
import typer.Applications.productSelectorTypes
import reporting.trace
import NullOpsDecorator.NullOps

final class AbsentContext
object AbsentContext {
  implicit val absentContext: AbsentContext = new AbsentContext
}

/** Provides methods to compare types.
 */
class TypeComparer(initctx: Context) extends ConstraintHandling[AbsentContext] with PatternTypeConstrainer {
  import TypeComparer._
  implicit def ctx(implicit nc: AbsentContext): Context = initctx

  val state = ctx.typerState
  def constraint: Constraint = state.constraint
  def constraint_=(c: Constraint): Unit = state.constraint = c

  private var pendingSubTypes: mutable.Set[(Type, Type)] = null
  private var recCount = 0
  private var monitored = false

  private var needsGc = false

  private var canCompareAtoms: Boolean = true // used for internal consistency checking

  /** Is a subtype check in progress? In that case we may not
   *  permanently instantiate type variables, because the corresponding
   *  constraint might still be retracted and the instantiation should
   *  then be reversed.
   */
  def subtypeCheckInProgress: Boolean = {
    val result = recCount > 0
    if (result) {
      constr.println("*** needsGC ***")
      needsGc = true
    }
    result
  }

  /** For statistics: count how many isSubTypes are part of successful comparisons */
  private var successCount = 0
  private var totalCount = 0

  private var myAnyClass: ClassSymbol = null
  private var myAnyKindClass: ClassSymbol = null
  private var myNothingClass: ClassSymbol = null
  private var myNullClass: ClassSymbol = null
  private var myObjectClass: ClassSymbol = null
  private var myAnyType: TypeRef = null
  private var myAnyKindType: TypeRef = null
  private var myNothingType: TypeRef = null

  def AnyClass: ClassSymbol = {
    if (myAnyClass == null) myAnyClass = defn.AnyClass
    myAnyClass
  }
  def AnyKindClass: ClassSymbol = {
    if (myAnyKindClass == null) myAnyKindClass = defn.AnyKindClass
    myAnyKindClass
  }
  def NothingClass: ClassSymbol = {
    if (myNothingClass == null) myNothingClass = defn.NothingClass
    myNothingClass
  }
  def NullClass: ClassSymbol = {
    if (myNullClass == null) myNullClass = defn.NullClass
    myNullClass
  }
  def ObjectClass: ClassSymbol = {
    if (myObjectClass == null) myObjectClass = defn.ObjectClass
    myObjectClass
  }
  def AnyType: TypeRef = {
    if (myAnyType == null) myAnyType = AnyClass.typeRef
    myAnyType
  }
  def AnyKindType: TypeRef = {
    if (myAnyKindType == null) myAnyKindType = AnyKindClass.typeRef
    myAnyKindType
  }
  def NothingType: TypeRef = {
    if (myNothingType == null) myNothingType = NothingClass.typeRef
    myNothingType
  }

  /** Indicates whether a previous subtype check used GADT bounds */
  var GADTused: Boolean = false

  /** Record that GADT bounds of `sym` were used in a subtype check.
   *  But exclude constructor type parameters, as these are aliased
   *  to the corresponding class parameters, which does not constitute
   *  a true usage of a GADT symbol.
   */
  private def GADTusage(sym: Symbol) = {
    if (!sym.owner.isConstructor) GADTused = true
    true
  }

  protected def gadtBounds(sym: Symbol)(implicit ctx: Context) = ctx.gadt.bounds(sym)
  protected def gadtAddLowerBound(sym: Symbol, b: Type): Boolean = ctx.gadt.addBound(sym, b, isUpper = false)
  protected def gadtAddUpperBound(sym: Symbol, b: Type): Boolean = ctx.gadt.addBound(sym, b, isUpper = true)

  protected def typeVarInstance(tvar: TypeVar)(implicit ctx: Context): Type = tvar.underlying

  // Subtype testing `<:<`

  def topLevelSubType(tp1: Type, tp2: Type): Boolean = {
    if (tp2 eq NoType) return false
    if ((tp2 eq tp1) || (tp2 eq WildcardType)) return true
    try isSubType(tp1, tp2)
    finally {
      monitored = false
      if (Config.checkConstraintsSatisfiable)
        assert(isSatisfiable, constraint.show)
    }
  }

  /** The current approximation state. See `ApproxState`. */
  private var approx: ApproxState = FreshApprox
  protected def approxState: ApproxState = approx

  /** The original left-hand type of the comparison. Gets reset
   *  every time we compare components of the previous pair of types.
   *  This type is used for capture conversion in `isSubArgs`.
   */
  private [this] var leftRoot: Type = _

  /** Are we forbidden from recording GADT constraints? */
  private var frozenGadt = false
  private inline def inFrozenGadt[T](op: => T): T = {
    val savedFrozenGadt = frozenGadt
    frozenGadt = true
    try op finally frozenGadt = savedFrozenGadt
  }

  protected def isSubType(tp1: Type, tp2: Type, a: ApproxState): Boolean = {
    val savedApprox = approx
    val savedLeftRoot = leftRoot
    if (a == FreshApprox) {
      this.approx = NoApprox
      this.leftRoot = tp1
    }
    else this.approx = a
    try recur(tp1, tp2)
    catch {
      case ex: Throwable => handleRecursive("subtype", i"$tp1 <:< $tp2", ex, weight = 2)
    }
    finally {
      this.approx = savedApprox
      this.leftRoot = savedLeftRoot
    }
  }

  def isSubType(tp1: Type, tp2: Type)(implicit nc: AbsentContext): Boolean = isSubType(tp1, tp2, FreshApprox)

  /** The inner loop of the isSubType comparison.
   *  Recursive calls from recur should go to recur directly if the two types
   *  compared in the callee are essentially the same as the types compared in the
   *  caller. "The same" means: represent essentially the same sets of values.
   * `recur` should not be used to compare components of types. In this case
   *  one should use `isSubType(_, _)`.
   *  `recur` should also not be used to compare approximated versions of the original
   *  types (as when we go from an abstract type to one of its bounds). In that case
   *  one should use `isSubType(_, _, a)` where `a` defines the kind of approximation.
   *
   *  Note: Logicaly, `recur` could be nested in `isSubType`, which would avoid
   *  the instance state consisting `approx` and `leftRoot`. But then the implemented
   *  code would have two extra parameters for each of the many calls that go from
   *  one sub-part of isSubType to another.
   */
  protected def recur(tp1: Type, tp2: Type): Boolean = trace(s"isSubType ${traceInfo(tp1, tp2)} $approx", subtyping) {

    def monitoredIsSubType = {
      if (pendingSubTypes == null) {
        pendingSubTypes = new mutable.HashSet[(Type, Type)]
        ctx.log(s"!!! deep subtype recursion involving ${tp1.show} <:< ${tp2.show}, constraint = ${state.constraint.show}")
        ctx.log(s"!!! constraint = ${constraint.show}")
        //if (ctx.settings.YnoDeepSubtypes.value) {
        //  new Error("deep subtype").printStackTrace()
        //}
        assert(!ctx.settings.YnoDeepSubtypes.value)
        if (Config.traceDeepSubTypeRecursions && !this.isInstanceOf[ExplainingTypeComparer])
          ctx.log(TypeComparer.explained(summon[Context].typeComparer.isSubType(tp1, tp2, approx)))
      }
      // Eliminate LazyRefs before checking whether we have seen a type before
      val normalize = new TypeMap {
        val DerefLimit = 10
        var derefCount = 0
        def apply(t: Type) = t match {
          case t: LazyRef =>
            // Dereference a lazyref to detect underlying matching types, but
            // be careful not to get into an infinite recursion. If recursion count
            // exceeds `DerefLimit`, approximate with `t` instead.
            derefCount += 1
            if t.evaluating || derefCount >= DerefLimit then t
            else try mapOver(t.ref) finally derefCount -= 1
          case tp: TypeVar =>
            tp
          case _ =>
            mapOver(t)
        }
      }
      val p = (normalize(tp1), normalize(tp2))
      !pendingSubTypes(p) && {
        try {
          pendingSubTypes += p
          firstTry
        }
        finally
          pendingSubTypes -= p
      }
    }

    def firstTry: Boolean = tp2 match {
      case tp2: NamedType =>
        def compareNamed(tp1: Type, tp2: NamedType): Boolean =
          val ctx = this.ctx
          given Context = ctx // optimization for performance
          val info2 = tp2.info
          info2 match
            case info2: TypeAlias =>
              if recur(tp1, info2.alias) then return true
              if tp2.asInstanceOf[TypeRef].canDropAlias then return false
            case _ =>
          tp1 match
            case tp1: NamedType =>
              tp1.info match {
                case info1: TypeAlias =>
                  if recur(info1.alias, tp2) then return true
                  if tp1.asInstanceOf[TypeRef].canDropAlias then return false
                case _ =>
              }
              val sym2 = tp2.symbol
              var sym1 = tp1.symbol
              if (sym1.is(ModuleClass) && sym2.is(ModuleVal))
                // For convenience we want X$ <:< X.type
                // This is safe because X$ self-type is X.type
                sym1 = sym1.companionModule
              if ((sym1 ne NoSymbol) && (sym1 eq sym2))
                ctx.erasedTypes ||
                sym1.isStaticOwner ||
                isSubType(tp1.prefix, tp2.prefix) ||
                thirdTryNamed(tp2)
              else
                (  (tp1.name eq tp2.name)
                && tp1.isMemberRef
                && tp2.isMemberRef
                && isSubType(tp1.prefix, tp2.prefix)
                && tp1.signature == tp2.signature
                && !(sym1.isClass && sym2.isClass)  // class types don't subtype each other
                ) ||
                thirdTryNamed(tp2)
            case _ =>
              secondTry
        end compareNamed
        compareNamed(tp1, tp2)
      case tp2: ProtoType =>
        isMatchedByProto(tp2, tp1)
      case tp2: BoundType =>
        tp2 == tp1 || secondTry
      case tp2: TypeVar =>
        recur(tp1, typeVarInstance(tp2))
      case tp2: WildcardType =>
        def compareWild = tp2.optBounds match {
          case TypeBounds(_, hi) => recur(tp1, hi)
          case NoType => true
        }
        compareWild
      case tp2: LazyRef =>
        !tp2.evaluating && recur(tp1, tp2.ref)
      case tp2: AnnotatedType if !tp2.isRefining =>
        recur(tp1, tp2.parent)
      case tp2: ThisType =>
        def compareThis = {
          val cls2 = tp2.cls
          tp1 match {
            case tp1: ThisType =>
              // We treat two prefixes A.this, B.this as equivalent if
              // A's selftype derives from B and B's selftype derives from A.
              val cls1 = tp1.cls
              cls1.classInfo.selfType.derivesFrom(cls2) &&
              cls2.classInfo.selfType.derivesFrom(cls1)
            case tp1: NamedType if cls2.is(Module) && cls2.eq(tp1.widen.typeSymbol) =>
              cls2.isStaticOwner ||
              recur(tp1.prefix, cls2.owner.thisType) ||
              secondTry
            case _ =>
              secondTry
          }
        }
        compareThis
      case tp2: SuperType =>
        def compareSuper = tp1 match {
          case tp1: SuperType =>
            recur(tp1.thistpe, tp2.thistpe) &&
            isSameType(tp1.supertpe, tp2.supertpe)
          case _ =>
            secondTry
        }
        compareSuper
      case AndType(tp21, tp22) =>
        recur(tp1, tp21) && recur(tp1, tp22)
      case OrType(tp21, tp22) =>
        if (tp21.stripTypeVar eq tp22.stripTypeVar) recur(tp1, tp21)
        else secondTry
      case TypeErasure.ErasedValueType(tycon1, underlying2) =>
        def compareErasedValueType = tp1 match {
          case TypeErasure.ErasedValueType(tycon2, underlying1) =>
            (tycon1.symbol eq tycon2.symbol) && isSameType(underlying1, underlying2)
          case _ =>
            secondTry
        }
        compareErasedValueType
      case ConstantType(v2) =>
        tp1 match {
          case ConstantType(v1) => v1.value == v2.value && recur(v1.tpe, v2.tpe)
          case _ => secondTry
        }
      case tp2: AnyConstantType =>
        if (tp2.tpe.exists) recur(tp1, tp2.tpe)
        else tp1 match {
          case tp1: ConstantType =>
            tp2.tpe = tp1
            true
          case _ =>
            secondTry
        }
      case _: FlexType =>
        true
      case _ =>
        secondTry
    }

    def secondTry: Boolean = tp1 match {
      case tp1: NamedType =>
        tp1.info match {
          case info1: TypeAlias =>
            if (recur(info1.alias, tp2)) return true
            if (tp1.prefix.isStable) return tryLiftedToThis1
          case _ =>
            if (tp1 eq NothingType) return true
        }
        thirdTry
      case tp1: TypeParamRef =>
        def flagNothingBound = {
          if (!frozenConstraint && tp2.isRef(NothingClass) && state.isGlobalCommittable) {
            def msg = s"!!! instantiated to Nothing: $tp1, constraint = ${constraint.show}"
            if (Config.failOnInstantiationToNothing) assert(false, msg)
            else ctx.log(msg)
          }
          true
        }
        def compareTypeParamRef =
          assumedTrue(tp1) ||
          isSubTypeWhenFrozen(bounds(tp1).hi, tp2) || {
            if (canConstrain(tp1) && !approx.high)
              addConstraint(tp1, tp2, fromBelow = false) && flagNothingBound
            else thirdTry
          }
        compareTypeParamRef
      case tp1: ThisType =>
        val cls1 = tp1.cls
        tp2 match {
          case tp2: TermRef if cls1.is(Module) && cls1.eq(tp2.widen.typeSymbol) =>
            cls1.isStaticOwner ||
            recur(cls1.owner.thisType, tp2.prefix) ||
            thirdTry
          case _ =>
            thirdTry
        }
      case tp1: SkolemType =>
        tp2 match {
          case tp2: SkolemType if !ctx.phase.isTyper && recur(tp1.info, tp2.info) => true
          case _ => thirdTry
        }
      case tp1: TypeVar =>
        recur(typeVarInstance(tp1), tp2)
      case tp1: WildcardType =>
        def compareWild = tp1.optBounds match {
          case bounds: TypeBounds => recur(bounds.lo, tp2)
          case _ => true
        }
        compareWild
      case tp1: LazyRef =>
        // If `tp1` is in train of being evaluated, don't force it
        // because that would cause an assertionError. Return false instead.
        // See i859.scala for an example where we hit this case.
        !tp1.evaluating && recur(tp1.ref, tp2)
      case tp1: AnnotatedType if !tp1.isRefining =>
        recur(tp1.parent, tp2)
      case AndType(tp11, tp12) =>
        if (tp11.stripTypeVar eq tp12.stripTypeVar) recur(tp11, tp2)
        else thirdTry
      case tp1 @ OrType(tp11, tp12) =>
        compareAtoms(tp1, tp2) match
          case Some(b) => return b
          case None =>

        def joinOK = tp2.dealiasKeepRefiningAnnots match {
          case tp2: AppliedType if !tp2.tycon.typeSymbol.isClass =>
            // If we apply the default algorithm for `A[X] | B[Y] <: C[Z]` where `C` is a
            // type parameter, we will instantiate `C` to `A` and then fail when comparing
            // with `B[Y]`. To do the right thing, we need to instantiate `C` to the
            // common superclass of `A` and `B`.
            recur(tp1.join, tp2)
          case _ =>
            false
        }

        def containsAnd(tp: Type): Boolean = tp.dealiasKeepRefiningAnnots match
          case tp: AndType => true
          case OrType(tp1, tp2) => containsAnd(tp1) || containsAnd(tp2)
          case _ => false

        def widenOK =
          (tp2.widenSingletons eq tp2) &&
          (tp1.widenSingletons ne tp1) &&
          recur(tp1.widenSingletons, tp2)

        widenOK
        || joinOK
        || recur(tp11, tp2) && recur(tp12, tp2)
        || containsAnd(tp1) && recur(tp1.join, tp2)
            // An & on the left side loses information. Compensate by also trying the join.
            // This is less ad-hoc than it looks since we produce joins in type inference,
            // and then need to check that they are indeed supertypes of the original types
            // under -Ycheck. Test case is i7965.scala.
      case tp1: MatchType =>
        val reduced = tp1.reduced
        if (reduced.exists) recur(reduced, tp2) else thirdTry
      case _: FlexType =>
        true
      case _ =>
        thirdTry
    }

    def thirdTryNamed(tp2: NamedType): Boolean = tp2.info match {
      case info2: TypeBounds =>
        def compareGADT: Boolean = {
          val gbounds2 = gadtBounds(tp2.symbol)
          (gbounds2 != null) &&
            (isSubTypeWhenFrozen(tp1, gbounds2.lo) ||
              (tp1 match {
                case tp1: NamedType if ctx.gadt.contains(tp1.symbol) =>
                  // Note: since we approximate constrained types only with their non-param bounds,
                  // we need to manually handle the case when we're comparing two constrained types,
                  // one of which is constrained to be a subtype of another.
                  // We do not need similar code in fourthTry, since we only need to care about
                  // comparing two constrained types, and that case will be handled here first.
                  ctx.gadt.isLess(tp1.symbol, tp2.symbol) && GADTusage(tp1.symbol) && GADTusage(tp2.symbol)
                case _ => false
              }) ||
              narrowGADTBounds(tp2, tp1, approx, isUpper = false)) &&
            { tp1.isRef(NothingClass) || GADTusage(tp2.symbol) }
        }
        isSubApproxHi(tp1, info2.lo) || compareGADT || tryLiftedToThis2 || fourthTry

      case _ =>
        val cls2 = tp2.symbol
        if (cls2.isClass)
          if (cls2.typeParams.isEmpty) {
            if (cls2 eq AnyKindClass) return true
            if (tp1.isRef(NothingClass)) return true
            if (tp1.isLambdaSub) return false
              // Note: We would like to replace this by `if (tp1.hasHigherKind)`
              // but right now we cannot since some parts of the standard library rely on the
              // idiom that e.g. `List <: Any`. We have to bootstrap without scalac first.
            if (cls2 eq AnyClass) return true
            if (cls2 == defn.SingletonClass && tp1.isStable) return true
            return tryBaseType(cls2)
          }
          else if (cls2.is(JavaDefined)) {
            // If `cls2` is parameterized, we are seeing a raw type, so we need to compare only the symbol
            val base = nonExprBaseType(tp1, cls2)
            if (base.typeSymbol == cls2) return true
          }
          else if tp1.isLambdaSub && !tp1.isAnyKind then
            return recur(tp1, EtaExpansion(cls2.typeRef))
        fourthTry
    }

    def thirdTry: Boolean = tp2 match {
      case tp2 @ AppliedType(tycon2, args2) =>
        compareAppliedType2(tp2, tycon2, args2)
      case tp2: NamedType =>
        thirdTryNamed(tp2)
      case tp2: TypeParamRef =>
        def compareTypeParamRef =
          assumedTrue(tp2) || {
            val alwaysTrue =
              // The following condition is carefully formulated to catch all cases
              // where the subtype relation is true without needing to add a constraint
              // It's tricky because we might need to either approximate tp2 by its
              // lower bound or else widen tp1 and check that the result is a subtype of tp2.
              // So if the constraint is not yet frozen, we do the same comparison again
              // with a frozen constraint, which means that we get a chance to do the
              // widening in `fourthTry` before adding to the constraint.
              if (frozenConstraint) recur(tp1, bounds(tp2).lo)
              else isSubTypeWhenFrozen(tp1, tp2)
            alwaysTrue ||
            frozenConstraint && (tp1 match {
              case tp1: TypeParamRef => constraint.isLess(tp1, tp2)
              case _ => false
            }) || {
              if (canConstrain(tp2) && !approx.low)
                addConstraint(tp2, tp1.widenExpr, fromBelow = true)
              else fourthTry
            }
          }
        compareTypeParamRef
      case tp2: RefinedType =>
        def compareRefinedSlow: Boolean = {
          val name2 = tp2.refinedName
          recur(tp1, tp2.parent) &&
            (name2 == nme.WILDCARD || hasMatchingMember(name2, tp1, tp2))
        }
        def compareRefined: Boolean = {
          val tp1w = tp1.widen
          val skipped2 = skipMatching(tp1w, tp2)
          if ((skipped2 eq tp2) || !Config.fastPathForRefinedSubtype)
            tp1 match {
              case tp1: AndType =>
                // Delay calling `compareRefinedSlow` because looking up a member
                // of an `AndType` can lead to a cascade of subtyping checks
                // This twist is needed to make collection/generic/ParFactory.scala compile
                fourthTry || compareRefinedSlow
              case tp1: HKTypeLambda =>
                // HKTypeLambdas do not have members.
                fourthTry
              case _ =>
                compareRefinedSlow || fourthTry
            }
          else // fast path, in particular for refinements resulting from parameterization.
            isSubRefinements(tp1w.asInstanceOf[RefinedType], tp2, skipped2) &&
            recur(tp1, skipped2)
        }
        compareRefined
      case tp2: RecType =>
        def compareRec = tp1.safeDealias match {
          case tp1: RecType =>
            val rthis1 = tp1.recThis
            recur(tp1.parent, tp2.parent.substRecThis(tp2, rthis1))
          case NoType => false
          case _ =>
            val tp1stable = ensureStableSingleton(tp1)
            recur(fixRecs(tp1stable, tp1stable.widenExpr), tp2.parent.substRecThis(tp2, tp1stable))
        }
        compareRec
      case tp2: HKTypeLambda =>
        def compareTypeLambda: Boolean = tp1.stripTypeVar match {
          case tp1: HKTypeLambda =>
           /* Don't compare bounds of lambdas under language:Scala2, or t2994 will fail.
            * The issue is that, logically, bounds should compare contravariantly,
            * but that would invalidate a pattern exploited in t2994:
            *
            *    [X0 <: Number] -> Number   <:<    [X0] -> Any
            *
            * Under the new scheme, `[X0] -> Any` is NOT a kind that subsumes
            * all other bounds. You'd have to write `[X0 >: Any <: Nothing] -> Any` instead.
            * This might look weird, but is the only logically correct way to do it.
            *
            * Note: it would be nice if this could trigger a migration warning, but I
            * am not sure how, since the code is buried so deep in subtyping logic.
            */
            def boundsOK =
              migrateTo3 ||
              tp1.typeParams.corresponds(tp2.typeParams)((tparam1, tparam2) =>
                isSubType(tparam2.paramInfo.subst(tp2, tp1), tparam1.paramInfo))
            val saved = comparedTypeLambdas
            comparedTypeLambdas += tp1
            comparedTypeLambdas += tp2
            val variancesOK = variancesConform(tp1.typeParams, tp2.typeParams)
            try variancesOK && boundsOK && isSubType(tp1.resType, tp2.resType.subst(tp2, tp1))
            finally comparedTypeLambdas = saved
          case _ =>
            val tparams1 = tp1.typeParams
            if (tparams1.nonEmpty)
              return recur(tp1.EtaExpand(tparams1), tp2) || fourthTry
            tp2 match {
              case EtaExpansion(tycon2) if tycon2.symbol.isClass && tycon2.symbol.is(JavaDefined) =>
                recur(tp1, tycon2) || fourthTry
              case _ =>
                fourthTry
            }
        }
        compareTypeLambda
      case OrType(tp21, tp22) =>
        compareAtoms(tp1, tp2) match
          case Some(b) => return b
          case _ =>

        // The next clause handles a situation like the one encountered in i2745.scala.
        // We have:
        //
        //   x: A | B, x.type <:< A | X   where X is a type variable
        //
        // We should instantiate X to B instead of x.type or A | B. To do this, we widen
        // the LHS to A | B and recur *without indicating that this is a lowApprox*. The
        // latter point is important since otherwise we would not get to instantiate X.
        // If that succeeds, fine. If not we continue and hit the `either` below.
        // That second path is important to handle comparisons with unions of singletons,
        // as in `1 <:< 1 | 2`.
        val tp1w = tp1.widen
        if ((tp1w ne tp1) && recur(tp1w, tp2))
          return true

        val tp1a = tp1.dealiasKeepRefiningAnnots
        if (tp1a ne tp1)
          // Follow the alias; this might lead to an OrType on the left which needs to be split
          return recur(tp1a, tp2)

        // Rewrite T1 <: (T211 & T212) | T22 to T1 <: (T211 | T22) and T1 <: (T212 | T22)
        // and analogously for T1 <: T21 | (T221 & T222)
        // `|' types to the right of <: are problematic, because
        // we have to choose one constraint set or another, which might cut off
        // solutions. The rewriting delays the point where we have to choose.
        tp21 match {
          case AndType(tp211, tp212) =>
            return recur(tp1, OrType(tp211, tp22)) && recur(tp1, OrType(tp212, tp22))
          case _ =>
        }
        tp22 match {
          case AndType(tp221, tp222) =>
            return recur(tp1, OrType(tp21, tp221)) && recur(tp1, OrType(tp21, tp222))
          case _ =>
        }
        either(recur(tp1, tp21), recur(tp1, tp22)) || fourthTry
      case tp2: MatchType =>
        val reduced = tp2.reduced
        if (reduced.exists) recur(tp1, reduced) else fourthTry
      case tp2: MethodType =>
        def compareMethod = tp1 match {
          case tp1: MethodType =>
            (tp1.signature consistentParams tp2.signature) &&
            matchingMethodParams(tp1, tp2) &&
            (!tp2.isImplicitMethod || tp1.isImplicitMethod) &&
            isSubType(tp1.resultType, tp2.resultType.subst(tp2, tp1))
          case _ => false
        }
        compareMethod
      case tp2: PolyType =>
        def comparePoly = tp1 match {
          case tp1: PolyType =>
            (tp1.signature consistentParams tp2.signature) &&
            matchingPolyParams(tp1, tp2) &&
            isSubType(tp1.resultType, tp2.resultType.subst(tp2, tp1))
          case _ => false
        }
        comparePoly
      case tp2 @ ExprType(restpe2) =>
        def compareExpr = tp1 match {
          // We allow ()T to be a subtype of => T.
          // We need some subtype relationship between them so that e.g.
          // def toString   and   def toString()   don't clash when seen
          // as members of the same type. And it seems most logical to take
          // ()T <:< => T, since everything one can do with a => T one can
          // also do with a ()T by automatic () insertion.
          case tp1 @ MethodType(Nil) => isSubType(tp1.resultType, restpe2)
          case tp1 @ ExprType(restpe1) => isSubType(restpe1, restpe2)
          case _ => fourthTry
        }
        compareExpr
      case tp2 @ TypeBounds(lo2, hi2) =>
        def compareTypeBounds = tp1 match {
          case tp1 @ TypeBounds(lo1, hi1) =>
            ((lo2 eq NothingType) || isSubType(lo2, lo1)) &&
            ((hi2 eq AnyType) && !hi1.isLambdaSub || (hi2 eq AnyKindType) || isSubType(hi1, hi2))
          case tp1: ClassInfo =>
            tp2 contains tp1
          case _ =>
            false
        }
        compareTypeBounds
      case tp2: AnnotatedType if tp2.isRefining =>
        (tp1.derivesAnnotWith(tp2.annot.sameAnnotation) || defn.isBottomType(tp1)) &&
        recur(tp1, tp2.parent)
      case ClassInfo(pre2, cls2, _, _, _) =>
        def compareClassInfo = tp1 match {
          case ClassInfo(pre1, cls1, _, _, _) =>
            (cls1 eq cls2) && isSubType(pre1, pre2)
          case _ =>
            false
        }
        compareClassInfo
      case _ =>
        fourthTry
    }

    def tryBaseType(cls2: Symbol) = {
      val base = nonExprBaseType(tp1, cls2)
      if (base.exists && (base `ne` tp1))
        isSubType(base, tp2, if (tp1.isRef(cls2)) approx else approx.addLow) ||
        base.isInstanceOf[OrType] && fourthTry
          // if base is a disjunction, this might have come from a tp1 type that
          // expands to a match type. In this case, we should try to reduce the type
          // and compare the redux. This is done in fourthTry
      else fourthTry
    }

    def fourthTry: Boolean = tp1 match {
      case tp1: TypeRef =>
        tp1.info match {
          case TypeBounds(_, hi1) =>
            def compareGADT = {
              val gbounds1 = gadtBounds(tp1.symbol)
              (gbounds1 != null) &&
                (isSubTypeWhenFrozen(gbounds1.hi, tp2) ||
                narrowGADTBounds(tp1, tp2, approx, isUpper = true)) &&
                { tp2.isAny || GADTusage(tp1.symbol) }
            }
            isSubType(hi1, tp2, approx.addLow) || compareGADT || tryLiftedToThis1
          case _ =>
            def isNullable(tp: Type): Boolean = tp.widenDealias match {
              case tp: TypeRef => tp.symbol.isNullableClass
              case tp: RefinedOrRecType => isNullable(tp.parent)
              case tp: AppliedType => isNullable(tp.tycon)
              case AndType(tp1, tp2) => isNullable(tp1) && isNullable(tp2)
              case OrType(tp1, tp2) => isNullable(tp1) || isNullable(tp2)
              case _ => false
            }
            val sym1 = tp1.symbol
            (sym1 eq NothingClass) && tp2.isValueTypeOrLambda ||
            (sym1 eq NullClass) && isNullable(tp2)
        }
      case tp1 @ AppliedType(tycon1, args1) =>
        compareAppliedType1(tp1, tycon1, args1)
      case tp1: SingletonType =>
        def comparePaths = tp2 match
          case tp2: TermRef =>
            compareAtoms(tp1, tp2, knownSingletons = true).getOrElse(false)
            || { // needed to make from-tasty work. test cases: pos/i1753.scala, pos/t839.scala
              tp2.info.widenExpr.dealias match
                case tp2i: SingletonType => recur(tp1, tp2i)
                case _ => false
            }
          case _ => false
        comparePaths || isNewSubType(tp1.underlying.widenExpr)
      case tp1: RefinedType =>
        isNewSubType(tp1.parent)
      case tp1: RecType =>
        isNewSubType(tp1.parent)
      case tp1: HKTypeLambda =>
        def compareHKLambda = tp1 match {
          case EtaExpansion(tycon1) if tycon1.symbol.isClass && tycon1.symbol.is(JavaDefined) =>
            // It's a raw type that was mistakenly eta-expanded to a hk-type.
            // This can happen because we do not cook types coming from Java sources
            recur(tycon1, tp2)
          case _ => tp2 match {
            case tp2: HKTypeLambda => false // this case was covered in thirdTry
            case _ => tp2.typeParams.hasSameLengthAs(tp1.paramRefs) && isSubType(tp1.resultType, tp2.appliedTo(tp1.paramRefs))
          }
        }
        compareHKLambda
      case AndType(tp11, tp12) =>
        val tp2a = tp2.dealiasKeepRefiningAnnots
        if (tp2a ne tp2) // Follow the alias; this might avoid truncating the search space in the either below
          return recur(tp1, tp2a)

        // Rewrite (T111 | T112) & T12 <: T2 to (T111 & T12) <: T2 and (T112 | T12) <: T2
        // and analogously for T11 & (T121 | T122) & T12 <: T2
        // `&' types to the left of <: are problematic, because
        // we have to choose one constraint set or another, which might cut off
        // solutions. The rewriting delays the point where we have to choose.
        tp11 match {
          case OrType(tp111, tp112) =>
            return recur(AndType(tp111, tp12), tp2) && recur(AndType(tp112, tp12), tp2)
          case _ =>
        }
        tp12 match {
          case OrType(tp121, tp122) =>
            return recur(AndType(tp11, tp121), tp2) && recur(AndType(tp11, tp122), tp2)
          case _ =>
        }
        val tp1norm = simplifyAndTypeWithFallback(tp11, tp12, tp1)
        if (tp1 ne tp1norm) recur(tp1norm, tp2)
        else either(recur(tp11, tp2), recur(tp12, tp2))
      case tp1: MatchType =>
        def compareMatch = tp2 match {
          case tp2: MatchType =>
            isSameType(tp1.scrutinee, tp2.scrutinee) &&
            tp1.cases.corresponds(tp2.cases)(isSubType)
          case _ => false
        }
        recur(tp1.underlying, tp2) || compareMatch
      case tp1: AnnotatedType if tp1.isRefining =>
        isNewSubType(tp1.parent)
      case JavaArrayType(elem1) =>
        def compareJavaArray = tp2 match {
          case JavaArrayType(elem2) => isSubType(elem1, elem2)
          case _ => tp2.isAnyRef
        }
        compareJavaArray
      case tp1: ExprType if ctx.phase.id > ctx.gettersPhase.id =>
        // getters might have converted T to => T, need to compensate.
        recur(tp1.widenExpr, tp2)
      case _ =>
        false
    }

    /** Subtype test for the hk application `tp2 = tycon2[args2]`.
     */
    def compareAppliedType2(tp2: AppliedType, tycon2: Type, args2: List[Type]): Boolean = {
      val tparams = tycon2.typeParams
      if (tparams.isEmpty) return false // can happen for ill-typed programs, e.g. neg/tcpoly_overloaded.scala

      /** True if `tp1` and `tp2` have compatible type constructors and their
       *  corresponding arguments are subtypes relative to their variance (see `isSubArgs`).
       */
      def isMatchingApply(tp1: Type): Boolean = tp1 match {
        case AppliedType(tycon1, args1) =>
          def loop(tycon1: Type, args1: List[Type]): Boolean = tycon1.dealiasKeepRefiningAnnots match {
            case tycon1: TypeParamRef =>
              (tycon1 == tycon2 ||
               canConstrain(tycon1) && isSubType(tycon1, tycon2)) &&
              isSubArgs(args1, args2, tp1, tparams)
            case tycon1: TypeRef =>
              tycon2.dealiasKeepRefiningAnnots match {
                case tycon2: TypeRef =>
                  val tycon1sym = tycon1.symbol
                  val tycon2sym = tycon2.symbol

                  var touchedGADTs = false
                  var gadtIsInstantiated = false
                  def byGadtBounds(sym: Symbol, tp: Type, fromAbove: Boolean): Boolean = {
                    touchedGADTs = true
                    val b = gadtBounds(sym)
                    def boundsDescr = if b == null then "null" else b.show
                    b != null && inFrozenGadt {
                      if fromAbove then isSubType(b.hi, tp) else isSubType(tp, b.lo)
                    } && {
                      gadtIsInstantiated = b.isInstanceOf[TypeAlias]
                      true
                    }
                  }

                  val res = (
                    tycon1sym == tycon2sym
                      && isSubType(tycon1.prefix, tycon2.prefix)
                      || byGadtBounds(tycon1sym, tycon2, fromAbove = true)
                      || byGadtBounds(tycon2sym, tycon1, fromAbove = false)
                  ) && {
                    // There are two cases in which we can assume injectivity.
                    // First we check if either sym is a class.
                    // Then:
                    // 1) if we didn't touch GADTs, then both symbols are the same
                    //    (b/c of an earlier condition) and both are the same class
                    // 2) if we touched GADTs, then the _other_ symbol (class syms
                    //    cannot have GADT constraints), the one w/ GADT cstrs,
                    //    must be instantiated, making the two tycons equal
                    val tyconIsInjective =
                      (tycon1sym.isClass || tycon2sym.isClass)
                        && (if touchedGADTs then gadtIsInstantiated else true)
                    def checkSubArgs() = isSubArgs(args1, args2, tp1, tparams)
                    // we only record GADT constraints if *both* tycons are effectively injective
                    if (tyconIsInjective) checkSubArgs()
                    else inFrozenGadt { checkSubArgs() }
                  }
                  if (res && touchedGADTs) GADTused = true
                  res
                case _ =>
                  false
              }
            case tycon1: TypeVar =>
              loop(tycon1.underlying, args1)
            case tycon1: AnnotatedType if !tycon1.isRefining =>
              loop(tycon1.underlying, args1)
            case _ =>
              false
          }
          loop(tycon1, args1)
        case _ =>
          false
      }

      /** `param2` can be instantiated to a type application prefix of the LHS
       *  or to a type application prefix of one of the LHS base class instances
       *  and the resulting type application is a supertype of `tp1`,
       *  or fallback to fourthTry.
       */
      def canInstantiate(tycon2: TypeParamRef): Boolean = {

        /** Let
         *
         *    `tparams_1, ..., tparams_k-1`    be the type parameters of the rhs
         *    `tparams1_1, ..., tparams1_n-1`  be the type parameters of the constructor of the lhs
         *    `args1_1, ..., args1_n-1`        be the type arguments of the lhs
         *    `d  =  n - k`
         *
         *  Returns `true` iff `d >= 0` and `tycon2` can be instantiated to
         *
         *      [tparams1_d, ... tparams1_n-1] -> tycon1[args_1, ..., args_d-1, tparams_d, ... tparams_n-1]
         *
         *  such that the resulting type application is a supertype of `tp1`.
         */
        def appOK(tp1base: Type) = tp1base match {
          case tp1base: AppliedType =>
            var tycon1 = tp1base.tycon
            val args1 = tp1base.args
            val tparams1all = tycon1.typeParams
            val lengthDiff = tparams1all.length - tparams.length
            lengthDiff >= 0 && {
              val tparams1 = tparams1all.drop(lengthDiff)
              variancesConform(tparams1, tparams) && {
                if (lengthDiff > 0)
                  tycon1 = HKTypeLambda(tparams1.map(_.paramName))(
                    tl => tparams1.map(tparam => tl.integrate(tparams, tparam.paramInfo).bounds),
                    tl => tp1base.tycon.appliedTo(args1.take(lengthDiff) ++
                            tparams1.indices.toList.map(tl.paramRefs(_))))
                (assumedTrue(tycon2) || isSubType(tycon1.ensureLambdaSub, tycon2)) &&
                recur(tp1, tycon1.appliedTo(args2))
              }
            }
          case _ => false
        }

        tp1.widen match {
          case tp1w: AppliedType => appOK(tp1w)
          case tp1w =>
            tp1w.typeSymbol.isClass && {
              val classBounds = tycon2.classSymbols
              def liftToBase(bcs: List[ClassSymbol]): Boolean = bcs match {
                case bc :: bcs1 =>
                  classBounds.exists(bc.derivesFrom) && appOK(nonExprBaseType(tp1, bc))
                  || liftToBase(bcs1)
                case _ =>
                  false
              }
              liftToBase(tp1w.baseClasses)
            } ||
            fourthTry
        }
      }

      /** Fall back to comparing either with `fourthTry` or against the lower
       *  approximation of the rhs.
       *  @param   tyconLo   The type constructor's lower approximation.
       */
      def fallback(tyconLo: Type) =
        either(fourthTry, isSubApproxHi(tp1, tyconLo.applyIfParameterized(args2)))

      /** Let `tycon2bounds` be the bounds of the RHS type constructor `tycon2`.
       *  Let `app2 = tp2` where the type constructor of `tp2` is replaced by
       *  `tycon2bounds.lo`.
       *  If both bounds are the same, continue with `tp1 <:< app2`.
       *  otherwise continue with either
       *
       *    tp1 <:< tp2    using fourthTry (this might instantiate params in tp1)
       *    tp1 <:< app2   using isSubType (this might instantiate params in tp2)
       */
      def compareLower(tycon2bounds: TypeBounds, tyconIsTypeRef: Boolean): Boolean =
        if ((tycon2bounds.lo `eq` tycon2bounds.hi) && !tycon2bounds.isInstanceOf[MatchAlias])
          if (tyconIsTypeRef) recur(tp1, tp2.superType)
          else isSubApproxHi(tp1, tycon2bounds.lo.applyIfParameterized(args2))
        else
          fallback(tycon2bounds.lo)

      tycon2 match {
        case param2: TypeParamRef =>
          isMatchingApply(tp1) ||
          canConstrain(param2) && canInstantiate(param2) ||
          compareLower(bounds(param2), tyconIsTypeRef = false)
        case tycon2: TypeRef =>
          isMatchingApply(tp1) ||
          defn.isCompiletimeAppliedType(tycon2.symbol) && compareCompiletimeAppliedType(tp2, tp1, fromBelow = true) || {
            tycon2.info match {
              case info2: TypeBounds =>
                compareLower(info2, tyconIsTypeRef = true)
              case info2: ClassInfo =>
                tycon2.name.toString.startsWith("Tuple") &&
                  defn.isTupleType(tp2) && recur(tp1, tp2.toNestedPairs) ||
                tryBaseType(info2.cls)
              case _ =>
                fourthTry
            }
          } || tryLiftedToThis2

        case _: TypeVar =>
          recur(tp1, tp2.superType)
        case tycon2: AnnotatedType if !tycon2.isRefining =>
          recur(tp1, tp2.superType)
        case tycon2: AppliedType =>
          fallback(tycon2.lowerBound)
        case _ =>
          false
      }
    }

    /** Subtype test for the application `tp1 = tycon1[args1]`.
     */
    def compareAppliedType1(tp1: AppliedType, tycon1: Type, args1: List[Type]): Boolean =
      tycon1 match {
        case param1: TypeParamRef =>
          def canInstantiate = tp2 match {
            case AppliedType(tycon2, args2) =>
              isSubType(param1, tycon2.ensureLambdaSub) && isSubArgs(args1, args2, tp1, tycon2.typeParams)
            case _ =>
              false
          }
          canConstrain(param1) && canInstantiate ||
            isSubType(bounds(param1).hi.applyIfParameterized(args1), tp2, approx.addLow)
        case tycon1: TypeRef =>
          val sym = tycon1.symbol
          !sym.isClass && {
            defn.isCompiletimeAppliedType(sym) && compareCompiletimeAppliedType(tp1, tp2, fromBelow = false) ||
            recur(tp1.superType, tp2) ||
            tryLiftedToThis1
          }
        case tycon1: TypeProxy =>
          recur(tp1.superType, tp2)
        case _ =>
          false
      }

    /** Compare `tp` of form `S[arg]` with `other`, via ">:>" if fromBelow is true, "<:<" otherwise.
     *  If `arg` is a Nat constant `n`, proceed with comparing `n + 1` and `other`.
     *  Otherwise, if `other` is a Nat constant `n`, proceed with comparing `arg` and `n - 1`.
     */
    def compareS(tp: AppliedType, other: Type, fromBelow: Boolean): Boolean = tp.args match {
      case arg :: Nil =>
        natValue(arg) match {
          case Some(n) if n != Int.MaxValue =>
            val succ = ConstantType(Constant(n + 1))
            if (fromBelow) recur(other, succ) else recur(succ, other)
          case none =>
            natValue(other) match {
              case Some(n) if n > 0 =>
                val pred = ConstantType(Constant(n - 1))
                if (fromBelow) recur(pred, arg) else recur(arg, pred)
              case none =>
                false
            }
        }
      case _ => false
    }

    /** Compare `tp` of form `tycon[...args]`, where `tycon` is a scala.compiletime type,
     *  with `other` via ">:>" if fromBelow is true, "<:<" otherwise.
     *  Delegates to compareS if `tycon` is scala.compiletime.S. Otherwise, constant folds if possible.
     */
    def compareCompiletimeAppliedType(tp: AppliedType, other: Type, fromBelow: Boolean): Boolean = {
      if (defn.isCompiletime_S(tp.tycon.typeSymbol)) compareS(tp, other, fromBelow)
      else {
        val folded = tp.tryCompiletimeConstantFold
        if (fromBelow) recur(other, folded) else recur(folded, other)
      }
    }

    /** Like tp1 <:< tp2, but returns false immediately if we know that
     *  the case was covered previously during subtyping.
     */
    def isNewSubType(tp1: Type): Boolean =
      if (isCovered(tp1) && isCovered(tp2))
        //println(s"useless subtype: $tp1 <:< $tp2")
        false
      else isSubType(tp1, tp2, approx.addLow)

    def isSubApproxHi(tp1: Type, tp2: Type): Boolean =
      tp1.eq(tp2) || tp2.ne(NothingType) && isSubType(tp1, tp2, approx.addHigh)

    def tryLiftedToThis1: Boolean = {
      val tp1a = liftToThis(tp1)
      (tp1a ne tp1) && recur(tp1a, tp2)
    }

    def tryLiftedToThis2: Boolean = {
      val tp2a = liftToThis(tp2)
      (tp2a ne tp2) && recur(tp1, tp2a)
    }

    // begin recur
    if (tp2 eq NoType) false
    else if (tp1 eq tp2) true
    else {
      val saved = constraint
      val savedSuccessCount = successCount
      try {
        recCount = recCount + 1
        if (recCount >= Config.LogPendingSubTypesThreshold) monitored = true
        val result = if (monitored) monitoredIsSubType else firstTry
        recCount = recCount - 1
        if (!result) state.resetConstraintTo(saved)
        else if (recCount == 0 && needsGc) {
          state.gc()
          needsGc = false
        }
        if (Stats.monitored) recordStatistics(result, savedSuccessCount)
        result
      }
      catch {
        case NonFatal(ex) =>
          if (ex.isInstanceOf[AssertionError]) showGoal(tp1, tp2)
          recCount -= 1
          state.resetConstraintTo(saved)
          successCount = savedSuccessCount
          throw ex
      }
    }
  }

  private def nonExprBaseType(tp: Type, cls: Symbol)(using Context): Type =
    if tp.isInstanceOf[ExprType] then NoType
    else tp.baseType(cls)

  /** If `tp` is an external reference to an enclosing module M that contains opaque types,
   *  convert to M.this.
   *  Note: It would be legal to do the lifting also if M does not contain opaque types,
   *  but in this case the retries in tryLiftedToThis would be redundant.
   */
  private def liftToThis(tp: Type): Type = {

    def findEnclosingThis(moduleClass: Symbol, from: Symbol): Type =
      if ((from.owner eq moduleClass) && from.isPackageObject && from.is(Opaque)) from.thisType
      else if (from.is(Package)) tp
      else if ((from eq moduleClass) && from.is(Opaque)) from.thisType
      else if (from eq NoSymbol) tp
      else findEnclosingThis(moduleClass, from.owner)

    tp match {
      case tp: TermRef if tp.symbol.is(Module) =>
        findEnclosingThis(tp.symbol.moduleClass, ctx.owner)
      case tp: TypeRef =>
        val pre1 = liftToThis(tp.prefix)
        if ((pre1 ne tp.prefix) && pre1.exists) tp.withPrefix(pre1) else tp
      case tp: ThisType if tp.cls.is(Package) =>
        findEnclosingThis(tp.cls, ctx.owner)
      case tp: AppliedType =>
        val tycon1 = liftToThis(tp.tycon)
        if (tycon1 ne tp.tycon) tp.derivedAppliedType(tycon1, tp.args) else tp
      case tp: TypeVar if tp.isInstantiated =>
        liftToThis(tp.inst)
      case tp: AnnotatedType =>
        val parent1 = liftToThis(tp.parent)
        if (parent1 ne tp.parent) tp.derivedAnnotatedType(parent1, tp.annot) else tp
      case _ =>
        tp
    }
  }

  /** Optionally, the `n` such that `tp <:< ConstantType(Constant(n: Int))` */
  def natValue(tp: Type): Option[Int] = constValue(tp) match {
    case Some(Constant(n: Int)) if n >= 0 => Some(n)
    case _ => None
  }

  /** Optionally, the constant `c` such that `tp <:< ConstantType(c)` */
  def constValue(tp: Type): Option[Constant] = {
    val ct = new AnyConstantType
    if (isSubTypeWhenFrozen(tp, ct))
      ct.tpe match {
        case ConstantType(c) => Some(c)
        case _ => None
      }
    else None
  }

  /** If both `tp1` and `tp2` have atoms information, compare the atoms
   *  in a Some, otherwise None.
   *  @param knownSingletons  If true, we are coming from a comparison of two singleton types
   *                          This influences the comparison as shown below:
   *
   *  Say you have singleton types p.type and q.type the atoms of p.type are `{p.type}..{p.type}`,
   *  and the atoms of `q.type` are `{}..{p.type}`. Normally the atom comparison between p's
   *  atoms and q's atoms gives false. But in this case we know that `q.type` is an alias of `p.type`
   *  so we are still allowed to conclude that `p.type <:< q.type`. A situation where this happens
   *  is in i6635.scala. Here,
   *
   *     p: A, q: B & p.type   and we want to conclude that p.type <: q.type.
   */
  def compareAtoms(tp1: Type, tp2: Type, knownSingletons: Boolean = false): Option[Boolean] =

    /** Check whether we can compare the given set of atoms with another to determine
     *  a subtype test between OrTypes. There is one situation where this is not
     *  the case, which has to do with SkolemTypes. TreeChecker sometimes expects two
     *  types to be equal that have different skolems. To account for this, we identify
     *  two different skolems in all phases `p`, where `p.isTyper` is false.
     *  But in that case comparing two sets of atoms that contain skolems
     *  for equality would give the wrong result, so we should not use the sets
     *  for comparisons.
     */
    def canCompare(ts: Set[Type]) = ctx.phase.isTyper || {
      val hasSkolems = new ExistsAccumulator(_.isInstanceOf[SkolemType]) {
        override def stopAtStatic = true
      }
      !ts.exists(hasSkolems(false, _))
    }
    def verified(result: Boolean): Boolean =
      if Config.checkAtomsComparisons then
        try
          canCompareAtoms = false
          val regular = recur(tp1, tp2)
          assert(result == regular,
            i"""Atoms inconsistency for $tp1 <:< $tp2
              |atoms predicted $result
              |atoms1 = ${tp1.atoms}
              |atoms2 = ${tp2.atoms}""")
        finally canCompareAtoms = true
      result

    tp2.atoms match
      case Atoms.Range(lo2, hi2) if canCompareAtoms && canCompare(hi2) =>
        tp1.atoms match
          case Atoms.Range(lo1, hi1) =>
            if hi1.subsetOf(lo2) || knownSingletons && hi2.size == 1 && hi1 == hi2 then
              Some(verified(true))
            else if !lo1.subsetOf(hi2) then
              Some(verified(false))
            else
              None
          case _ => Some(verified(recur(tp1, NothingType)))
      case _ => None

  /** Subtype test for corresponding arguments in `args1`, `args2` according to
   *  variances in type parameters `tparams2`.
   *
   *  @param  tp1       The applied type containing `args1`
   *  @param  tparams2  The type parameters of the type constructor applied to `args2`
   */
  def isSubArgs(args1: List[Type], args2: List[Type], tp1: Type, tparams2: List[ParamInfo]): Boolean = {
    /** The bounds of parameter `tparam`, where all references to type paramneters
     *  are replaced by corresponding arguments (or their approximations in the case of
     *  wildcard arguments).
     */
    def paramBounds(tparam: Symbol): TypeBounds =
      tparam.info.substApprox(tparams2.asInstanceOf[List[Symbol]], args2).bounds

    def recurArgs(args1: List[Type], args2: List[Type], tparams2: List[ParamInfo]): Boolean =
      if (args1.isEmpty) args2.isEmpty
      else args2.nonEmpty && {
        val tparam = tparams2.head
        val v = tparam.paramVarianceSign

        /** Try a capture conversion:
         *  If the original left-hand type `leftRoot` is a path `p.type`,
         *  and the current widened left type is an application with wildcard arguments
         *  such as `C[?]`, where `X` is `C`'s type parameter corresponding to the `_` argument,
         *  compare with `C[p.X]` instead. Otherwise approximate based on variance.
         *  Also do a capture conversion in either of the following cases:
         *
         *   - If we are after typer. We generally relax soundness requirements then.
         *     We need the relaxed condition to correctly compute overriding relationships.
         *     Missing this case led to AbstractMethod errors in the bootstrap.
         *
         *   - If we are in mode TypevarsMissContext, which means we test implicits
         *     for eligibility. In this case, we can be more permissive, since it's
         *     just a pre-check. This relaxation is needed since the full
         *     implicit typing might perform an adaptation that skolemizes the
         *     type of a synthesized tree before comparing it with an expected type.
         *     But no such adaptation is applied for implicit eligibility
         *     testing, so we have to compensate.
         *
         *  Note: Doing the capture conversion on path types is actually not necessary
         *  since we can already deal with the situation through skolemization in Typer#captureWildcards.
         *  But performance tests indicate that it's better to do it, since we avoid
         *  skolemizations, which are more expensive . And, besides, capture conversion on
         *  paths is less intrusive than skolemization.
         */
        def compareCaptured(arg1: TypeBounds, arg2: Type) = tparam match {
          case tparam: Symbol =>
            if (leftRoot.isStable || (ctx.isAfterTyper || ctx.mode.is(Mode.TypevarsMissContext))
                && leftRoot.member(tparam.name).exists) {
              val captured = TypeRef(leftRoot, tparam)
              try isSubArg(captured, arg2)
              catch case ex: TypeError =>
                // The captured reference could be illegal and cause a
                // TypeError to be thrown in argDenot
                false
            }
            else if (v > 0)
              isSubType(paramBounds(tparam).hi, arg2)
            else if (v < 0)
              isSubType(arg2, paramBounds(tparam).lo)
            else
              false
          case _ =>
            false
        }

        def isSubArg(arg1: Type, arg2: Type): Boolean = arg2 match {
          case arg2: TypeBounds =>
            val arg1norm = arg1 match {
              case arg1: TypeBounds =>
                tparam match {
                  case tparam: Symbol => arg1 & paramBounds(tparam)
                  case _ => arg1 // This case can only arise when a hk-type is illegally instantiated with a wildcard
                }
              case _ => arg1
            }
            arg2.contains(arg1norm)
          case _ =>
            arg1 match {
              case arg1: TypeBounds =>
                compareCaptured(arg1, arg2)
              case _ =>
                (v > 0 || isSubType(arg2, arg1)) &&
                (v < 0 || isSubType(arg1, arg2))
            }
        }

        isSubArg(args1.head, args2.head)
      } && recurArgs(args1.tail, args2.tail, tparams2.tail)

    recurArgs(args1, args2, tparams2)
  }

  /** Test whether `tp1` has a base type of the form `B[T1, ..., Tn]` where
   *   - `B` derives from one of the class symbols of `tp2`,
   *   - the type parameters of `B` match one-by-one the variances of `tparams`,
   *   - `B` satisfies predicate `p`.
   */
  private def testLifted(tp1: Type, tp2: Type, tparams: List[TypeParamInfo], p: Type => Boolean): Boolean = {
    val classBounds = tp2.classSymbols
    def recur(bcs: List[ClassSymbol]): Boolean = bcs match {
      case bc :: bcs1 =>
        (classBounds.exists(bc.derivesFrom) &&
          variancesConform(bc.typeParams, tparams) &&
          p(nonExprBaseType(tp1, bc))
        ||
        recur(bcs1))
      case nil =>
        false
    }
    recur(tp1.baseClasses)
  }

  /** Replace any top-level recursive type `{ z => T }` in `tp` with
   *  `[z := anchor]T`.
   */
  private def fixRecs(anchor: SingletonType, tp: Type): Type = {
    def fix(tp: Type): Type = tp.stripTypeVar match {
      case tp: RecType => fix(tp.parent).substRecThis(tp, anchor)
      case tp @ RefinedType(parent, rname, rinfo) => tp.derivedRefinedType(fix(parent), rname, rinfo)
      case tp: TypeParamRef => fixOrElse(bounds(tp).hi, tp)
      case tp: TypeProxy => fixOrElse(tp.underlying, tp)
      case tp: AndType => tp.derivedAndType(fix(tp.tp1), fix(tp.tp2))
      case tp: OrType  => tp.derivedOrType (fix(tp.tp1), fix(tp.tp2))
      case tp => tp
    }
    def fixOrElse(tp: Type, fallback: Type) = {
      val tp1 = fix(tp)
      if (tp1 ne tp) tp1 else fallback
    }
    fix(tp)
  }

  /** Returns true iff the result of evaluating either `op1` or `op2` is true and approximates resulting constraints.
   *
   *  If we're inferring GADT bounds or constraining a method based on its
   *  expected type, we infer only the _necessary_ constraints, this means we
   *  keep the smaller constraint if any, or no constraint at all. This is
   *  necessary for GADT bounds inference to be sound. When constraining a
   *  method, this avoid committing of constraints that would later prevent us
   *  from typechecking method arguments, see or-inf.scala and and-inf.scala for
   *  examples.
   *
   *  Otherwise, we infer _sufficient_ constraints: we try to keep the smaller of
   *  the two constraints, but if never is smaller than the other, we just pick
   *  the first one.
   *
   *  @see [[necessaryEither]] for the GADT / result type case
   *  @see [[sufficientEither]] for the normal case
   */
  protected def either(op1: => Boolean, op2: => Boolean): Boolean =
    if ctx.mode.is(Mode.GadtConstraintInference) || ctx.mode.is(Mode.ConstrainResult) then
      necessaryEither(op1, op2)
    else
      sufficientEither(op1, op2)

  /** Returns true iff the result of evaluating either `op1` or `op2` is true,
   *  trying at the same time to keep the constraint as wide as possible.
   *  E.g, if
   *
   *    tp11 <:< tp12 = true   with post-constraint c1
   *    tp12 <:< tp22 = true   with post-constraint c2
   *
   *  and c1 subsumes c2, then c2 is kept as the post-constraint of the result,
   *  otherwise c1 is kept.
   *
   *  This method is used to approximate a solution in one of the following cases
   *
   *     T1 & T2 <:< T3
   *     T1 <:< T2 | T3
   *
   *  In the first case (the second one is analogous), we have a choice whether we
   *  want to establish the subtyping judgement using
   *
   *     T1 <:< T3   or    T2 <:< T3
   *
   *  as a precondition. Either precondition might constrain type variables.
   *  The purpose of this method is to pick the precondition that constrains less.
   *  The method is not complete, because sometimes there is no best solution. Example:
   *
   *     A? & B?  <:  T
   *
   *  Here, each precondition leads to a different constraint, and neither of
   *  the two post-constraints subsumes the other.
   *
   *  Note that to be complete when it comes to typechecking, we would instead need to backtrack
   *  and attempt to typecheck with the other constraint.
   *
   *  Method name comes from the notion that we are keeping a constraint which is sufficient to satisfy
   *  one of subtyping relationships.
   */
  private def sufficientEither(op1: => Boolean, op2: => Boolean): Boolean = {
    val preConstraint = constraint
    op1 && {
      val leftConstraint = constraint
      constraint = preConstraint
      if (!(op2 && subsumes(leftConstraint, constraint, preConstraint))) {
        if (constr != noPrinter && !subsumes(constraint, leftConstraint, preConstraint))
          constr.println(i"CUT - prefer $leftConstraint over $constraint")
        constraint = leftConstraint
      }
      true
    } || op2
  }

  /** Returns true iff the result of evaluating either `op1` or `op2` is true, keeping the smaller constraint if any.
   *  E.g., if
   *
   *    tp11 <:< tp12 = true   with constraint c1 and GADT constraint g1
   *    tp12 <:< tp22 = true   with constraint c2 and GADT constraint g2
   *
   *  We keep:
   *    - (c1, g1) if c2 subsumes c1 and g2 subsumes g1
   *    - (c2, g2) if c1 subsumes c2 and g1 subsumes g2
   *    - neither constraint pair otherwise.
   *
   *  Like [[sufficientEither]], this method is used to approximate a solution in one of the following cases:
   *
   *     T1 & T2 <:< T3
   *     T1 <:< T2 | T3
   *
   *  Unlike [[sufficientEither]], this method is used in GADTConstraintInference mode, when we are attempting
   *  to infer GADT constraints that necessarily follow from the subtyping relationship. For instance, if we have
   *
   *     enum Expr[T] {
   *       case IntExpr(i: Int) extends Expr[Int]
   *       case StrExpr(s: String) extends Expr[String]
   *     }
   *
   *  and `A` is an abstract type and we know that
   *
   *     Expr[A] <: IntExpr | StrExpr
   *
   *  (the case with &-type is analogous) then this may follow either from
   *
   *     Expr[A] <: IntExpr    or    Expr[A] <: StrExpr
   *
   *  Since we don't know which branch is true, we need to give up and not keep either constraint. OTOH, if one
   *  constraint pair is subsumed by the other, we know that it is necessary for both cases and therefore we can
   *  keep it.
   *
   *  Like [[sufficientEither]], this method is not complete because sometimes, the necessary constraint
   *  is neither of the pairs. For instance, if
   *
   *     g1 = { A = Int, B = String }
   *     g2 = { A = Int, B = Int }
   *
   *  then the necessary constraint is { A = Int }, but correctly inferring that is, as far as we know, too expensive.
   *
   *  This method is also used in ConstrainResult mode
   *  to avoid inference getting stuck due to lack of backtracking,
   *  see or-inf.scala and and-inf.scala for examples.
   *
   *  Method name comes from the notion that we are keeping the constraint which is necessary to satisfy both
   *  subtyping relationships.
   */
  private def necessaryEither(op1: => Boolean, op2: => Boolean): Boolean =
    val preConstraint = constraint
    val preGadt = ctx.gadt.fresh

    def allSubsumes(leftGadt: GadtConstraint, rightGadt: GadtConstraint, left: Constraint, right: Constraint): Boolean =
      subsumes(left, right, preConstraint) && preGadt.match
        case preGadt: ProperGadtConstraint =>
          preGadt.subsumes(leftGadt, rightGadt, preGadt)
        case _ =>
          true

    if op1 then
      val op1Constraint = constraint
      val op1Gadt = ctx.gadt.fresh
      constraint = preConstraint
      ctx.gadt.restore(preGadt)
      if op2 then
        if allSubsumes(op1Gadt, ctx.gadt, op1Constraint, constraint) then
          gadts.println(i"GADT CUT - prefer ${ctx.gadt} over $op1Gadt")
          constr.println(i"CUT - prefer $constraint over $op1Constraint")
        else if allSubsumes(ctx.gadt, op1Gadt, constraint, op1Constraint) then
          gadts.println(i"GADT CUT - prefer $op1Gadt over ${ctx.gadt}")
          constr.println(i"CUT - prefer $op1Constraint over $constraint")
          constraint = op1Constraint
          ctx.gadt.restore(op1Gadt)
        else
          gadts.println(i"GADT CUT - no constraint is preferable, reverting to $preGadt")
          constr.println(i"CUT - no constraint is preferable, reverting to $preConstraint")
          constraint = preConstraint
          ctx.gadt.restore(preGadt)
      else
        constraint = op1Constraint
        ctx.gadt.restore(op1Gadt)
      true
    else op2
  end necessaryEither

  /** Does type `tp1` have a member with name `name` whose normalized type is a subtype of
   *  the normalized type of the refinement `tp2`?
   *  Normalization is as follows: If `tp2` contains a skolem to its refinement type,
   *  rebase both itself and the member info of `tp` on a freshly created skolem type.
   */
  protected def hasMatchingMember(name: Name, tp1: Type, tp2: RefinedType): Boolean =
    trace(i"hasMatchingMember($tp1 . $name :? ${tp2.refinedInfo}), mbr: ${tp1.member(name).info}", subtyping) {
      val rinfo2 = tp2.refinedInfo

      // If the member is an abstract type and the prefix is a path, compare the member itself
      // instead of its bounds. This case is needed situations like:
      //
      //    class C { type T }
      //    val foo: C
      //    foo.type <: C { type T {= , <: , >:} foo.T }
      //
      // or like:
      //
      //    class C[T]
      //    C[?] <: C[TV]
      //
      // where TV is a type variable. See i2397.scala for an example of the latter.
      def matchAbstractTypeMember(info1: Type) = info1 match {
        case TypeBounds(lo, hi) if lo ne hi =>
          tp2.refinedInfo match {
            case rinfo2: TypeBounds if tp1.isStable =>
              val ref1 = tp1.widenExpr.select(name)
              isSubType(rinfo2.lo, ref1) && isSubType(ref1, rinfo2.hi)
            case _ =>
              false
          }
        case _ => false
      }

      def qualifies(m: SingleDenotation) =
        isSubType(m.info.widenExpr, rinfo2.widenExpr) || matchAbstractTypeMember(m.info)

      tp1.member(name) match { // inlined hasAltWith for performance
        case mbr: SingleDenotation => qualifies(mbr)
        case mbr => mbr hasAltWith qualifies
      }
    }

  final def ensureStableSingleton(tp: Type): SingletonType = tp.stripTypeVar match {
    case tp: SingletonType if tp.isStable => tp
    case tp: ValueType => SkolemType(tp)
    case tp: TypeProxy => ensureStableSingleton(tp.underlying)
    case tp => assert(ctx.reporter.errorsReported); SkolemType(tp)
  }

  /** Skip refinements in `tp2` which match corresponding refinements in `tp1`.
   *  "Match" means:
   *   - they appear in the same order,
   *   - they refine the same names,
   *   - the refinement in `tp1` is an alias type, and
   *   - neither refinement refers back to the refined type via a refined this.
   *  @return  The parent type of `tp2` after skipping the matching refinements.
   */
  private def skipMatching(tp1: Type, tp2: RefinedType): Type = tp1 match {
    case tp1 @ RefinedType(parent1, name1, rinfo1: TypeAlias) if name1 == tp2.refinedName =>
      tp2.parent match {
        case parent2: RefinedType => skipMatching(parent1, parent2)
        case parent2 => parent2
      }
    case _ => tp2
  }

  /** Are refinements in `tp1` pairwise subtypes of the refinements of `tp2`
   *  up to parent type `limit`?
   *  @pre `tp1` has the necessary number of refinements, they are type aliases,
   *       and their names match the corresponding refinements in `tp2`.
   *       Further, no refinement refers back to the refined type via a refined this.
   *  The precondition is established by `skipMatching`.
   */
  private def isSubRefinements(tp1: RefinedType, tp2: RefinedType, limit: Type): Boolean =
    isSubType(tp1.refinedInfo, tp2.refinedInfo)
    && ((tp2.parent eq limit)
       || isSubRefinements(
            tp1.parent.asInstanceOf[RefinedType],
            tp2.parent.asInstanceOf[RefinedType], limit))

  /** A type has been covered previously in subtype checking if it
   *  is some combination of TypeRefs that point to classes, where the
   *  combiners are AppliedTypes, RefinedTypes, RecTypes, And/Or-Types or AnnotatedTypes.
   */
  private def isCovered(tp: Type): Boolean = tp.dealiasKeepRefiningAnnots.stripTypeVar match {
    case tp: TypeRef => tp.symbol.isClass && tp.symbol != NothingClass && tp.symbol != NullClass
    case tp: AppliedType => isCovered(tp.tycon)
    case tp: RefinedOrRecType => isCovered(tp.parent)
    case tp: AndType => isCovered(tp.tp1) && isCovered(tp.tp2)
    case tp: OrType  => isCovered(tp.tp1) && isCovered(tp.tp2)
    case _ => false
  }

  /** Defer constraining type variables when compared against prototypes */
  def isMatchedByProto(proto: ProtoType, tp: Type): Boolean = tp.stripTypeVar match {
    case tp: TypeParamRef if constraint contains tp => true
    case _ => proto.isMatchedBy(tp, keepConstraint = true)
  }

  /** Narrow gadt.bounds for the type parameter referenced by `tr` to include
   *  `bound` as an upper or lower bound (which depends on `isUpper`).
   *  Test that the resulting bounds are still satisfiable.
   */
  private def narrowGADTBounds(tr: NamedType, bound: Type, approx: ApproxState, isUpper: Boolean): Boolean = {
    val boundImprecise = approx.high || approx.low
    ctx.mode.is(Mode.GadtConstraintInference) && !frozenGadt && !frozenConstraint && !boundImprecise && {
      val tparam = tr.symbol
      gadts.println(i"narrow gadt bound of $tparam: ${tparam.info} from ${if (isUpper) "above" else "below"} to $bound ${bound.toString} ${bound.isRef(tparam)}")
      if (bound.isRef(tparam)) false
      else if (isUpper) gadtAddUpperBound(tparam, bound)
      else gadtAddLowerBound(tparam, bound)
    }
  }

  // Tests around `matches`

  /** A function implementing `tp1` matches `tp2`. */
  final def matchesType(tp1: Type, tp2: Type, relaxed: Boolean): Boolean = tp1.widen match {
    case tp1: MethodType =>
      tp2.widen match {
        case tp2: MethodType =>
          // implicitness is ignored when matching
          matchingMethodParams(tp1, tp2) &&
          matchesType(tp1.resultType, tp2.resultType.subst(tp2, tp1), relaxed)
        case tp2 =>
          relaxed && tp1.paramNames.isEmpty &&
            matchesType(tp1.resultType, tp2, relaxed)
      }
    case tp1: PolyType =>
      tp2.widen match {
        case tp2: PolyType =>
          sameLength(tp1.paramNames, tp2.paramNames) &&
          matchesType(tp1.resultType, tp2.resultType.subst(tp2, tp1), relaxed)
        case _ =>
          false
      }
    case _ =>
      tp2.widen match {
        case _: PolyType =>
          false
        case tp2: MethodType =>
          relaxed && tp2.paramNames.isEmpty &&
            matchesType(tp1, tp2.resultType, relaxed)
        case tp2 =>
          relaxed || isSameType(tp1, tp2)
      }
  }

  /** Do the parameter types of `tp1` and `tp2` match in a way that allows `tp1`
   *  to override `tp2` ? This is the case if they're pairwise =:=, as a special
   *  case, we allow `Any` in Java methods to match `Object`.
   */
  def matchingMethodParams(tp1: MethodType, tp2: MethodType): Boolean = {
    def loop(formals1: List[Type], formals2: List[Type]): Boolean = formals1 match {
      case formal1 :: rest1 =>
        formals2 match {
          case formal2 :: rest2 =>
            val formal2a = if (tp2.isParamDependent) formal2.subst(tp2, tp1) else formal2
            // The next two definitions handle the special case mentioned above, where
            // the Java argument has type 'Any', and the Scala argument has type 'Object' or
            // 'Object|Null', depending on whether explicit nulls are enabled.
            def formal1IsObject =
              if (ctx.explicitNulls) formal1 match {
                case OrNull(formal1b) => formal1b.isAnyRef
                case _ => false
              }
              else formal1.isAnyRef
            def formal2IsObject =
              if (ctx.explicitNulls) formal2 match {
                case OrNull(formal2b) => formal2b.isAnyRef
                case _ => false
              }
              else formal2.isAnyRef
            (isSameTypeWhenFrozen(formal1, formal2a)
             || tp1.isJavaMethod && formal2IsObject && formal1.isAny
             || tp2.isJavaMethod && formal1IsObject && formal2.isAny
            )
            && loop(rest1, rest2)
          case nil =>
            false
        }
      case nil =>
        formals2.isEmpty
    }
    loop(tp1.paramInfos, tp2.paramInfos)
  }

  /** Do the parameter types of `tp1` and `tp2` match in a way that allows `tp1`
   *  to override `tp2` ? This is the case if they're pairwise >:>.
   */
  def matchingPolyParams(tp1: PolyType, tp2: PolyType): Boolean = {
    def loop(formals1: List[Type], formals2: List[Type]): Boolean = formals1 match {
      case formal1 :: rest1 =>
        formals2 match {
          case formal2 :: rest2 =>
            val formal2a = formal2.subst(tp2, tp1)
            isSubTypeWhenFrozen(formal2a, formal1) &&
            loop(rest1, rest2)
          case nil =>
            false
        }
      case nil =>
        formals2.isEmpty
    }
    loop(tp1.paramInfos, tp2.paramInfos)
  }

  // Type equality =:=

  /** Two types are the same if are mutual subtypes of each other */
  def isSameType(tp1: Type, tp2: Type)(implicit nc: AbsentContext): Boolean =
    if (tp1 eq NoType) false
    else if (tp1 eq tp2) true
    else isSubType(tp1, tp2) && isSubType(tp2, tp1)

  /** Same as `isSameType` but also can be applied to overloaded TermRefs, where
   *  two overloaded refs are the same if they have pairwise equal alternatives
   */
  def isSameRef(tp1: Type, tp2: Type): Boolean = trace(s"isSameRef($tp1, $tp2") {
    def isSubRef(tp1: Type, tp2: Type): Boolean = tp1 match {
      case tp1: TermRef if tp1.isOverloaded =>
        tp1.alternatives forall (isSubRef(_, tp2))
      case _ =>
        tp2 match {
          case tp2: TermRef if tp2.isOverloaded =>
            tp2.alternatives exists (isSubRef(tp1, _))
          case _ =>
            isSubType(tp1, tp2)
        }
    }
    isSubRef(tp1, tp2) && isSubRef(tp2, tp1)
  }

  /** If the range `tp1..tp2` consist of a single type, that type, otherwise NoType`.
   *  This is the case if `tp1 =:= tp2`, but also if `tp1 <:< tp2`, `tp1` is a singleton type,
   *  and `tp2` derives from `scala.Singleton` (or vice-versa). Examples of the latter case:
   *
   *     "name".type .. Singleton
   *     "name".type .. String & Singleton
   *     Singleton .. "name".type
   *     String & Singleton .. "name".type
   *
   *  All consist of the single type `"name".type`.
   */
  def singletonInterval(tp1: Type, tp2: Type): Type = {
    def isSingletonBounds(lo: Type, hi: Type) =
      lo.isSingleton && hi.derivesFrom(defn.SingletonClass) && isSubTypeWhenFrozen(lo, hi)
    if (isSameTypeWhenFrozen(tp1, tp2)) tp1
    else if (isSingletonBounds(tp1, tp2)) tp1
    else if (isSingletonBounds(tp2, tp1)) tp2
    else NoType
  }

  /** The greatest lower bound of two types */
  def glb(tp1: Type, tp2: Type): Type = /*>|>*/ trace(s"glb(${tp1.show}, ${tp2.show})", subtyping, show = true) /*<|<*/ {
    if (tp1 eq tp2) tp1
    else if (!tp1.exists) tp2
    else if (!tp2.exists) tp1
    else if tp1.isAny && !tp2.isLambdaSub || tp1.isAnyKind || tp2.isRef(NothingClass) then tp2
    else if tp2.isAny && !tp1.isLambdaSub || tp2.isAnyKind || tp1.isRef(NothingClass) then tp1
    else tp2 match {  // normalize to disjunctive normal form if possible.
      case tp2: LazyRef =>
        glb(tp1, tp2.ref)
      case OrType(tp21, tp22) =>
        tp1 & tp21 | tp1 & tp22
      case _ =>
        tp1 match {
          case tp1: LazyRef =>
            glb(tp1.ref, tp2)
          case OrType(tp11, tp12) =>
            tp11 & tp2 | tp12 & tp2
          case _ =>
            val tp1a = dropIfSuper(tp1, tp2)
            if (tp1a ne tp1) glb(tp1a, tp2)
            else {
              val tp2a = dropIfSuper(tp2, tp1)
              if (tp2a ne tp2) glb(tp1, tp2a)
              else tp1 match {
                case tp1: ConstantType =>
                  tp2 match {
                    case tp2: ConstantType =>
                      // Make use of the fact that the intersection of two constant types
                      // types which are not subtypes of each other is known to be empty.
                      // Note: The same does not apply to singleton types in general.
                      // E.g. we could have a pattern match against `x.type & y.type`
                      // which might succeed if `x` and `y` happen to be the same ref
                      // at run time. It would not work to replace that with `Nothing`.
                      // However, maybe we can still apply the replacement to
                      // types which are not explicitly written.
                      NothingType
                    case _ => andType(tp1, tp2)
                  }
                case _ => andType(tp1, tp2)
              }
            }
        }
    }
  }

  /** The greatest lower bound of a list types */
  final def glb(tps: List[Type]): Type = tps.foldLeft(AnyType: Type)(glb)

  def widenInUnions(implicit ctx: Context): Boolean =
    migrateTo3 || ctx.erasedTypes

  /** The least upper bound of two types
   *  @param canConstrain  If true, new constraints might be added to simplify the lub.
   *  @note  We do not admit singleton types in or-types as lubs.
   */
  def lub(tp1: Type, tp2: Type, canConstrain: Boolean = false): Type = /*>|>*/ trace(s"lub(${tp1.show}, ${tp2.show}, canConstrain=$canConstrain)", subtyping, show = true) /*<|<*/ {

    if (tp1 eq tp2) tp1
    else if (!tp1.exists) tp1
    else if (!tp2.exists) tp2
    else if tp1.isAny && !tp2.isLambdaSub || tp1.isAnyKind || tp2.isRef(NothingClass) then tp1
    else if tp2.isAny && !tp1.isLambdaSub || tp2.isAnyKind || tp1.isRef(NothingClass) then tp2
    else
      def mergedLub(tp1: Type, tp2: Type): Type = {
        tp1.atoms match
          case Atoms.Range(lo1, hi1) if !widenInUnions =>
            tp2.atoms match
              case Atoms.Range(lo2, hi2) =>
                if hi1.subsetOf(lo2) then return tp2
                if hi2.subsetOf(lo1) then return tp1
                if (hi1 & hi2).isEmpty then return orType(tp1, tp2)
              case none =>
          case none =>
        val t1 = mergeIfSuper(tp1, tp2, canConstrain)
        if (t1.exists) return t1

        val t2 = mergeIfSuper(tp2, tp1, canConstrain)
        if (t2.exists) return t2

        def widen(tp: Type) = if (widenInUnions) tp.widen else tp.widenIfUnstable
        val tp1w = widen(tp1)
        val tp2w = widen(tp2)
        if ((tp1 ne tp1w) || (tp2 ne tp2w)) lub(tp1w, tp2w)
        else orType(tp1w, tp2w) // no need to check subtypes again
      }
      mergedLub(tp1.stripLazyRef, tp2.stripLazyRef)
  }

  /** The least upper bound of a list of types */
  final def lub(tps: List[Type]): Type =
    tps.foldLeft(NothingType: Type)(lub(_,_, canConstrain = false))

  /** Try to produce joint arguments for a lub `A[T_1, ..., T_n] | A[T_1', ..., T_n']` using
   *  the following strategies:
   *
   *    - if arguments are the same, that argument.
   *    - if corresponding parameter variance is co/contra-variant, the lub/glb.
   *    - otherwise a TypeBounds containing both arguments
   */
  def lubArgs(args1: List[Type], args2: List[Type], tparams: List[TypeParamInfo], canConstrain: Boolean = false): List[Type] =
    tparams match {
      case tparam :: tparamsRest =>
        val arg1 :: args1Rest = args1
        val arg2 :: args2Rest = args2
        val common = singletonInterval(arg1, arg2)
        val v = tparam.paramVarianceSign
        val lubArg =
          if (common.exists) common
          else if (v > 0) lub(arg1.hiBound, arg2.hiBound, canConstrain)
          else if (v < 0) glb(arg1.loBound, arg2.loBound)
          else TypeBounds(glb(arg1.loBound, arg2.loBound),
                          lub(arg1.hiBound, arg2.hiBound, canConstrain))
        lubArg :: lubArgs(args1Rest, args2Rest, tparamsRest, canConstrain)
      case nil =>
        Nil
    }

  /** Try to produce joint arguments for a glb `A[T_1, ..., T_n] & A[T_1', ..., T_n']` using
   *  the following strategies:
   *
   *    - if arguments are the same, that argument.
   *    - if corresponding parameter variance is co/contra-variant, the glb/lub.
   *    - if at least one of the arguments if a TypeBounds, the union of
   *      the bounds.
   *    - if homogenizeArgs is set, and arguments can be unified by instantiating
   *      type parameters, the unified argument.
   *    - otherwise NoType
   *
   *  The unification rule is contentious because it cuts the constraint set.
   *  Therefore it is subject to Config option `alignArgsInAnd`.
   */
  def glbArgs(args1: List[Type], args2: List[Type], tparams: List[TypeParamInfo]): List[Type] =
    tparams match {
      case tparam :: tparamsRest =>
        val arg1 :: args1Rest = args1
        val arg2 :: args2Rest = args2
        val common = singletonInterval(arg1, arg2)
        val v = tparam.paramVarianceSign
        val glbArg =
          if (common.exists) common
          else if (v > 0) glb(arg1.hiBound, arg2.hiBound)
          else if (v < 0) lub(arg1.loBound, arg2.loBound)
          else if (isBounds(arg1) || isBounds(arg2))
            TypeBounds(lub(arg1.loBound, arg2.loBound),
                       glb(arg1.hiBound, arg2.hiBound))
          else if (homogenizeArgs && !frozenConstraint && isSameType(arg1, arg2)) arg1
          else NoType
        glbArg :: glbArgs(args1Rest, args2Rest, tparamsRest)
      case nil =>
        Nil
    }

  private def recombineAnd(tp: AndType, tp1: Type, tp2: Type) =
    if (!tp1.exists) tp2
    else if (!tp2.exists) tp1
    else tp.derivedAndType(tp1, tp2)

  /** If some (&-operand of) `tp` is a supertype of `sub` replace it with `NoType`.
   */
  private def dropIfSuper(tp: Type, sub: Type): Type =
    if (isSubTypeWhenFrozen(sub, tp)) NoType
    else tp match {
      case tp @ AndType(tp1, tp2) =>
        recombineAnd(tp, dropIfSuper(tp1, sub), dropIfSuper(tp2, sub))
      case _ =>
        tp
    }

  /** Merge `t1` into `tp2` if t1 is a subtype of some &-summand of tp2.
   */
  private def mergeIfSub(tp1: Type, tp2: Type): Type =
    if (isSubTypeWhenFrozen(tp1, tp2))
      if (isSubTypeWhenFrozen(tp2, tp1)) tp2 else tp1 // keep existing type if possible
    else tp2 match {
      case tp2 @ AndType(tp21, tp22) =>
        val lower1 = mergeIfSub(tp1, tp21)
        if (lower1 eq tp21) tp2
        else if (lower1.exists) lower1 & tp22
        else {
          val lower2 = mergeIfSub(tp1, tp22)
          if (lower2 eq tp22) tp2
          else if (lower2.exists) tp21 & lower2
          else NoType
        }
      case _ =>
        NoType
    }

  /** Merge `tp1` into `tp2` if tp1 is a supertype of some |-summand of tp2.
   *  @param canConstrain  If true, new constraints might be added to make the merge possible.
   */
  private def mergeIfSuper(tp1: Type, tp2: Type, canConstrain: Boolean): Type =
    if (isSubType(tp2, tp1, whenFrozen = !canConstrain))
      if (isSubType(tp1, tp2, whenFrozen = !canConstrain)) tp2 else tp1 // keep existing type if possible
    else tp2 match {
      case tp2 @ OrType(tp21, tp22) =>
        val higher1 = mergeIfSuper(tp1, tp21, canConstrain)
        if (higher1 eq tp21) tp2
        else if (higher1.exists) higher1 | tp22
        else {
          val higher2 = mergeIfSuper(tp1, tp22, canConstrain)
          if (higher2 eq tp22) tp2
          else if (higher2.exists) tp21 | higher2
          else NoType
        }
      case _ =>
        NoType
    }

  private def andTypeGen(tp1: Type, tp2: Type, op: (Type, Type) => Type,
      original: (Type, Type) => Type = _ & _, isErased: Boolean = ctx.erasedTypes): Type = trace(s"glb(${tp1.show}, ${tp2.show})", subtyping, show = true) {
    val t1 = distributeAnd(tp1, tp2)
    if (t1.exists) t1
    else {
      val t2 = distributeAnd(tp2, tp1)
      if (t2.exists) t2
      else if (isErased) erasedGlb(tp1, tp2, isJava = false)
      else liftIfHK(tp1, tp2, op, original, _ | _)
        // The ` | ` on variances is needed since variances are associated with bounds
        // not lambdas. Example:
        //
        //    trait A { def F[-X] }
        //    trait B { def F[+X] }
        //    object O extends A, B { ... }
        //
        // Here, `F` is treated as bivariant in `O`. That is, only bivariant implementation
        // of `F` are allowed. See neg/hk-variance2s.scala test.
    }
  }

  /** Form a normalized conjunction of two types.
   *  Note: For certain types, `&` is distributed inside the type. This holds for
   *  all types which are not value types (e.g. TypeBounds, ClassInfo,
   *  ExprType, LambdaType). Also, when forming an `&`,
   *  instantiated TypeVars are dereferenced and annotations are stripped.
   *  Finally, refined types with the same refined name are
   *  opportunistically merged.
   *
   *  Sometimes, the conjunction of two types cannot be formed because
   *  the types are in conflict of each other. In particular:
   *
   *    1. Two different class types are conflicting.
   *    2. A class type conflicts with a type bounds that does not include the class reference.
   *    3. Two method or poly types with different (type) parameters but the same
   *       signature are conflicting
   *
   *  In these cases, a MergeError is thrown.
   */
  final def andType(tp1: Type, tp2: Type, isErased: Boolean = ctx.erasedTypes): Type =
    andTypeGen(tp1, tp2, AndType(_, _), isErased = isErased)

  final def simplifyAndTypeWithFallback(tp1: Type, tp2: Type, fallback: Type): Type =
    andTypeGen(tp1, tp2, (_, _) => fallback)

  /** Form a normalized conjunction of two types.
   *  Note: For certain types, `|` is distributed inside the type. This holds for
   *  all types which are not value types (e.g. TypeBounds, ClassInfo,
   *  ExprType, LambdaType). Also, when forming an `|`,
   *  instantiated TypeVars are dereferenced and annotations are stripped.
   *
   *  Sometimes, the disjunction of two types cannot be formed because
   *  the types are in conflict of each other. (@see `andType` for an enumeration
   *  of these cases). In cases of conflict a `MergeError` is raised.
   *
   *  @param isErased Apply erasure semantics. If erased is true, instead of creating
   *                  an OrType, the lub will be computed using TypeCreator#erasedLub.
   */
  final def orType(tp1: Type, tp2: Type, isErased: Boolean = ctx.erasedTypes): Type = {
    val t1 = distributeOr(tp1, tp2)
    if (t1.exists) t1
    else {
      val t2 = distributeOr(tp2, tp1)
      if (t2.exists) t2
      else if (isErased) erasedLub(tp1, tp2)
      else liftIfHK(tp1, tp2, OrType(_, _), _ | _, _ & _)
    }
  }

  /** `op(tp1, tp2)` unless `tp1` and `tp2` are type-constructors.
   *  In the latter case, combine `tp1` and `tp2` under a type lambda like this:
   *
   *    [X1, ..., Xn] -> op(tp1[X1, ..., Xn], tp2[X1, ..., Xn])
   */
  private def liftIfHK(tp1: Type, tp2: Type,
      op: (Type, Type) => Type, original: (Type, Type) => Type, combineVariance: (Variance, Variance) => Variance) = {
    val tparams1 = tp1.typeParams
    val tparams2 = tp2.typeParams
    def applied(tp: Type) = tp.appliedTo(tp.typeParams.map(_.paramInfoAsSeenFrom(tp)))
    if (tparams1.isEmpty)
      if (tparams2.isEmpty) op(tp1, tp2)
      else original(tp1, applied(tp2))
    else if (tparams2.isEmpty)
      original(applied(tp1), tp2)
    else if (tparams1.hasSameLengthAs(tparams2))
      HKTypeLambda(
        paramNames = HKTypeLambda.syntheticParamNames(tparams1.length),
        variances =
          if tp1.isDeclaredVarianceLambda && tp2.isDeclaredVarianceLambda then
            tparams1.lazyZip(tparams2).map((p1, p2) => combineVariance(p1.paramVariance, p2.paramVariance))
          else Nil
      )(
        paramInfosExp = tl => tparams1.lazyZip(tparams2).map((tparam1, tparam2) =>
          tl.integrate(tparams1, tparam1.paramInfoAsSeenFrom(tp1)).bounds &
          tl.integrate(tparams2, tparam2.paramInfoAsSeenFrom(tp2)).bounds),
        resultTypeExp = tl =>
          original(tp1.appliedTo(tl.paramRefs), tp2.appliedTo(tl.paramRefs)))
    else original(applied(tp1), applied(tp2))
  }

  /** Try to distribute `&` inside type, detect and handle conflicts
   *  @pre !(tp1 <: tp2) && !(tp2 <:< tp1) -- these cases were handled before
   */
  private def distributeAnd(tp1: Type, tp2: Type): Type = tp1 match {
    case tp1 @ AppliedType(tycon1, args1) =>
      tp2 match {
        case AppliedType(tycon2, args2)
        if tycon1.typeSymbol == tycon2.typeSymbol && tycon1 =:= tycon2 =>
          val jointArgs = glbArgs(args1, args2, tycon1.typeParams)
          if (jointArgs.forall(_.exists)) (tycon1 & tycon2).appliedTo(jointArgs)
          else NoType
        case _ =>
          NoType
      }
    case tp1: RefinedType =>
      // opportunistically merge same-named refinements
      // this does not change anything semantically (i.e. merging or not merging
      // gives =:= types), but it keeps the type smaller.
      tp2 match {
        case tp2: RefinedType if tp1.refinedName == tp2.refinedName =>
          try {
            val jointInfo = Denotations.infoMeet(tp1.refinedInfo, tp2.refinedInfo, NoSymbol, NoSymbol, safeIntersection = false)
            tp1.derivedRefinedType(tp1.parent & tp2.parent, tp1.refinedName, jointInfo)
          }
          catch {
            case ex: MergeError => NoType
          }
        case _ =>
          NoType
      }
    case tp1: RecType =>
      tp1.rebind(distributeAnd(tp1.parent, tp2))
    case ExprType(rt1) =>
      tp2 match {
        case ExprType(rt2) =>
          ExprType(rt1 & rt2)
        case _ =>
          NoType
      }
    case tp1: TypeVar if tp1.isInstantiated =>
      tp1.underlying & tp2
    case tp1: AnnotatedType if !tp1.isRefining =>
      tp1.underlying & tp2
    case _ =>
      NoType
  }

  /** Try to distribute `|` inside type, detect and handle conflicts
   *  Note that, unlike for `&`, a disjunction cannot be pushed into
   *  a refined or applied type. Example:
   *
   *     List[T] | List[U] is not the same as List[T | U].
   *
   *  The rhs is a proper supertype of the lhs.
   */
  private def distributeOr(tp1: Type, tp2: Type): Type = tp1 match {
    case ExprType(rt1) =>
      tp2 match {
        case ExprType(rt2) =>
          ExprType(rt1 | rt2)
        case _ =>
          NoType
      }
    case tp1: TypeVar if tp1.isInstantiated =>
      tp1.underlying | tp2
    case tp1: AnnotatedType if !tp1.isRefining =>
      tp1.underlying | tp2
    case _ =>
      NoType
  }

  /** Show type, handling type types better than the default */
  private def showType(tp: Type)(implicit ctx: Context) = tp match {
    case ClassInfo(_, cls, _, _, _) => cls.showLocated
    case bounds: TypeBounds => "type bounds" + bounds.show
    case _ => tp.show
  }

  /** A comparison function to pick a winner in case of a merge conflict */
  private def isAsGood(tp1: Type, tp2: Type): Boolean = tp1 match {
    case tp1: ClassInfo =>
      tp2 match {
        case tp2: ClassInfo =>
          isSubTypeWhenFrozen(tp1.prefix, tp2.prefix) || (tp1.cls.owner derivesFrom tp2.cls.owner)
        case _ =>
          false
      }
    case tp1: PolyType =>
      tp2 match {
        case tp2: PolyType =>
          tp1.typeParams.length == tp2.typeParams.length &&
          isAsGood(tp1.resultType, tp2.resultType.subst(tp2, tp1))
        case _ =>
          false
      }
    case tp1: MethodType =>
      tp2 match {
        case tp2: MethodType =>
          def asGoodParams(formals1: List[Type], formals2: List[Type]) =
            (formals2 corresponds formals1)(isSubTypeWhenFrozen)
          asGoodParams(tp1.paramInfos, tp2.paramInfos) &&
          (!asGoodParams(tp2.paramInfos, tp1.paramInfos) ||
           isAsGood(tp1.resultType, tp2.resultType))
        case _ =>
          false
      }
    case _ =>
      false
  }

  /** A new type comparer of the same type as this one, using the given context. */
  def copyIn(ctx: Context): TypeComparer = new TypeComparer(ctx)

  // ----------- Diagnostics --------------------------------------------------

  /** A hook for showing subtype traces. Overridden in ExplainingTypeComparer */
  def traceIndented[T](str: String)(op: => T): T = op

  private def traceInfo(tp1: Type, tp2: Type) =
    s"${tp1.show} <:< ${tp2.show}" + {
      if (ctx.settings.verbose.value || Config.verboseExplainSubtype)
        s" ${tp1.getClass}, ${tp2.getClass}" +
        (if (frozenConstraint) " frozen" else "") +
        (if (ctx.mode is Mode.TypevarsMissContext) " tvars-miss-ctx" else "")
      else ""
    }

  /** Show subtype goal that led to an assertion failure */
  def showGoal(tp1: Type, tp2: Type)(implicit ctx: Context): Unit = {
    ctx.echo(i"assertion failure for ${show(tp1)} <:< ${show(tp2)}, frozen = $frozenConstraint")
    def explainPoly(tp: Type) = tp match {
      case tp: TypeParamRef => ctx.echo(s"TypeParamRef ${tp.show} found in ${tp.binder.show}")
      case tp: TypeRef if tp.symbol.exists => ctx.echo(s"typeref ${tp.show} found in ${tp.symbol.owner.show}")
      case tp: TypeVar => ctx.echo(s"typevar ${tp.show}, origin = ${tp.origin}")
      case _ => ctx.echo(s"${tp.show} is a ${tp.getClass}")
    }
    if (Config.verboseExplainSubtype) {
      explainPoly(tp1)
      explainPoly(tp2)
    }
  }

  /** Record statistics about the total number of subtype checks
   *  and the number of "successful" subtype checks, i.e. checks
   *  that form part of a subtype derivation tree that's ultimately successful.
   */
  def recordStatistics(result: Boolean, prevSuccessCount: Int): Unit = {
    // Stats.record(s"isSubType ${tp1.show} <:< ${tp2.show}")
    totalCount += 1
    if (result) successCount += 1 else successCount = prevSuccessCount
    if (recCount == 0) {
      Stats.record("successful subType", successCount)
      Stats.record("total subType", totalCount)
      successCount = 0
      totalCount = 0
    }
  }

  /** Returns last check's debug mode, if explicitly enabled. */
  def lastTrace(): String = ""

  /** Does `tycon` have a field with type `tparam`? Special cased for `scala.*:`
   *  as that type is artificially added to tuples. */
  private def typeparamCorrespondsToField(tycon: Type, tparam: TypeParamInfo): Boolean =
    productSelectorTypes(tycon, null).exists {
      case tp: TypeRef =>
        tp.designator.eq(tparam) // Bingo!
      case _ =>
        false
    } || tycon.derivesFrom(defn.PairClass)

  /** Is `tp` an empty type?
   *
   *  `true` implies that we found a proof; uncertainty defaults to `false`.
   */
  def provablyEmpty(tp: Type): Boolean =
    tp.dealias match {
      case tp if tp.isBottomType => true
      case AndType(tp1, tp2) => provablyDisjoint(tp1, tp2)
      case OrType(tp1, tp2) => provablyEmpty(tp1) && provablyEmpty(tp2)
      case at @ AppliedType(tycon, args) =>
        args.lazyZip(tycon.typeParams).exists { (arg, tparam) =>
          tparam.paramVarianceSign >= 0
          && provablyEmpty(arg)
          && typeparamCorrespondsToField(tycon, tparam)
        }
      case tp: TypeProxy =>
        provablyEmpty(tp.underlying)
      case _ => false
    }


  /** Are `tp1` and `tp2` provablyDisjoint types?
   *
   *  `true` implies that we found a proof; uncertainty defaults to `false`.
   *
   *  Proofs rely on the following properties of Scala types:
   *
   *  1. Single inheritance of classes
   *  2. Final classes cannot be extended
   *  3. ConstantTypes with distinct values are non intersecting
   *  4. There is no value of type Nothing
   *
   *  Note on soundness: the correctness of match types relies on on the
   *  property that in all possible contexts, the same match type expression
   *  is either stuck or reduces to the same case.
   */
  def provablyDisjoint(tp1: Type, tp2: Type)(implicit ctx: Context): Boolean = {
    // println(s"provablyDisjoint(${tp1.show}, ${tp2.show})")
    /** Can we enumerate all instantiations of this type? */
    def isClosedSum(tp: Symbol): Boolean =
      tp.is(Sealed) && tp.isOneOf(AbstractOrTrait) && !tp.hasAnonymousChild

    /** Splits a closed type into a disjunction of smaller types.
     *  It should hold that `tp` and `decompose(tp).reduce(_ or _)`
     *  denote the same set of values.
     */
    def decompose(sym: Symbol, tp: Type): List[Type] =
      sym.children.map(x => ctx.refineUsingParent(tp, x)).filter(_.exists)

    (tp1.dealias, tp2.dealias) match {
      case (tp1: TypeRef, tp2: TypeRef) if tp1.symbol == defn.SingletonClass || tp2.symbol == defn.SingletonClass =>
        false
      case (tp1: ConstantType, tp2: ConstantType) =>
        tp1 != tp2
      case (tp1: TypeRef, tp2: TypeRef) if tp1.symbol.isClass && tp2.symbol.isClass =>
        val cls1 = tp1.classSymbol
        val cls2 = tp2.classSymbol
        if (cls1.derivesFrom(cls2) || cls2.derivesFrom(cls1))
          false
        else
          if (cls1.is(Final) || cls2.is(Final))
            // One of these types is final and they are not mutually
            // subtype, so they must be unrelated.
            true
          else if (!cls2.is(Trait) && !cls1.is(Trait))
            // Both of these types are classes and they are not mutually
            // subtype, so they must be unrelated by single inheritance
            // of classes.
            true
          else if (isClosedSum(cls1))
            decompose(cls1, tp1).forall(x => provablyDisjoint(x, tp2))
          else if (isClosedSum(cls2))
            decompose(cls2, tp2).forall(x => provablyDisjoint(x, tp1))
          else
            false
      case (AppliedType(tycon1, args1), AppliedType(tycon2, args2)) if tycon1 == tycon2 =>
        // It is possible to conclude that two types applies are disjoint by
        // looking at covariant type parameters if the said type parameters
        // are disjoin and correspond to fields.
        // (Type parameter disjointness is not enough by itself as it could
        // lead to incorrect conclusions for phantom type parameters).
        def covariantDisjoint(tp1: Type, tp2: Type, tparam: TypeParamInfo): Boolean =
          provablyDisjoint(tp1, tp2) && typeparamCorrespondsToField(tycon1, tparam)

        args1.lazyZip(args2).lazyZip(tycon1.typeParams).exists {
          (arg1, arg2, tparam) =>
            val v = tparam.paramVarianceSign
            if (v > 0)
              covariantDisjoint(arg1, arg2, tparam)
            else if (v < 0)
              // Contravariant case: a value where this type parameter is
              // instantiated to `Any` belongs to both types.
              false
            else
              covariantDisjoint(arg1, arg2, tparam) || !isSameType(arg1, arg2) && {
                // We can only trust a "no" from `isSameType` when both
                // `arg1` and `arg2` are fully instantiated.
                def fullyInstantiated(tp: Type): Boolean = new TypeAccumulator[Boolean] {
                  override def apply(x: Boolean, t: Type) =
                    x && {
                      t match {
                        case tp: TypeRef if tp.symbol.isAbstractOrParamType => false
                        case _: SkolemType | _: TypeVar | _: TypeParamRef => false
                        case _ => foldOver(x, t)
                      }
                    }
                }.apply(true, tp)
                fullyInstantiated(arg1) && fullyInstantiated(arg2)
              }
        }
      case (tp1: HKLambda, tp2: HKLambda) =>
        provablyDisjoint(tp1.resType, tp2.resType)
      case (_: HKLambda, _) =>
        // The intersection of these two types would be ill kinded, they are therefore provablyDisjoint.
        true
      case (_, _: HKLambda) =>
        true
      case (tp1: OrType, _)  =>
        provablyDisjoint(tp1.tp1, tp2) && provablyDisjoint(tp1.tp2, tp2)
      case (_, tp2: OrType)  =>
        provablyDisjoint(tp1, tp2.tp1) && provablyDisjoint(tp1, tp2.tp2)
      case (tp1: AndType, tp2: AndType) =>
        (provablyDisjoint(tp1.tp1, tp2.tp1) || provablyDisjoint(tp1.tp2, tp2.tp2)) &&
        (provablyDisjoint(tp1.tp1, tp2.tp2) || provablyDisjoint(tp1.tp2, tp2.tp1))
      case (tp1: AndType, _) =>
        provablyDisjoint(tp1.tp2, tp2) || provablyDisjoint(tp1.tp1, tp2)
      case (_, tp2: AndType) =>
        provablyDisjoint(tp1, tp2.tp2) || provablyDisjoint(tp1, tp2.tp1)
      case (tp1: TypeProxy, tp2: TypeProxy) =>
        provablyDisjoint(tp1.underlying, tp2) || provablyDisjoint(tp1, tp2.underlying)
      case (tp1: TypeProxy, _) =>
        provablyDisjoint(tp1.underlying, tp2)
      case (_, tp2: TypeProxy) =>
        provablyDisjoint(tp1, tp2.underlying)
      case _ =>
        false
    }
  }
}

object TypeComparer {

  /** Class for unification variables used in `natValue`. */
  private class AnyConstantType extends UncachedGroundType with ValueType {
    var tpe: Type = NoType
  }

  private[core] def show(res: Any)(implicit ctx: Context): String = res match {
    case res: printing.Showable if !ctx.settings.YexplainLowlevel.value => res.show
    case _ => String.valueOf(res)
  }

  private val LoApprox = 1
  private val HiApprox = 2

  /** The approximation state indicates how the pair of types currently compared
   *  relates to the types compared originally.
   *   - `NoApprox`: They are still the same types
   *   - `LoApprox`: The left type is approximated (i.e widened)"
   *   - `HiApprox`: The right type is approximated (i.e narrowed)"
   */
  class ApproxState(private val bits: Int) extends AnyVal {
    override def toString: String = {
      val lo = if ((bits & LoApprox) != 0) "LoApprox" else ""
      val hi = if ((bits & HiApprox) != 0) "HiApprox" else ""
      lo ++ hi
    }
    def addLow: ApproxState = new ApproxState(bits | LoApprox)
    def addHigh: ApproxState = new ApproxState(bits | HiApprox)
    def low: Boolean = (bits & LoApprox) != 0
    def high: Boolean = (bits & HiApprox) != 0
  }

  val NoApprox: ApproxState = new ApproxState(0)

  /** A special approximation state to indicate that this is the first time we
   *  compare (approximations of) this pair of types. It's converted to `NoApprox`
   *  in `isSubType`, but also leads to `leftRoot` being set there.
   */
  val FreshApprox: ApproxState = new ApproxState(4)

  /** Show trace of comparison operations when performing `op` */
  def explaining[T](say: String => Unit)(op: Context ?=> T)(implicit ctx: Context): T = {
    val nestedCtx = ctx.fresh.setTypeComparerFn(new ExplainingTypeComparer(_))
    val res = try { op(using nestedCtx) } finally { say(nestedCtx.typeComparer.lastTrace()) }
    res
  }

  /** Like [[explaining]], but returns the trace instead */
  def explained[T](op: Context ?=> T)(implicit ctx: Context): String = {
    var trace: String = null
    try { explaining(trace = _)(op) } catch { case ex: Throwable => ex.printStackTrace }
    trace
  }
}

class TrackingTypeComparer(initctx: Context) extends TypeComparer(initctx) {
  import state.constraint

  val footprint: mutable.Set[Type] = mutable.Set[Type]()

  override def bounds(param: TypeParamRef)(implicit nc: AbsentContext): TypeBounds = {
    if (param.binder `ne` caseLambda) footprint += param
    super.bounds(param)
  }

  override def addOneBound(param: TypeParamRef, bound: Type, isUpper: Boolean)(implicit nc: AbsentContext): Boolean = {
    if (param.binder `ne` caseLambda) footprint += param
    super.addOneBound(param, bound, isUpper)
  }

  override def gadtBounds(sym: Symbol)(implicit ctx: Context): TypeBounds = {
    if (sym.exists) footprint += sym.typeRef
    super.gadtBounds(sym)
  }

  override def gadtAddLowerBound(sym: Symbol, b: Type): Boolean = {
    if (sym.exists) footprint += sym.typeRef
    super.gadtAddLowerBound(sym, b)
  }

  override def gadtAddUpperBound(sym: Symbol, b: Type): Boolean = {
    if (sym.exists) footprint += sym.typeRef
    super.gadtAddUpperBound(sym, b)
  }

  override def typeVarInstance(tvar: TypeVar)(implicit ctx: Context): Type = {
    footprint += tvar
    super.typeVarInstance(tvar)
  }

  def matchCases(scrut: Type, cases: List[Type])(implicit ctx: Context): Type = {
    def paramInstances = new TypeAccumulator[Array[Type]] {
      def apply(inst: Array[Type], t: Type) = t match {
        case t @ TypeParamRef(b, n) if b `eq` caseLambda =>
          inst(n) = approximation(t, fromBelow = variance >= 0).simplified
          inst
        case _ =>
          foldOver(inst, t)
      }
    }

    def instantiateParams(inst: Array[Type]) = new TypeMap {
      def apply(t: Type) = t match {
        case t @ TypeParamRef(b, n) if b `eq` caseLambda => inst(n)
        case t: LazyRef => apply(t.ref)
        case _ => mapOver(t)
      }
    }

    /** Match a single case.
     *  @return  Some(tp)     if the match succeeds with type `tp`
     *           Some(NoType) if the match fails, and there is an overlap between pattern and scrutinee
     *           None         if the match fails and we should consider the following cases
     *                        because scrutinee and pattern do not overlap
     */
    def matchCase(cas: Type): Option[Type] = {
      val cas1 = cas match {
        case cas: HKTypeLambda =>
          caseLambda = constrained(cas)
          caseLambda.resultType
        case _ =>
          cas
      }
      def widenAbstractTypes(tp: Type): Type = new TypeMap {
        var seen = Set[TypeParamRef]()
        def apply(tp: Type) = tp match {
          case tp: TypeRef =>
            tp.info match {
              case info: MatchAlias =>
                mapOver(tp)
                  // TODO: We should follow the alias in this case, but doing so
                  // risks infinite recursion
              case TypeBounds(lo, hi) =>
                if (hi frozen_<:< lo) {
                  val alias = apply(lo)
                  if (alias ne lo) alias else mapOver(tp)
                }
                else WildcardType
              case _ =>
                mapOver(tp)
            }
          case tp: TypeLambda =>
            val saved = seen
            seen ++= tp.paramRefs
            try mapOver(tp)
            finally seen = saved
          case tp: TypeVar if !tp.isInstantiated => WildcardType
          case tp: TypeParamRef if !seen.contains(tp) => WildcardType
          case _ => mapOver(tp)
        }
      }.apply(tp)

      val defn.MatchCase(pat, body) = cas1

      if (isSubType(scrut, pat))
        // `scrut` is a subtype of `pat`: *It's a Match!*
        Some {
          caseLambda match {
            case caseLambda: HKTypeLambda =>
              val instances = paramInstances(new Array(caseLambda.paramNames.length), pat)
              instantiateParams(instances)(body)
            case _ =>
              body
          }
        }
      else if (isSubType(widenAbstractTypes(scrut), widenAbstractTypes(pat)))
        Some(NoType)
      else if (provablyDisjoint(scrut, pat))
        // We found a proof that `scrut` and `pat` are incompatible.
        // The search continues.
        None
      else
        Some(NoType)
    }

    def recur(cases: List[Type]): Type = cases match {
      case cas :: cases1 => matchCase(cas).getOrElse(recur(cases1))
      case Nil => NoType
    }

    inFrozenConstraint {
      // Empty types break the basic assumption that if a scrutinee and a
      // pattern are disjoint it's OK to reduce passed that pattern. Indeed,
      // empty types viewed as a set of value is always a subset of any other
      // types. As a result, we first check that the scrutinee isn't empty
      // before proceeding with reduction. See `tests/neg/6570.scala` and
      // `6570-1.scala` for examples that exploit emptiness to break match
      // type soundness.

      // If we revered the uncertainty case of this empty check, that is,
      // `!provablyNonEmpty` instead of `provablyEmpty`, that would be
      // obviously sound, but quite restrictive. With the current formulation,
      // we need to be careful that `provablyEmpty` covers all the conditions
      // used to conclude disjointness in `provablyDisjoint`.
      if (provablyEmpty(scrut))
        NoType
      else
        recur(cases)
    }
  }
}

/** A type comparer that can record traces of subtype operations */
class ExplainingTypeComparer(initctx: Context) extends TypeComparer(initctx) {
  import TypeComparer._

  private var indent = 0
  private val b = new StringBuilder

  private var skipped = false

  override def traceIndented[T](str: String)(op: => T): T =
    if (skipped) op
    else {
      indent += 2
      b.append("\n").append(" " * indent).append("==> ").append(str)
      val res = op
      b.append("\n").append(" " * indent).append("<== ").append(str).append(" = ").append(show(res))
      indent -= 2
      res
    }

  override def isSubType(tp1: Type, tp2: Type, approx: ApproxState): Boolean =
    def moreInfo =
      if Config.verboseExplainSubtype || ctx.settings.verbose.value
      then s" ${tp1.getClass} ${tp2.getClass}"
      else ""
    traceIndented(s"${show(tp1)} <:< ${show(tp2)}$moreInfo $approx ${if (frozenConstraint) " frozen" else ""}") {
      super.isSubType(tp1, tp2, approx)
    }

  override def recur(tp1: Type, tp2: Type): Boolean =
    traceIndented(s"${show(tp1)} <:< ${show(tp2)} recur ${if (frozenConstraint) " frozen" else ""}") {
      super.recur(tp1, tp2)
    }

  override def hasMatchingMember(name: Name, tp1: Type, tp2: RefinedType): Boolean =
    traceIndented(s"hasMatchingMember(${show(tp1)} . $name, ${show(tp2.refinedInfo)}), member = ${show(tp1.member(name).info)}") {
      super.hasMatchingMember(name, tp1, tp2)
    }

  override def lub(tp1: Type, tp2: Type, canConstrain: Boolean = false): Type =
    traceIndented(s"lub(${show(tp1)}, ${show(tp2)}, canConstrain=$canConstrain)") {
      super.lub(tp1, tp2, canConstrain)
    }

  override def glb(tp1: Type, tp2: Type): Type =
    traceIndented(s"glb(${show(tp1)}, ${show(tp2)})") {
      super.glb(tp1, tp2)
    }

  override def addConstraint(param: TypeParamRef, bound: Type, fromBelow: Boolean)(implicit nc: AbsentContext): Boolean =
    traceIndented(i"add constraint $param ${if (fromBelow) ">:" else "<:"} $bound $frozenConstraint, constraint = ${ctx.typerState.constraint}") {
      super.addConstraint(param, bound, fromBelow)
    }

  override def copyIn(ctx: Context): ExplainingTypeComparer = new ExplainingTypeComparer(ctx)

  override def lastTrace(): String = "Subtype trace:" + { try b.toString finally b.clear() }
}
