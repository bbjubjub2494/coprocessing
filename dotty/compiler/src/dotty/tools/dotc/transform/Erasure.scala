package dotty.tools
package dotc
package transform

import core.Phases._
import core.DenotTransformers._
import core.Denotations._
import core.SymDenotations._
import core.Symbols._
import core.Contexts._
import core.Types._
import core.Names._
import core.StdNames._
import core.NameOps._
import core.NameKinds.{AdaptedClosureName, BodyRetainerName}
import core.Decorators._
import core.Constants._
import core.Definitions._
import core.Annotations.BodyAnnotation
import typer.{NoChecking, LiftErased}
import typer.Inliner
import typer.ProtoTypes._
import core.TypeErasure._
import core.Decorators._
import dotty.tools.dotc.ast.{tpd, untpd}
import ast.Trees._
import ast.TreeTypeMap
import dotty.tools.dotc.core.{Constants, Flags}
import ValueClasses._
import TypeUtils._
import ContextFunctionResults._
import ExplicitOuter._
import core.Mode
import util.Property
import reporting.trace
import collection.mutable

class Erasure extends Phase with DenotTransformer {

  override def phaseName: String = Erasure.name

  /** List of names of phases that should precede this phase */
  override def runsAfter: Set[String] = Set(InterceptedMethods.name, ElimRepeated.name)

  override def changesMembers: Boolean = true // the phase adds bridges
  override def changesParents: Boolean = true // the phase drops Any

  def transform(ref: SingleDenotation)(using Context): SingleDenotation = ref match {
    case ref: SymDenotation =>
      def isCompacted(sym: Symbol) =
        sym.isAnonymousFunction && {
          sym.info(ctx.withPhase(ctx.phase.next)) match {
            case MethodType(nme.ALLARGS :: Nil) => true
            case _                              => false
          }
        }

      assert(ctx.phase == this, s"transforming $ref at ${ctx.phase}")
      if (ref.symbol eq defn.ObjectClass) {
        // After erasure, all former Any members are now Object members
        val ClassInfo(pre, _, ps, decls, selfInfo) = ref.info
        val extendedScope = decls.cloneScope
        for (decl <- defn.AnyClass.classInfo.decls)
          if (!decl.isConstructor) extendedScope.enter(decl)
        ref.copySymDenotation(
          info = transformInfo(ref.symbol,
              ClassInfo(pre, defn.ObjectClass, ps, extendedScope, selfInfo))
        )
      }
      else {
        val oldSymbol = ref.symbol
        val newSymbol =
          if ((oldSymbol.owner eq defn.AnyClass) && oldSymbol.isConstructor)
            defn.ObjectClass.primaryConstructor
          else oldSymbol
        val oldOwner = ref.owner
        val newOwner = if (oldOwner eq defn.AnyClass) defn.ObjectClass else oldOwner
        val oldName = ref.name
        val newName = ref.erasedName
        val oldInfo = ref.info
        val newInfo = transformInfo(oldSymbol, oldInfo)
        val oldFlags = ref.flags
        var newFlags =
          if (oldSymbol.is(Flags.TermParam) && isCompacted(oldSymbol.owner)) oldFlags &~ Flags.Param
          else oldFlags
        val oldAnnotations = ref.annotations
        var newAnnotations = oldAnnotations
        if oldSymbol.isRetainedInlineMethod then
          newFlags = newFlags &~ Flags.Inline
          newAnnotations = newAnnotations.filterConserve(!_.isInstanceOf[BodyAnnotation])
        // TODO: define derivedSymDenotation?
        if (oldSymbol eq newSymbol)
            && (oldOwner eq newOwner)
            && (oldName eq newName)
            && (oldInfo eq newInfo)
            && (oldFlags == newFlags)
            && (oldAnnotations eq newAnnotations)
        then
          ref
        else
          assert(!ref.is(Flags.PackageClass), s"trans $ref @ ${ctx.phase} oldOwner = $oldOwner, newOwner = $newOwner, oldInfo = $oldInfo, newInfo = $newInfo ${oldOwner eq newOwner} ${oldInfo eq newInfo}")
          ref.copySymDenotation(
            symbol = newSymbol,
            owner = newOwner,
            name = newName,
            initFlags = newFlags,
            info = newInfo,
            annotations = newAnnotations)
      }
    case ref: JointRefDenotation =>
      new UniqueRefDenotation(
        ref.symbol, transformInfo(ref.symbol, ref.symbol.info), ref.validFor, ref.prefix)
    case _ =>
      ref.derivedSingleDenotation(ref.symbol, transformInfo(ref.symbol, ref.symbol.info))
  }

  private val eraser = new Erasure.Typer(this)

  def run(using Context): Unit = {
    val unit = ctx.compilationUnit
    unit.tpdTree = eraser.typedExpr(unit.tpdTree)(using ctx.fresh.setTyper(eraser).setPhase(this.next))
  }

  override def checkPostCondition(tree: tpd.Tree)(using Context): Unit = {
    assertErased(tree)
    tree match {
      case res: tpd.This =>
        assert(!ExplicitOuter.referencesOuter(ctx.owner.lexicallyEnclosingClass, res),
          i"Reference to $res from ${ctx.owner.showLocated}")
      case ret: tpd.Return =>
        // checked only after erasure, as checking before erasure is complicated
        // due presence of type params in returned types
        val from = if (ret.from.isEmpty) ctx.owner.enclosingMethod else ret.from.symbol
        val rType = from.info.finalResultType
        assert(ret.expr.tpe <:< rType,
          i"Returned value:${ret.expr}  does not conform to result type(${ret.expr.tpe.widen} of method $from")
      case _ =>
    }
  }

