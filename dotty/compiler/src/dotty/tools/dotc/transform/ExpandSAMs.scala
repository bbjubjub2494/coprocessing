package dotty.tools.dotc
package transform

import core._
import Contexts._, Symbols._, Types._, Flags._, Decorators._, StdNames._, Constants._
import MegaPhase._
import SymUtils._
import ast.Trees._
import dotty.tools.dotc.reporting.messages.TypeMismatch
import dotty.tools.dotc.util.Spans.Span

/** Expand SAM closures that cannot be represented by the JVM as lambdas to anonymous classes.
 *  These fall into five categories
 *
 *   1. Partial function closures, we need to generate isDefinedAt and applyOrElse methods for these.
 *   2. Closures implementing non-trait classes
 *   3. Closures implementing classes that inherit from a class other than Object
 *      (a lambda cannot not be a run-time subtype of such a class)
 *   4. Closures that implement traits which run initialization code.
 *   5. Closures that get synthesized abstract methods in the transformation pipeline. These methods can be
 *      (1) superaccessors, (2) outer references, (3) accessors for fields.
 *
 *  However, implicit function types do not count as SAM types.
 */
class ExpandSAMs extends MiniPhase {
  override def phaseName: String = "expandSAMs"

  import ast.tpd._

  /** Is the SAMType `cls` also a SAM under the rules of the platform? */
  def isPlatformSam(cls: ClassSymbol)(implicit ctx: Context): Boolean =
    ctx.platform.isSam(cls)

  override def transformBlock(tree: Block)(implicit ctx: Context): Tree = tree match {
    case Block(stats @ (fn: DefDef) :: Nil, Closure(_, fnRef, tpt)) if fnRef.symbol == fn.symbol =>
      tpt.tpe match {
        case NoType =>
          tree // it's a plain function
        case tpe if defn.isContextFunctionType(tpe) =>
          tree
        case tpe @ SAMType(_) if tpe.isRef(defn.PartialFunctionClass) =>
          val tpe1 = checkRefinements(tpe, fn)
          toPartialFunction(tree, tpe1)
        case tpe @ SAMType(_) if isPlatformSam(tpe.classSymbol.asClass) =>
          checkRefinements(tpe, fn)
          tree
        case tpe =>
          val tpe1 = checkRefinements(tpe, fn)
          val Seq(samDenot) = tpe1.possibleSamMethods
          cpy.Block(tree)(stats,
              AnonClass(tpe1 :: Nil, fn.symbol.asTerm :: Nil, samDenot.symbol.asTerm.name :: Nil))
      }
    case _ =>
      tree
  }

