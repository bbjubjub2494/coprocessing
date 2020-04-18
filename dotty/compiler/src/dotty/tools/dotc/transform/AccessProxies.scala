package dotty.tools.dotc
package transform

import core._
import Contexts.Context
import Symbols._
import Flags._
import Names._
import NameOps._
import Decorators._
import TypeUtils._
import Types._
import NameKinds.ClassifiedNameKind
import ast.Trees._
import util.Spans.Span
import config.Printers.transforms

/** A utility class for generating access proxies. Currently used for
 *  inline accessors and protected accessors.
 */
abstract class AccessProxies {
  import ast.tpd._
  import AccessProxies._

  /** accessor -> accessed */
  private val accessedBy = newMutableSymbolMap[Symbol]

  /** Given the name of an accessor, is the receiver of the call to accessed obtained
   *  as a parameterer?
   */
  protected def passReceiverAsArg(accessorName: Name)(implicit ctx: Context): Boolean = false

  /** The accessor definitions that need to be added to class `cls`
   *  As a side-effect, this method removes entries from the `accessedBy` map.
   *  So a second call of the same method will yield the empty list.
   */
  private def accessorDefs(cls: Symbol)(implicit ctx: Context): Iterator[DefDef] =
    for (accessor <- cls.info.decls.iterator; accessed <- accessedBy.remove(accessor)) yield
      polyDefDef(accessor.asTerm, tps => argss => {
        def numTypeParams = accessed.info match {
          case info: PolyType => info.paramNames.length
          case _ => 0
        }
        val (accessRef, forwardedTypes, forwardedArgss) =
          if (passReceiverAsArg(accessor.name))
            (argss.head.head.select(accessed), tps.takeRight(numTypeParams), argss.tail)
          else
            (if (accessed.isStatic) ref(accessed) else ref(TermRef(cls.thisType, accessed)),
             tps, argss)
        val rhs =
          if (accessor.name.isSetterName &&
              forwardedArgss.nonEmpty && forwardedArgss.head.nonEmpty) // defensive conditions
            accessRef.becomes(forwardedArgss.head.head)
          else
            accessRef.appliedToTypes(forwardedTypes).appliedToArgss(forwardedArgss)
        rhs.withSpan(accessed.span)
      })

  /** Add all needed accessors to the `body` of class `cls` */
  def addAccessorDefs(cls: Symbol, body: List[Tree])(implicit ctx: Context): List[Tree] = {
    val accDefs = accessorDefs(cls)
    transforms.println(i"add accessors for $cls: $accDefs%, %")
    if (accDefs.isEmpty) body else body ++ accDefs
  }

  trait Insert {
    import ast.tpd._

    def accessorNameKind: ClassifiedNameKind
    def needsAccessor(sym: Symbol)(implicit ctx: Context): Boolean

    def ifNoHost(reference: RefTree)(implicit ctx: Context): Tree = {
      assert(false, "no host found for $reference with ${reference.symbol.showLocated} from ${ctx.owner}")
      reference
    }

    /** A fresh accessor symbol */
    private def newAccessorSymbol(owner: Symbol, name: TermName, info: Type, span: Span)(implicit ctx: Context): TermSymbol = {
      val sym = ctx.newSymbol(owner, name, Synthetic | Method, info, coord = span).entered
      if (sym.allOverriddenSymbols.exists(!_.is(Deferred))) sym.setFlag(Override)
      sym
    }

    /** An accessor symbol, create a fresh one unless one exists already */
    protected def accessorSymbol(owner: Symbol, accessorName: TermName, accessorInfo: Type, accessed: Symbol)(implicit ctx: Context): Symbol = {
      def refersToAccessed(sym: Symbol) = accessedBy.get(sym).contains(accessed)
      owner.info.decl(accessorName).suchThat(refersToAccessed).symbol.orElse {
        val acc = newAccessorSymbol(owner, accessorName, accessorInfo, accessed.span)
        accessedBy(acc) = accessed
        acc
      }
    }

    /** Rewire reference to refer to `accessor` symbol */
    private def rewire(reference: RefTree, accessor: Symbol)(implicit ctx: Context): Tree = {
      reference match {
        case Select(qual, _) if qual.tpe.derivesFrom(accessor.owner) => qual.select(accessor)
        case _ => ref(accessor)
      }
    }.withSpan(reference.span)

    /** Given a reference to a getter accessor, the corresponding setter reference */
    def useSetter(getterRef: Tree)(implicit ctx: Context): Tree = getterRef match {
      case getterRef: RefTree =>
        val getter = getterRef.symbol.asTerm
        val accessed = accessedBy(getter)
        val setterName = getter.name.setterName
        def toSetterInfo(getterInfo: Type): Type = getterInfo match {
          case getterInfo: LambdaType =>
            getterInfo.derivedLambdaType(resType = toSetterInfo(getterInfo.resType))
          case _ =>
            MethodType(getterInfo :: Nil, defn.UnitType)
        }
        val setterInfo = toSetterInfo(getter.info.widenExpr)
        val setter = accessorSymbol(getter.owner, setterName, setterInfo, accessed)
        rewire(getterRef, setter)
      case Apply(fn, args) =>
        cpy.Apply(getterRef)(useSetter(fn), args)
      case TypeApply(fn, args) =>
        cpy.TypeApply(getterRef)(useSetter(fn), args)
    }

    /** Create an accessor unless one exists already, and replace the original
      *  access with a reference to the accessor.
      *
      *  @param reference    The original reference to the non-public symbol
      *  @param onLHS        The reference is on the left-hand side of an assignment
      */
    def useAccessor(reference: RefTree)(implicit ctx: Context): Tree = {
      val accessed = reference.symbol.asTerm
      var accessorClass = hostForAccessorOf(accessed: Symbol)
      if (accessorClass.exists) {
        if accessorClass.is(Package) then
          accessorClass = ctx.owner.topLevelClass
        val accessorName = accessorNameKind(accessed.name)
        val accessorInfo =
          accessed.info.ensureMethodic.asSeenFrom(accessorClass.thisType, accessed.owner)
        val accessor = accessorSymbol(accessorClass, accessorName, accessorInfo, accessed)
        rewire(reference, accessor)
      }
      else ifNoHost(reference)
    }

    /** Replace tree with a reference to an accessor if needed */
    def accessorIfNeeded(tree: Tree)(implicit ctx: Context): Tree = tree match {
      case tree: RefTree if needsAccessor(tree.symbol) =>
        if (tree.symbol.isConstructor) {
          ctx.error("Implementation restriction: cannot use private constructors in inlineable methods", tree.sourcePos)
          tree // TODO: create a proper accessor for the private constructor
        }
        else useAccessor(tree)
      case _ =>
        tree
    }
  }
}
object AccessProxies {
  /** Where an accessor for the `accessed` symbol should be placed.
   *  This is the closest enclosing class that has `accessed` as a member.
   */
  def hostForAccessorOf(accessed: Symbol)(implicit ctx: Context): Symbol = {
    def recur(cls: Symbol): Symbol =
      if (!cls.exists) NoSymbol
      else if cls.derivesFrom(accessed.owner)
              || cls.companionModule.moduleClass == accessed.owner
      then cls
      else recur(cls.owner)
    recur(ctx.owner)
  }
}