  /** Assert that tree type and its widened underlying type are erased.
   *  Also assert that term refs have fixed symbols (so we are sure
   *  they need not be reloaded using member; this would likely fail as signatures
   *  may change after erasure).
   */
  def assertErased(tree: tpd.Tree)(using Context): Unit = {
    assertErased(tree.typeOpt, tree)
    if (!defn.isPolymorphicAfterErasure(tree.symbol))
      assertErased(tree.typeOpt.widen, tree)
    if (ctx.mode.isExpr)
      tree.tpe match {
        case ref: TermRef =>
          assert(ref.denot.isInstanceOf[SymDenotation] ||
              ref.denot.isInstanceOf[UniqueRefDenotation],
            i"non-sym type $ref of class ${ref.getClass} with denot of class ${ref.denot.getClass} of $tree")
        case _ =>
      }
  }

  def assertErased(tp: Type, tree: tpd.Tree = tpd.EmptyTree)(using Context): Unit = {
    def isAllowed(cls: Symbol, sourceName: String) =
      tp.widen.typeSymbol == cls && ctx.compilationUnit.source.file.name == sourceName
    assert(isErasedType(tp) ||
           isAllowed(defn.ArrayClass, "Array.scala") ||
           isAllowed(defn.TupleClass, "Tuple.scala") ||
           isAllowed(defn.NonEmptyTupleClass, "Tuple.scala") ||
           isAllowed(defn.PairClass, "Tuple.scala"),
        i"The type $tp - ${tp.toString} of class ${tp.getClass} of tree $tree : ${tree.tpe} / ${tree.getClass} is illegal after erasure, phase = ${ctx.phase.prev}")
  }
}

object Erasure {
  import tpd._
  import TypeTestsCasts._

  val name: String = "erasure"

  /** An attachment on Apply nodes indicating that multiple arguments
   *  are passed in a single array. This occurs only if the function
   *  implements a FunctionXXL apply.
   */
  private val BunchedArgs = new Property.Key[Unit]

  /** An Apply node which might still be missing some arguments */
  def partialApply(fn: Tree, args: List[Tree])(using Context): Tree =
    untpd.Apply(fn, args.toList)
      .withType(applyResultType(fn.tpe.widen.asInstanceOf[MethodType], args))

  /** The type of an Apply node which might still be missing some arguments */
  private def applyResultType(mt: MethodType, args: List[Tree])(using Context): Type =
    if mt.paramInfos.length <= args.length then mt.resultType
    else MethodType(mt.paramInfos.drop(args.length), mt.resultType)

  /** The method type corresponding to `mt`, except that bunched parameters
   *  are expanded. The expansion is determined using the original function
   *  tree `origFun` which has a type that is not yet converted to a type
   *  with bunched arguments.
   */
  def expandedMethodType(mt: MethodType, origFun: Tree)(using Context): MethodType =
    mt.paramInfos match
      case JavaArrayType(elemType) :: Nil if elemType.isRef(defn.ObjectClass) =>
        val origArity = totalParamCount(origFun.symbol)(using preErasureCtx)
        if origArity > MaxImplementedFunctionArity then
          MethodType(List.fill(origArity)(defn.ObjectType), mt.resultType)
        else mt
      case _ => mt

  object Boxing:

    def isUnbox(sym: Symbol)(using Context): Boolean =
      sym.name == nme.unbox && sym.owner.linkedClass.isPrimitiveValueClass

    def isBox(sym: Symbol)(using Context): Boolean =
      sym.name == nme.box && sym.owner.linkedClass.isPrimitiveValueClass

    def boxMethod(cls: ClassSymbol)(using Context): Symbol =
      cls.linkedClass.info.member(nme.box).symbol
    def unboxMethod(cls: ClassSymbol)(using Context): Symbol =
      cls.linkedClass.info.member(nme.unbox).symbol

    /** Isf this tree is an unbox operation which can be safely removed
     *  when enclosed in a box, the unboxed argument, otherwise EmptyTree.
     *  Note that one can't always remove a Box(Unbox(x)) combination because the
     *  process of unboxing x may lead to throwing an exception.
     *  This is important for specialization: calls to the super constructor should not box/unbox specialized
     *  fields (see TupleX). (ID)
     */
    private def safelyRemovableUnboxArg(tree: Tree)(using Context): Tree = tree match {
      case Apply(fn, arg :: Nil)
      if isUnbox(fn.symbol) && defn.ScalaBoxedClasses().contains(arg.tpe.widen.typeSymbol) =>
        arg
      case _ =>
        EmptyTree
    }

    def constant(tree: Tree, const: Tree)(using Context): Tree =
      (if (isPureExpr(tree)) const else Block(tree :: Nil, const)).withSpan(tree.span)

    final def box(tree: Tree, target: => String = "")(using Context): Tree = trace(i"boxing ${tree.showSummary}: ${tree.tpe} into $target") {
      tree.tpe.widen match {
        case ErasedValueType(tycon, _) =>
          New(tycon, cast(tree, underlyingOfValueClass(tycon.symbol.asClass)) :: Nil) // todo: use adaptToType?
        case tp =>
          val cls = tp.classSymbol
          if (cls eq defn.UnitClass) constant(tree, ref(defn.BoxedUnit_UNIT))
          else if (cls eq defn.NothingClass) tree // a non-terminating expression doesn't need boxing
          else {
            assert(cls ne defn.ArrayClass)
            val arg = safelyRemovableUnboxArg(tree)
            if (arg.isEmpty) ref(boxMethod(cls.asClass)).appliedTo(tree)
            else {
              ctx.log(s"boxing an unbox: ${tree.symbol} -> ${arg.tpe}")
              arg
            }
          }
      }
    }