  /** A partial function literal:
   *
   *  ```
   *  val x: PartialFunction[A, B] = { case C1 => E1; ...; case Cn => En }
   *  ```
   *
   *  which desugars to:
   *
   *  ```
   *  val x: PartialFunction[A, B] = {
   *    def $anonfun(x: A): B = x match { case C1 => E1; ...; case Cn => En }
   *    closure($anonfun: PartialFunction[A, B])
   *  }
   *  ```
   *
   *  is expanded to an anomymous class:
   *
   *  ```
   *  val x: PartialFunction[A, B] = {
   *    class $anon extends AbstractPartialFunction[A, B] {
   *      final def isDefinedAt(x: A): Boolean = x match {
   *        case C1 => true
   *        ...
   *        case Cn => true
   *        case _  => false
   *      }
   *
   *      final def applyOrElse[A1 <: A, B1 >: B](x: A1, default: A1 => B1): B1 = x match {
   *        case C1 => E1
   *        ...
   *        case Cn => En
   *        case _  => default(x)
   *      }
   *    }
   *
   *    new $anon
   *  }
   *  ```
   */
  private def toPartialFunction(tree: Block, tpe: Type)(implicit ctx: Context): Tree = {
    /** An extractor for match, either contained in a block or standalone. */
    object PartialFunctionRHS {
      def unapply(tree: Tree): Option[Match] = tree match {
        case Block(Nil, expr) => unapply(expr)
        case m: Match => Some(m)
        case _ => None
      }
    }

    val closureDef(anon @ DefDef(_, _, List(List(param)), _, _)) = tree
    anon.rhs match {
      case PartialFunctionRHS(pf) =>
        val anonSym = anon.symbol
        val anonTpe = anon.tpe.widen
        val parents = List(
          defn.AbstractPartialFunctionClass.typeRef.appliedTo(anonTpe.firstParamTypes.head, anonTpe.resultType),
          defn.SerializableType)
        val pfSym = ctx.newNormalizedClassSymbol(anonSym.owner, tpnme.ANON_CLASS, Synthetic | Final, parents, coord = tree.span)

        def overrideSym(sym: Symbol) = sym.copy(
          owner = pfSym,
          flags = Synthetic | Method | Final | Override,
          info = tpe.memberInfo(sym),
          coord = tree.span).asTerm.entered
        val isDefinedAtFn = overrideSym(defn.PartialFunction_isDefinedAt)
        val applyOrElseFn = overrideSym(defn.PartialFunction_applyOrElse)

        def translateMatch(tree: Match, pfParam: Symbol, cases: List[CaseDef], defaultValue: Tree)(implicit ctx: Context) = {
          val selector = tree.selector
          val selectorTpe = selector.tpe.widen
          val defaultSym = ctx.newSymbol(pfParam.owner, nme.WILDCARD, Synthetic | Case, selectorTpe)
          val defaultCase =
            CaseDef(
              Bind(defaultSym, Underscore(selectorTpe)),
              EmptyTree,
              defaultValue)
          val unchecked = selector.annotated(New(ref(defn.UncheckedAnnot.typeRef)))
          cpy.Match(tree)(unchecked, cases :+ defaultCase)
            .subst(param.symbol :: Nil, pfParam :: Nil)
              // Needed because  a partial function can be written as:
              // param => param match { case "foo" if foo(param) => param }
              // And we need to update all references to 'param'
        }

        def isDefinedAtRhs(paramRefss: List[List[Tree]])(implicit ctx: Context) = {
          val tru = Literal(Constant(true))
          def translateCase(cdef: CaseDef) =
            cpy.CaseDef(cdef)(body = tru).changeOwner(anonSym, isDefinedAtFn)
          val paramRef = paramRefss.head.head
          val defaultValue = Literal(Constant(false))
          translateMatch(pf, paramRef.symbol, pf.cases.map(translateCase), defaultValue)
        }

        def applyOrElseRhs(paramRefss: List[List[Tree]])(implicit ctx: Context) = {
          val List(paramRef, defaultRef) = paramRefss.head
          def translateCase(cdef: CaseDef) =
            cdef.changeOwner(anonSym, applyOrElseFn)
          val defaultValue = defaultRef.select(nme.apply).appliedTo(paramRef)
          translateMatch(pf, paramRef.symbol, pf.cases.map(translateCase), defaultValue)
        }

        val constr = ctx.newConstructor(pfSym, Synthetic, Nil, Nil).entered
        val isDefinedAtDef = transformFollowingDeep(DefDef(isDefinedAtFn, isDefinedAtRhs(_)(ctx.withOwner(isDefinedAtFn))))
        val applyOrElseDef = transformFollowingDeep(DefDef(applyOrElseFn, applyOrElseRhs(_)(ctx.withOwner(applyOrElseFn))))
        val pfDef = ClassDef(pfSym, DefDef(constr), List(isDefinedAtDef, applyOrElseDef))
        cpy.Block(tree)(pfDef :: Nil, New(pfSym.typeRef, Nil))

      case _ =>
        val found = tpe.baseType(defn.FunctionClass(1))
        ctx.error(TypeMismatch(found, tpe), tree.sourcePos)
        tree
    }
  }

  private def checkRefinements(tpe: Type, tree: Tree)(implicit ctx: Context): Type = tpe.dealias match {
    case RefinedType(parent, name, _) =>
      if (name.isTermName && tpe.member(name).symbol.ownersIterator.isEmpty) // if member defined in the refinement
        ctx.error("Lambda does not define " + name, tree.sourcePos)
      checkRefinements(parent, tree)
    case tpe =>
      tpe
  }
}