    def unbox(tree: Tree, pt: Type)(using Context): Tree = trace(i"unboxing ${tree.showSummary}: ${tree.tpe} as a $pt") {
      pt match {
        case ErasedValueType(tycon, underlying) =>
          def unboxedTree(t: Tree) =
            adaptToType(t, tycon)
            .select(valueClassUnbox(tycon.symbol.asClass))
            .appliedToNone

          // Null unboxing needs to be treated separately since we cannot call a method on null.
          // "Unboxing" null to underlying is equivalent to doing null.asInstanceOf[underlying]
          // See tests/pos/valueclasses/nullAsInstanceOfVC.scala for cases where this might happen.
          val tree1 =
            if (tree.tpe isRef defn.NullClass)
              adaptToType(tree, underlying)
            else if (!(tree.tpe <:< tycon)) {
              assert(!(tree.tpe.typeSymbol.isPrimitiveValueClass))
              val nullTree = nullLiteral
              val unboxedNull = adaptToType(nullTree, underlying)

              evalOnce(tree) { t =>
                If(t.select(defn.Object_eq).appliedTo(nullTree),
                  unboxedNull,
                  unboxedTree(t))
              }
            }
            else unboxedTree(tree)

          cast(tree1, pt)
        case _ =>
          val cls = pt.widen.classSymbol
          if (cls eq defn.UnitClass) constant(tree, Literal(Constant(())))
          else {
            assert(cls ne defn.ArrayClass)
            ref(unboxMethod(cls.asClass)).appliedTo(tree)
          }
      }
    }

    /** Generate a synthetic cast operation from tree.tpe to pt.
     *  Does not do any boxing/unboxing (this is handled upstream).
     *  Casts from and to ErasedValueType are special, see the explanation
     *  in ExtensionMethods#transform.
     */
    def cast(tree: Tree, pt: Type)(using Context): Tree = trace(i"cast ${tree.tpe.widen} --> $pt", show = true) {

      def wrap(tycon: TypeRef) =
        ref(u2evt(tycon.typeSymbol.asClass)).appliedTo(tree)
      def unwrap(tycon: TypeRef) =
        ref(evt2u(tycon.typeSymbol.asClass)).appliedTo(tree)

      assert(!pt.isInstanceOf[SingletonType], pt)
      if (pt isRef defn.UnitClass) unbox(tree, pt)
      else (tree.tpe.widen, pt) match {
        case (JavaArrayType(treeElem), JavaArrayType(ptElem))
        if treeElem.widen.isPrimitiveValueType && !ptElem.isPrimitiveValueType =>
          // See SI-2386 for one example of when this might be necessary.
          cast(ref(defn.runtimeMethodRef(nme.toObjectArray)).appliedTo(tree), pt)

        // When casting between two EVTs, we need to check which one underlies the other to determine
        // whether u2evt or evt2u should be used.
        case (tp1 @ ErasedValueType(tycon1, underlying1), tp2 @ ErasedValueType(tycon2, underlying2)) =>
          if (tp1 <:< underlying2)
            // Cast EVT(tycon1, underlying1) to EVT(tycon2, EVT(tycon1, underlying1))
            wrap(tycon2)
          else {
            assert(underlying1 <:< tp2, i"Non-sensical cast between unrelated types $tp1 and $tp2")
            // Cast EVT(tycon1, EVT(tycon2, underlying2)) to EVT(tycon2, underlying2)
            unwrap(tycon1)
          }

        // When only one type is an EVT then we already know that the other one is the underlying
        case (_, ErasedValueType(tycon2, _)) =>
          wrap(tycon2)
        case (ErasedValueType(tycon1, _), _) =>
          unwrap(tycon1)

        case _ =>
          if (pt.isPrimitiveValueType)
            primitiveConversion(tree, pt.classSymbol)
          else
            tree.asInstance(pt)
      }
    }

    /** Adaptation of an expression `e` to an expected type `PT`, applying the following
     *  rewritings exhaustively as long as the type of `e` is not a subtype of `PT`.
     *
     *    e -> e()           if `e` appears not as the function part of an application
     *    e -> box(e)        if `e` is of erased value type
     *    e -> unbox(e, PT)  otherwise, if `PT` is an erased value type
     *    e -> box(e)        if `e` is of primitive type and `PT` is not a primitive type
     *    e -> unbox(e, PT)  if `PT` is a primitive type and `e` is not of primitive type
     *    e -> cast(e, PT)   otherwise
     */
    def adaptToType(tree: Tree, pt: Type)(using Context): Tree = pt match
      case _: FunProto | AnyFunctionProto => tree
      case _ => tree.tpe.widen match
        case mt: MethodType if tree.isTerm =>
          if mt.paramInfos.isEmpty then adaptToType(tree.appliedToNone, pt)
          else etaExpand(tree, mt, pt)
        case tpw =>
          if (pt.isInstanceOf[ProtoType] || tree.tpe <:< pt)
            tree
          else if (tpw.isErasedValueType)
            adaptToType(box(tree), pt)
          else if (pt.isErasedValueType)
            adaptToType(unbox(tree, pt), pt)
          else if (tpw.isPrimitiveValueType && !pt.isPrimitiveValueType)
            adaptToType(box(tree), pt)
          else if (pt.isPrimitiveValueType && !tpw.isPrimitiveValueType)
            adaptToType(unbox(tree, pt), pt)
          else
            cast(tree, pt)
    end adaptToType


    /** The following code:
     *
     *      val f: Function1[Int, Any] = x => ...
     *
     *  results in the creation of a closure and a method in the typer:
     *
     *      def $anonfun(x: Int): Any = ...
     *      val f: Function1[Int, Any] = closure($anonfun)
     *
     *  Notice that `$anonfun` takes a primitive as argument, but the single abstract method
     *  of `Function1` after erasure is:
     *
     *      def apply(x: Object): Object
     *
     *  which takes a reference as argument. Hence, some form of adaptation is required.
     *
     *  If we do nothing, the LambdaMetaFactory bootstrap method will
     *  automatically do the adaptation. Unfortunately, the result does not
     *  implement the expected Scala semantics: null should be "unboxed" to
     *  the default value of the value class, but LMF will throw a
     *  NullPointerException instead. LMF is also not capable of doing
     *  adaptation for derived value classes.
     *
     *  Thus, we need to replace the closure method by a bridge method that
     *  forwards to the original closure method with appropriate
     *  boxing/unboxing. For our example above, this would be:
     *
     *      def $anonfun1(x: Object): Object = $anonfun(BoxesRunTime.unboxToInt(x))
     *      val f: Function1 = closure($anonfun1)
     *
     *  In general a bridge is needed when, after Erasure, one of the
     *  parameter type or the result type of the closure method has a
     *  different type, and we cannot rely on auto-adaptation.
     *
     *  Auto-adaptation works in the following cases:
     *  - If the SAM is replaced by JFunction*mc* in
     *    [[FunctionalInterfaces]], no bridge is needed: the SAM contains
     *    default methods to handle adaptation.
     *  - If a result type of the closure method is a primitive value type
     *    different from Unit, we can rely on the auto-adaptation done by
     *    LMF (because it only needs to box, not unbox, so no special
     *    handling of null is required).
     *  - If the SAM is replaced by JProcedure* in
     *    [[DottyBackendInterface]] (this only happens when no explicit SAM
     *    type is given), no bridge is needed to box a Unit result type:
     *    the SAM contains a default method to handle that.
     *
     *  See test cases lambda-*.scala and t8017/ for concrete examples.
     */
    def adaptClosure(tree: tpd.Closure)(using Context): Tree = {
      val implClosure @ Closure(_, meth, _) = tree

      implClosure.tpe match {
        case SAMType(sam) =>
          val implType = meth.tpe.widen.asInstanceOf[MethodType]

          val implParamTypes = implType.paramInfos
          val List(samParamTypes) = sam.paramInfoss
          val implResultType = implType.resultType
          val samResultType = sam.resultType

          if (!defn.isSpecializableFunction(implClosure.tpe.widen.classSymbol.asClass, implParamTypes, implResultType)) {
            def autoAdaptedParam(tp: Type) = !tp.isErasedValueType && !tp.isPrimitiveValueType
            val explicitSAMType = implClosure.tpt.tpe.exists
            def autoAdaptedResult(tp: Type) = !tp.isErasedValueType &&
              (!explicitSAMType || tp.typeSymbol != defn.UnitClass)
            def sameSymbol(tp1: Type, tp2: Type) = tp1.typeSymbol == tp2.typeSymbol

            val paramAdaptationNeeded =
              implParamTypes.lazyZip(samParamTypes).exists((implType, samType) =>
                !sameSymbol(implType, samType) && !autoAdaptedParam(implType))
            val resultAdaptationNeeded =
              !sameSymbol(implResultType, samResultType) && !autoAdaptedResult(implResultType)

            if (paramAdaptationNeeded || resultAdaptationNeeded) {
              val bridgeType =
                if (paramAdaptationNeeded)
                  if (resultAdaptationNeeded) sam
                  else implType.derivedLambdaType(paramInfos = samParamTypes)
                else implType.derivedLambdaType(resType = samResultType)
              val bridge = ctx.newSymbol(ctx.owner, AdaptedClosureName(meth.symbol.name.asTermName), Flags.Synthetic | Flags.Method, bridgeType)
              Closure(bridge, bridgeParamss =>
                inContext(ctx.withOwner(bridge)) {
                  val List(bridgeParams) = bridgeParamss
                  assert(ctx.typer.isInstanceOf[Erasure.Typer])
                  val rhs = Apply(meth, bridgeParams.lazyZip(implParamTypes).map(ctx.typer.adapt(_, _)))
                  ctx.typer.adapt(rhs, bridgeType.resultType)
                },
                targetType = implClosure.tpt.tpe)
            }
            else implClosure
          }
          else implClosure
        case _ =>
          implClosure
      }
    }

    /** Eta expand given `tree` that has the given method type `mt`, so that
     *  it conforms to erased result type `pt`.
     *  To do this correctly, we have to look at the tree's original pre-erasure
     *  type and figure out which context function types in its result are
     *  not yet instantiated.
     */
    def etaExpand(tree: Tree, mt: MethodType, pt: Type)(using Context): Tree =
      ctx.log(i"eta expanding $tree")
      val defs = new mutable.ListBuffer[Tree]
      val tree1 = LiftErased.liftApp(defs, tree)
      val xmt = if tree.isInstanceOf[Apply] then mt else expandedMethodType(mt, tree)
      val targetLength = xmt.paramInfos.length
      val origOwner = ctx.owner

      // The original type from which closures should be constructed
      val origType = contextFunctionResultTypeCovering(tree.symbol, targetLength)

      def abstracted(args: List[Tree], tp: Type, pt: Type)(using Context): Tree =
        if args.length < targetLength then
          try
            val defn.ContextFunctionType(argTpes, resTpe, isErased): @unchecked = tp
            if isErased then abstracted(args, resTpe, pt)
            else
              val anonFun = ctx.newSymbol(
                ctx.owner, nme.ANON_FUN, Flags.Synthetic | Flags.Method,
                MethodType(argTpes, resTpe), coord = tree.span.endPos)
              anonFun.info = transformInfo(anonFun, anonFun.info)
              def lambdaBody(refss: List[List[Tree]]) =
                val refs :: Nil : @unchecked = refss
                val expandedRefs = refs.map(_.withSpan(tree.span.endPos)) match
                  case (bunchedParam @ Ident(nme.ALLARGS)) :: Nil =>
                    argTpes.indices.toList.map(n =>
                      bunchedParam
                        .select(nme.primitive.arrayApply)
                        .appliedTo(Literal(Constant(n))))
                  case refs1 => refs1
                abstracted(args ::: expandedRefs, resTpe, anonFun.info.finalResultType)(
                  using ctx.withOwner(anonFun))

              val unadapted = Closure(anonFun, lambdaBody)
              cpy.Block(unadapted)(unadapted.stats, adaptClosure(unadapted.expr.asInstanceOf[Closure]))
          catch case ex: MatchError =>
            println(i"error while abstracting tree = $tree | mt = $mt | args = $args%, % | tp = $tp | pt = $pt")
            throw ex
        else
          assert(args.length == targetLength, i"wrong # args tree = $tree | args = $args%, % | mt = $mt | tree type = ${tree.tpe}")
          val app = untpd.cpy.Apply(tree1)(tree1, args)
          assert(ctx.typer.isInstanceOf[Erasure.Typer])
          ctx.typer.typed(app, pt)
            .changeOwnerAfter(origOwner, ctx.owner, ctx.erasurePhase.asInstanceOf[Erasure])

      seq(defs.toList, abstracted(Nil, origType, pt))
    end etaExpand

  end Boxing

  class Typer(erasurePhase: DenotTransformer) extends typer.ReTyper with NoChecking {
    import Boxing._

    def isErased(tree: Tree)(using Context): Boolean = tree match {
      case TypeApply(Select(qual, _), _) if tree.symbol == defn.Any_typeCast =>
        isErased(qual)
      case _ => tree.symbol.isEffectivelyErased
    }

    /** Check that Java statics and packages can only be used in selections.
      */
    private def checkValue(tree: Tree, proto: Type)(using Context): tree.type =
      if (!proto.isInstanceOf[SelectionProto] && !proto.isInstanceOf[ApplyingProto]) then
        checkValue(tree)
      tree

    private def checkValue(tree: Tree)(using Context): Unit =
      val sym = tree.tpe.termSymbol
      if (sym is Flags.Package)
         || (sym.isAllOf(Flags.JavaModule) && !ctx.compilationUnit.isJava)
      then
        ctx.error(reporting.messages.JavaSymbolIsNotAValue(sym), tree.sourcePos)

    private def checkNotErased(tree: Tree)(using Context): tree.type = {
      if (!ctx.mode.is(Mode.Type)) {
        if (isErased(tree))
          ctx.error(em"${tree.symbol} is declared as erased, but is in fact used", tree.sourcePos)
        tree.symbol.getAnnotation(defn.CompileTimeOnlyAnnot) match {
          case Some(annot) =>
            def defaultMsg =
              i"""Reference to ${tree.symbol.showLocated} should not have survived,
                 |it should have been processed and eliminated during expansion of an enclosing macro or term erasure."""
            val message = annot.argumentConstant(0).fold(defaultMsg)(_.stringValue)
            ctx.error(message, tree.sourcePos)
          case _ => // OK
        }
      }
      tree
    }

    def erasedDef(sym: Symbol)(using Context): Thicket = {
      if (sym.owner.isClass) sym.dropAfter(erasurePhase)
      tpd.EmptyTree
    }

    def erasedType(tree: untpd.Tree)(using Context): Type = {
      val tp = tree.typeOpt
      if (tree.isTerm) erasedRef(tp) else valueErasure(tp)
    }

    override def promote(tree: untpd.Tree)(using Context): tree.ThisTree[Type] = {
      assert(tree.hasType)
      val erasedTp = erasedType(tree)
      ctx.log(s"promoting ${tree.show}: ${erasedTp.showWithUnderlying()}")
      tree.withType(erasedTp)
    }

    /** When erasing most TypeTrees we should not semi-erase value types.
     *  This is not the case for [[DefDef#tpt]], [[ValDef#tpt]] and [[Typed#tpt]], they
     *  are handled separately by [[typedDefDef]], [[typedValDef]] and [[typedTyped]].
     */
    override def typedTypeTree(tree: untpd.TypeTree, pt: Type)(using Context): TypeTree =
      tree.withType(erasure(tree.tpe))

    /** This override is only needed to semi-erase type ascriptions */
    override def typedTyped(tree: untpd.Typed, pt: Type)(using Context): Tree = {
      val Typed(expr, tpt) = tree
      val tpt1 = tpt match {
        case Block(_, tpt) => tpt // erase type aliases (statements) from type block
        case tpt => tpt
      }
      val tpt2 = typedType(tpt1)
      val expr1 = typed(expr, tpt2.tpe)
      assignType(untpd.cpy.Typed(tree)(expr1, tpt2), tpt2)
    }

    override def typedLiteral(tree: untpd.Literal)(using Context): Tree =
      if (tree.typeOpt.isRef(defn.UnitClass))
        tree.withType(tree.typeOpt)
      else if (tree.const.tag == Constants.ClazzTag)
        Literal(Constant(erasure(tree.const.typeValue)))
      else
        super.typedLiteral(tree)

    override def typedIdent(tree: untpd.Ident, pt: Type)(using Context): Tree =
      checkValue(checkNotErased(super.typedIdent(tree, pt)), pt)

    /** Type check select nodes, applying the following rewritings exhaustively
     *  on selections `e.m`, where `OT` is the type of the owner of `m` and `ET`
     *  is the erased type of the selection's original qualifier expression.
     *
     *      e.m1 -> e.m2          if `m1` is a member of a class that erases to Object and `m2` is
     *                            the same-named member in Object.
     *      e.m -> box(e).m       if `e` is primitive and `m` is a member or a reference class
     *                            or `e` has an erased value class type.
     *      e.m -> unbox(e).m     if `e` is not primitive and `m` is a member of a primtive type.
     *      e.m -> cast(e, OT).m  if the type of `e` does not conform to OT and `m`
     *                            is not an array operation.
     *
     *  If `m` is an array operation, i.e. one of the members apply, update, length, clone, and
     *  <init> of class Array, we additionally try the following rewritings:
     *
     *      e.m -> runtime.array_m(e)   if ET is Object
     *      e.m -> cast(e, ET).m        if the type of `e` does not conform to ET
     *      e.clone -> e.clone'         where clone' is Object's clone method
     *      e.m -> e.[]m                if `m` is an array operation other than `clone`.
     */
    override def typedSelect(tree: untpd.Select, pt: Type)(using Context): Tree = {
      if tree.name == nme.apply && integrateSelect(tree) then
      	return typed(tree.qualifier, pt)

      val qual1 = typed(tree.qualifier, AnySelectionProto)

      def mapOwner(sym: Symbol): Symbol =
        if !sym.exists && tree.name == nme.apply then
          // PolyFunction apply Selects will not have a symbol, so deduce the owner
          // from the typed qual.
          val owner = qual1.tpe.widen.typeSymbol
          if defn.isFunctionClass(owner) then owner else NoSymbol
        else
          val owner = sym.maybeOwner
          if defn.specialErasure.contains(owner) then
            assert(sym.isConstructor, s"${sym.showLocated}")
            defn.specialErasure(owner)
          else if defn.isSyntheticFunctionClass(owner)
            defn.erasedFunctionClass(owner)
          else
            owner

      val origSym = tree.symbol

      if !origSym.exists && qual1.tpe.widen.isInstanceOf[JavaArrayType] then
        return tree.asInstanceOf[Tree] // we are re-typing a primitive array op

      val owner = mapOwner(origSym)
      val sym = if (owner eq origSym.maybeOwner) origSym else owner.info.decl(tree.name).symbol
      assert(sym.exists, origSym.showLocated)

      if owner == defn.ObjectClass then checkValue(qual1)

      def select(qual: Tree, sym: Symbol): Tree =
        untpd.cpy.Select(tree)(qual, sym.name).withType(NamedType(qual.tpe, sym))

      def selectArrayMember(qual: Tree, erasedPre: Type): Tree =
        if erasedPre.isAnyRef then
          partialApply(ref(defn.runtimeMethodRef(tree.name.genericArrayOp)), qual :: Nil)
        else if !(qual.tpe <:< erasedPre) then
          selectArrayMember(cast(qual, erasedPre), erasedPre)
        else
          assignType(untpd.cpy.Select(tree)(qual, tree.name.primitiveArrayOp), qual)

      def adaptIfSuper(qual: Tree): Tree = qual match {
        case Super(thisQual, untpd.EmptyTypeIdent) =>
          val SuperType(thisType, supType) = qual.tpe
          if (sym.owner.is(Flags.Trait))
            cpy.Super(qual)(thisQual, untpd.Ident(sym.owner.asClass.name))
              .withType(SuperType(thisType, sym.owner.typeRef))
          else
            qual.withType(SuperType(thisType, thisType.firstParent.typeConstructor))
        case _ =>
          qual
      }

      def recur(qual: Tree): Tree = {
        val qualIsPrimitive = qual.tpe.widen.isPrimitiveValueType
        val symIsPrimitive = sym.owner.isPrimitiveValueClass
        if (qualIsPrimitive && !symIsPrimitive || qual.tpe.widenDealias.isErasedValueType)
          recur(box(qual))
        else if (!qualIsPrimitive && symIsPrimitive)
          recur(unbox(qual, sym.owner.typeRef))
        else if (sym.owner eq defn.ArrayClass)
          selectArrayMember(qual, erasure(tree.qualifier.typeOpt.widen.finalResultType))
        else {
          val qual1 = adaptIfSuper(qual)
          if (qual1.tpe.derivesFrom(sym.owner) || qual1.isInstanceOf[Super])
            select(qual1, sym)
          else
            val castTarget = // Avoid inaccessible cast targets, see i8661
              if sym.owner.isAccessibleFrom(qual1.tpe)(using preErasureCtx)
              then sym.owner.typeRef
              else erasure(tree.qualifier.typeOpt.widen)
            recur(cast(qual1, castTarget))
        }
      }

      checkValue(checkNotErased(recur(qual1)), pt)
    }

    override def typedThis(tree: untpd.This)(using Context): Tree =
      if (tree.symbol == ctx.owner.lexicallyEnclosingClass || tree.symbol.isStaticOwner) promote(tree)
      else {
        ctx.log(i"computing outer path from ${ctx.owner.ownersIterator.toList}%, % to ${tree.symbol}, encl class = ${ctx.owner.enclosingClass}")
        outer.path(toCls = tree.symbol)
      }

    override def typedTypeApply(tree: untpd.TypeApply, pt: Type)(using Context): Tree = {
      val ntree = interceptTypeApply(tree.asInstanceOf[TypeApply])(using ctx.withPhase(ctx.erasurePhase)).withSpan(tree.span)

      ntree match {
        case TypeApply(fun, args) =>
          val fun1 = typedExpr(fun, AnyFunctionProto)
          fun1.tpe.widen match {
            case funTpe: PolyType =>
              val args1 = args.mapconserve(typedType(_))
              untpd.cpy.TypeApply(tree)(fun1, args1).withType(funTpe.instantiate(args1.tpes))
            case _ => fun1
          }
        case _ => typedExpr(ntree, pt)
      }
    }

    /** Besides normal typing, this method does uncurrying and collects parameters
     *  to anonymous functions of arity > 22.
     */
    override def typedApply(tree: untpd.Apply, pt: Type)(using Context): Tree =
      val Apply(fun, args) = tree
      if fun.symbol == defn.cbnArg then
        typedUnadapted(args.head, pt)
      else
        val origFun = fun.asInstanceOf[tpd.Tree]
        val origFunType = origFun.tpe.widen(using preErasureCtx)
        val ownArgs = if origFunType.isErasedMethod then Nil else args
        val fun1 = typedExpr(fun, AnyFunctionProto)
        val fun1core = stripBlock(fun1)
        fun1.tpe.widen match
          case mt: MethodType =>
            val (xmt,        // A method type like `mt` but with bunched arguments expanded to individual ones
                 bunchArgs,  // whether arguments are bunched
                 outers) =   // the outer reference parameter(s)
              if fun1core.isInstanceOf[Apply] then
                (mt, fun1core.removeAttachment(BunchedArgs).isDefined, Nil)
              else
                val xmt = expandedMethodType(mt, origFun)
                (xmt, xmt ne mt, outer.args(origFun))

            val args0 = outers ::: ownArgs
            val args1 = args0.zipWithConserve(xmt.paramInfos)(typedExpr)
              .asInstanceOf[List[Tree]]

            def mkApply(finalFun: Tree, finalArgs: List[Tree]) =
              val app = untpd.cpy.Apply(tree)(finalFun, finalArgs)
                .withType(applyResultType(xmt, args1))
              if bunchArgs then app.withAttachment(BunchedArgs, ()) else app

            def app(fun1: Tree): Tree = fun1 match
              case Block(stats, expr) =>
                cpy.Block(fun1)(stats, app(expr))
              case Apply(fun2, SeqLiteral(prevArgs, argTpt) :: _) if bunchArgs =>
                mkApply(fun2, JavaSeqLiteral(prevArgs ++ args1, argTpt) :: Nil)
              case Apply(fun2, prevArgs) =>
                mkApply(fun2, prevArgs ++ args1)
              case _ if bunchArgs =>
                mkApply(fun1, JavaSeqLiteral(args1, TypeTree(defn.ObjectType)) :: Nil)
              case _ =>
                mkApply(fun1, args1)

            app(fun1)
          case t =>
            if ownArgs.isEmpty then fun1
            else throw new MatchError(i"tree $tree has unexpected type of function $fun/$fun1: $t, was $origFunType, args = $ownArgs")
    end typedApply

    // The following four methods take as the proto-type the erasure of the pre-existing type,
    // if the original proto-type is not a value type.
    // This makes all branches be adapted to the correct type.
    override def typedSeqLiteral(tree: untpd.SeqLiteral, pt: Type)(using Context): SeqLiteral =
      super.typedSeqLiteral(tree, erasure(tree.typeOpt))
        // proto type of typed seq literal is original type;

    override def typedIf(tree: untpd.If, pt: Type)(using Context): Tree =
      super.typedIf(tree, adaptProto(tree, pt))

    override def typedMatch(tree: untpd.Match, pt: Type)(using Context): Tree =
      super.typedMatch(tree, adaptProto(tree, pt))

    override def typedTry(tree: untpd.Try, pt: Type)(using Context): Try =
      super.typedTry(tree, adaptProto(tree, pt))

    private def adaptProto(tree: untpd.Tree, pt: Type)(using Context) =
      if (pt.isValueType) pt else
        if (tree.typeOpt.derivesFrom(ctx.definitions.UnitClass))
          tree.typeOpt
        else valueErasure(tree.typeOpt)

    override def typedInlined(tree: untpd.Inlined, pt: Type)(using Context): Tree =
      super.typedInlined(tree, pt) match {
        case tree: Inlined => Inliner.dropInlined(tree)
      }

    override def typedValDef(vdef: untpd.ValDef, sym: Symbol)(using Context): Tree =
      if (sym.isEffectivelyErased) erasedDef(sym)
      else
        super.typedValDef(untpd.cpy.ValDef(vdef)(
          tpt = untpd.TypedSplice(TypeTree(sym.info).withSpan(vdef.tpt.span))), sym)

    /** Besides normal typing, this function also compacts anonymous functions
     *  with more than `MaxImplementedFunctionArity` parameters to use a single
     *  parameter of type `[]Object`.
     */
    override def typedDefDef(ddef: untpd.DefDef, sym: Symbol)(using Context): Tree =
      if sym.isEffectivelyErased || sym.name.is(BodyRetainerName) then
        erasedDef(sym)
      else
        val restpe = if sym.isConstructor then defn.UnitType else sym.info.resultType
        var vparams = outerParamDefs(sym)
            ::: ddef.vparamss.flatten.filterConserve(!_.symbol.is(Flags.Erased))

        def skipContextClosures(rhs: Tree, crCount: Int)(using Context): Tree =
          if crCount == 0 then rhs
          else rhs match
            case closureDef(meth) =>
              val contextParams = meth.vparamss.head
              for param <- contextParams do
                param.symbol.copySymDenotation(owner = sym).installAfter(erasurePhase)
              vparams ++= contextParams
              if crCount == 1 then meth.rhs.changeOwnerAfter(meth.symbol, sym, erasurePhase)
              else skipContextClosures(meth.rhs, crCount - 1)

        var rhs1 = skipContextClosures(ddef.rhs.asInstanceOf[Tree], contextResultCount(sym))

        if sym.isAnonymousFunction && vparams.length > MaxImplementedFunctionArity then
          val bunchedParam = ctx.newSymbol(sym, nme.ALLARGS, Flags.TermParam, JavaArrayType(defn.ObjectType))
          def selector(n: Int) = ref(bunchedParam)
            .select(defn.Array_apply)
            .appliedTo(Literal(Constant(n)))
          val paramDefs = vparams.zipWithIndex.map {
            case (paramDef, idx) =>
              assignType(untpd.cpy.ValDef(paramDef)(rhs = selector(idx)), paramDef.symbol)
          }
          vparams = ValDef(bunchedParam) :: Nil
          rhs1 = Block(paramDefs, rhs1)

        val ddef1 = untpd.cpy.DefDef(ddef)(
          tparams = Nil,
          vparamss = vparams :: Nil,
          tpt = untpd.TypedSplice(TypeTree(restpe).withSpan(ddef.tpt.span)),
          rhs = rhs1)
        super.typedDefDef(ddef1, sym)
    end typedDefDef

    /** The outer parameter definition of a constructor if it needs one */
    private def outerParamDefs(constr: Symbol)(using Context): List[ValDef] =
      if constr.isConstructor && hasOuterParam(constr.owner.asClass) then
        constr.info match
          case MethodTpe(outerName :: _, outerType :: _, _) =>
            val outerSym = ctx.newSymbol(constr, outerName, Flags.Param, outerType)
            ValDef(outerSym) :: Nil
          case _ =>
            // There's a possible race condition that a constructor was looked at
            // after erasure before we had a chance to run ExplicitOuter on its class
            // If furthermore the enclosing class does not always have constructors,
            // but needs constructors in this particular case, we miss the constructor
            // accessor that's produced with an `enteredAfter` in ExplicitOuter, so
            // `tranformInfo` of the constructor in erasure yields a method type without
            // an outer parameter. We fix this problem by adding the missing outer
            // parameter here.
            constr.copySymDenotation(
              info = outer.addParam(constr.owner.asClass, constr.info)
            ).installAfter(erasurePhase)
            outerParamDefs(constr)
      else Nil

    /** For all statements in stats: given a retained inline method and
     *  its retainedBody method such as
     *
     *     inline override def f(x: T) = body1
     *     private def f$retainedBody(x: T) = body2
     *
     *  return the runtime version of `f` as
     *
     *     override def f(x: T) = body2
     *
     *  Here, the owner of body2 is changed to f and all references
     *  to parameters of f$retainedBody are changed to references of
     *  corresponding parameters in f.
     *
     *  `f$retainedBody` is subseqently mapped to the empty tree in `typedDefDef`
     *  which is then dropped in `typedStats`.
     */
    private def addRetainedInlineBodies(stats: List[untpd.Tree])(using Context): List[untpd.Tree] =
      lazy val retainerDef: Map[Symbol, DefDef] = stats.collect {
        case stat: DefDef if stat.symbol.name.is(BodyRetainerName) =>
          val retainer = stat.symbol
          val origName = retainer.name.asTermName.exclude(BodyRetainerName)
          val inlineMeth = ctx.atPhase(ctx.typerPhase) {
            retainer.owner.info.decl(origName)
              .matchingDenotation(retainer.owner.thisType, stat.symbol.info)
              .symbol
          }
          (inlineMeth, stat)
      }.toMap
      stats.mapConserve {
        case stat: DefDef if stat.symbol.isRetainedInlineMethod =>
          val rdef = retainerDef(stat.symbol)
          val fromParams = untpd.allParamSyms(rdef)
          val toParams = untpd.allParamSyms(stat)
          assert(fromParams.hasSameLengthAs(toParams))
          val mapBody = TreeTypeMap(
            oldOwners = rdef.symbol :: Nil,
            newOwners = stat.symbol :: Nil,
            substFrom = fromParams,
            substTo   = toParams)
          cpy.DefDef(stat)(rhs = mapBody.transform(rdef.rhs))
        case stat => stat
      }

    override def typedClosure(tree: untpd.Closure, pt: Type)(using Context): Tree = {
      val xxl = defn.isXXLFunctionClass(tree.typeOpt.typeSymbol)
      var implClosure = super.typedClosure(tree, pt).asInstanceOf[Closure]
      if (xxl) implClosure = cpy.Closure(implClosure)(tpt = TypeTree(defn.FunctionXXLClass.typeRef))
      adaptClosure(implClosure)
    }

    override def typedTypeDef(tdef: untpd.TypeDef, sym: Symbol)(using Context): Tree =
      EmptyTree

    override def typedAnnotated(tree: untpd.Annotated, pt: Type)(using Context): Tree =
      typed(tree.arg, pt)

    override def typedStats(stats: List[untpd.Tree], exprOwner: Symbol)(using Context): (List[Tree], Context) = {
      val stats0 = addRetainedInlineBodies(stats)(using preErasureCtx)
      val stats1 =
        if (takesBridges(ctx.owner)) new Bridges(ctx.owner.asClass, erasurePhase).add(stats0)
        else stats0
      val (stats2, finalCtx) = super.typedStats(stats1, exprOwner)
      (stats2.filter(!_.isEmpty), finalCtx)
    }

    override def adapt(tree: Tree, pt: Type, locked: TypeVars)(using Context): Tree =
      trace(i"adapting ${tree.showSummary}: ${tree.tpe} to $pt", show = true) {
        if ctx.phase != ctx.erasurePhase && ctx.phase != ctx.erasurePhase.next then
          // this can happen when reading annotations loaded during erasure,
          // since these are loaded at phase typer.
          adapt(tree, pt, locked)(using ctx.withPhase(ctx.erasurePhase.next))
        else if (tree.isEmpty) tree
        else if (ctx.mode is Mode.Pattern) tree // TODO: replace with assertion once pattern matcher is active
        else adaptToType(tree, pt)
      }

    override def simplify(tree: Tree, pt: Type, locked: TypeVars)(using Context): tree.type = tree
  }

  private def takesBridges(sym: Symbol)(using Context): Boolean =
    sym.isClass && !sym.isOneOf(Flags.Trait | Flags.Package)
}
