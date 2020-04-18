package dotty.tools
package dotc
package typer

import ast.{TreeInfo, tpd, _}
import Trees._
import core._
import Flags._
import Symbols._
import Types._
import Decorators._
import Constants._
import StagingContext._
import StdNames._
import transform.SymUtils._
import Contexts.{Context, inContext, ctx}
import Names.{Name, TermName}
import NameKinds.{InlineAccessorName, InlineBinderName, InlineScrutineeName, BodyRetainerName}
import ProtoTypes.selectionProto
import SymDenotations.SymDenotation
import Inferencing.isFullyDefined
import config.Printers.inlining
import ErrorReporting.errorTree
import dotty.tools.dotc.tastyreflect.ReflectionImpl
import dotty.tools.dotc.util.{SimpleIdentityMap, SimpleIdentitySet, SourceFile, SourcePosition}
import dotty.tools.dotc.parsing.Parsers.Parser
import Nullables.{given _}

import collection.mutable
import reporting.trace
import util.Spans.Span
import dotty.tools.dotc.transform.{Splicer, TreeMapWithStages}

object Inliner {
  import tpd._

  /** `sym` is an inline method with a known body to inline.
   */
  def hasBodyToInline(sym: SymDenotation)(using Context): Boolean =
    sym.isInlineMethod && sym.hasAnnotation(defn.BodyAnnot)

  /** The body to inline for method `sym`, or `EmptyTree` if none exists.
   *  @pre  hasBodyToInline(sym)
   */
  def bodyToInline(sym: SymDenotation)(using Context): Tree =
    if (sym.isInlineMethod && sym.hasAnnotation(defn.BodyAnnot))
      sym.getAnnotation(defn.BodyAnnot).get.tree
    else
      EmptyTree

  /** Should call to method `meth` be inlined in this context? */
  def isInlineable(meth: Symbol)(using Context): Boolean =
    meth.is(Inline) && meth.hasAnnotation(defn.BodyAnnot) && !ctx.inInlineMethod

  /** Should call be inlined in this context? */
  def isInlineable(tree: Tree)(using Context): Boolean = tree match {
    case Block(_, expr) => isInlineable(expr)
    case _ => isInlineable(tree.symbol) && !tree.tpe.isInstanceOf[MethodOrPoly]
  }

  /** Try to inline a call to an inline method. Fail with error if the maximal
   *  inline depth is exceeded.
   *
   *  @param tree   The call to inline
   *  @param pt     The expected type of the call.
   *  @return   An `Inlined` node that refers to the original call and the inlined bindings
   *            and body that replace it.
   */
  def inlineCall(tree: Tree)(using Context): Tree = {
    if tree.symbol.denot != SymDenotations.NoDenotation && tree.symbol.owner.companionModule == defn.CompiletimeTestingPackageObject
      if (tree.symbol == defn.CompiletimeTesting_typeChecks) return Intrinsics.typeChecks(tree)
      if (tree.symbol == defn.CompiletimeTesting_typeCheckErrors) return Intrinsics.typeCheckErrors(tree)

   /** Set the position of all trees logically contained in the expansion of
    *  inlined call `call` to the position of `call`. This transform is necessary
    *  when lifting bindings from the expansion to the outside of the call.
    */
    def liftFromInlined(call: Tree) = new TreeMap {
      override def transform(t: Tree)(using Context) =
        t match {
          case Inlined(t, Nil, expr) if t.isEmpty => expr
          case _ => super.transform(t.withSpan(call.span))
        }
    }

    val bindings = new mutable.ListBuffer[Tree]

    /** Lift bindings around inline call or in its function part to
     *  the `bindings` buffer. This is done as an optimization to keep
     *  inline call expansions smaller.
     */
    def liftBindings(tree: Tree, liftPos: Tree => Tree): Tree = tree match {
      case Block(stats, expr) =>
        bindings ++= stats.map(liftPos)
        liftBindings(expr, liftPos)
      case Inlined(call, stats, expr) =>
        bindings ++= stats.map(liftPos)
        val lifter = liftFromInlined(call)
        cpy.Inlined(tree)(call, Nil, liftBindings(expr, liftFromInlined(call).transform(_)))
      case Apply(fn, args) =>
        cpy.Apply(tree)(liftBindings(fn, liftPos), args)
      case TypeApply(fn, args) =>
        cpy.TypeApply(tree)(liftBindings(fn, liftPos), args)
      case Select(qual, name) =>
        cpy.Select(tree)(liftBindings(qual, liftPos), name)
      case _ =>
        tree
    }

    val tree1 = liftBindings(tree, identity)
    if (bindings.nonEmpty)
      cpy.Block(tree)(bindings.toList, inlineCall(tree1))
    else if (enclosingInlineds.length < ctx.settings.XmaxInlines.value) {
      val body = bodyToInline(tree.symbol) // can typecheck the tree and thereby produce errors
      new Inliner(tree, body).inlined(tree.sourcePos)
    }
    else
      errorTree(
        tree,
        i"""|Maximal number of successive inlines (${ctx.settings.XmaxInlines.value}) exceeded,
            |Maybe this is caused by a recursive inline method?
            |You can use -Xmax-inlines to change the limit.""",
        (tree :: enclosingInlineds).last.sourcePos
      )
  }

  /** Try to inline a pattern with an inline unapply method. Fail with error if the maximal
   *  inline depth is exceeded.
   *
   *  @param unapp   The tree of the pattern to inline
   *  @return   An `Unapply` with a `fun` containing the inlined call to the unapply
   */
  def inlinedUnapply(unapp: tpd.UnApply)(using Context): Tree = {
    // We cannot inline the unapply directly, since the pattern matcher relies on unapply applications
    // as signposts what to do. On the other hand, we can do the inlining only in typer, not afterwards.
    // So the trick is to create a "wrapper" unapply in an anonymous class that has the inlined unapply
    // as its right hand side. The call to the wrapper unapply serves as the signpost for pattern matching.
    // After pattern matching, the anonymous class is removed in phase InlinePatterns with a beta reduction step.
    //
    // An inline unapply `P.unapply` in a plattern `P(x1,x2,...)` is transformed into
    // `{ class $anon { def unapply(t0: T0)(using t1: T1, t2: T2, ...): R = P.unapply(t0)(using t1, t2, ...) }; new $anon }.unapply`
    // and the call `P.unapply(x1, x2, ...)` is inlined.
    // This serves as a placeholder for the inlined body until the `patternMatcher` phase. After pattern matcher
    // transforms the patterns into terms, the `inlinePatterns` phase removes this anonymous class by β-reducing
    // the call to the `unapply`.

    val UnApply(fun, implicits, patterns) = unapp
    val sym = unapp.symbol
    val cls = ctx.newNormalizedClassSymbol(ctx.owner, tpnme.ANON_CLASS, Synthetic | Final, List(defn.ObjectType), coord = sym.coord)
    val constr = ctx.newConstructor(cls, Synthetic, Nil, Nil, coord = sym.coord).entered

    val targs = fun match
      case TypeApply(_, targs) => targs
      case _ => Nil
    val unapplyInfo = sym.info match
      case info: PolyType => info.instantiate(targs.map(_.tpe))
      case info => info

    val unappplySym = ctx.newSymbol(cls, sym.name.toTermName, Synthetic | Method, unapplyInfo, coord = sym.coord).entered
    val unapply = DefDef(unappplySym, argss =>
      inlineCall(fun.appliedToArgss(argss).withSpan(unapp.span))(using ctx.withOwner(unappplySym))
    )
    val cdef = ClassDef(cls, DefDef(constr), List(unapply))
    val newUnapply = Block(cdef :: Nil, New(cls.typeRef, Nil))
    val newFun = newUnapply.select(unappplySym).withSpan(unapp.span)
    cpy.UnApply(unapp)(newFun, implicits, patterns)
  }

  /** For a retained inline method, another method that keeps track of
   *  the body that is kept at runtime. For instance, an inline method
   *
   *      inline override def f(x: T) = b
   *
   *  is complemented by the body retainer method
   *
   *      private def f$retainedBody(x: T) = f(x)
   *
   *  where the call `f(x)` is inline-expanded. This body is then transferred
   *  back to `f` at erasure, using method addRetainedInlineBodies.
   */
  def bodyRetainer(mdef: DefDef)(using Context): DefDef =
    val meth = mdef.symbol.asTerm

    val retainer = meth.copy(
      name = BodyRetainerName(meth.name),
      flags = meth.flags &~ (Inline | Override) | Private,
      coord = mdef.rhs.span.startPos).asTerm
    polyDefDef(retainer, targs => prefss =>
      inlineCall(
        ref(meth).appliedToTypes(targs).appliedToArgss(prefss)
          .withSpan(mdef.rhs.span.startPos))(
        using ctx.withOwner(retainer)))
    .reporting(i"retainer for $meth: $result", inlining)

  /** Replace `Inlined` node by a block that contains its bindings and expansion */
  def dropInlined(inlined: Inlined)(using Context): Tree =
    val tree1 =
      if inlined.bindings.isEmpty then inlined.expansion
      else cpy.Block(inlined)(inlined.bindings, inlined.expansion)
    // Reposition in the outer most inlined call
    if (enclosingInlineds.nonEmpty) tree1 else reposition(tree1, inlined.span)

  def reposition(tree: Tree, callSpan: Span)(using Context): Tree = {
    // Reference test tests/run/i4947b

    val curSource = ctx.compilationUnit.source

    // Tree copier that changes the source of all trees to `curSource`
    val cpyWithNewSource = new TypedTreeCopier {
      override protected def sourceFile(tree: tpd.Tree): SourceFile = curSource
      override protected val untpdCpy: untpd.UntypedTreeCopier = new untpd.UntypedTreeCopier {
        override protected def sourceFile(tree: untpd.Tree): SourceFile = curSource
      }
    }

    /** Removes all Inlined trees, replacing them with blocks.
     *  Repositions all trees directly inside an inlined expansion of a non empty call to the position of the call.
     *  Any tree directly inside an empty call (inlined in the inlined code) retains their position.
     *
     *  Until we implement JSR-45, we cannot represent in output positions in other source files.
     *  So, reposition inlined code from other files with the call position.
     */
    class Reposition extends TreeMap(cpyWithNewSource) {

      override def transform(tree: Tree)(using Context): Tree = {
        def finalize(copied: untpd.Tree) =
          val span = if tree.source == curSource then tree.span else callSpan
          copied.withSpan(span).withAttachmentsFrom(tree).withTypeUnchecked(tree.tpe)

        inContext(ctx.withSource(curSource)) {
          tree match
            case tree: Ident => finalize(untpd.Ident(tree.name)(curSource))
            case tree: Literal => finalize(untpd.Literal(tree.const)(curSource))
            case tree: This => finalize(untpd.This(tree.qual)(curSource))
            case tree: JavaSeqLiteral => finalize(untpd.JavaSeqLiteral(transform(tree.elems), transform(tree.elemtpt))(curSource))
            case tree: SeqLiteral => finalize(untpd.SeqLiteral(transform(tree.elems), transform(tree.elemtpt))(curSource))
            case tree: Bind => finalize(untpd.Bind(tree.name, transform(tree.body))(curSource))
            case tree: TypeTree => finalize(tpd.TypeTree(tree.tpe))
            case tree: DefTree => super.transform(tree).setDefTree
            case _ => super.transform(tree)
        }
      }
    }

    (new Reposition).transform(tree)
  }

  /** Leave only a call trace consisting of
   *  - a reference to the top-level class from which the call was inlined,
   *  - the call's position
   *  in the call field of an Inlined node.
   *  The trace has enough info to completely reconstruct positions.
   *  Note: For macros it returns a Select and for other inline methods it returns an Ident (this distinction is only temporary to be able to run YCheckPositions)
   */
  def inlineCallTrace(callSym: Symbol, pos: SourcePosition)(using Context): Tree = {
    assert(ctx.source == pos.source)
    if (callSym.is(Macro)) ref(callSym.topLevelClass.owner).select(callSym.topLevelClass.name).withSpan(pos.span)
    else Ident(callSym.topLevelClass.typeRef).withSpan(pos.span)
  }

  object Intrinsics {
    import dotty.tools.dotc.reporting.Diagnostic.Error
    private enum ErrorKind:
      case Parser, Typer

    private def compileForErrors(tree: Tree, stopAfterParser: Boolean)(using Context): List[(ErrorKind, Error)] =
      assert(tree.symbol == defn.CompiletimeTesting_typeChecks || tree.symbol == defn.CompiletimeTesting_typeCheckErrors)
      def stripTyped(t: Tree): Tree = t match {
        case Typed(t2, _) => stripTyped(t2)
        case _ => t
      }

      val Apply(_, codeArg :: Nil) = tree
      val underlyingCodeArg = stripTyped(codeArg.underlying)
      ConstFold(underlyingCodeArg).tpe.widenTermRefExpr match {
        case ConstantType(Constant(code: String)) =>
          val source2 = SourceFile.virtual("tasty-reflect", code)
          val ctx2 = ctx.fresh.setNewTyperState().setTyper(new Typer).setSource(source2)
          val tree2 = new Parser(source2)(ctx2).block()
          val res = collection.mutable.ListBuffer.empty[(ErrorKind, Error)]

          val parseErrors = ctx2.reporter.allErrors.toList
          res ++= parseErrors.map(e => ErrorKind.Parser -> e)
          if !stopAfterParser || res.isEmpty
            ctx2.typer.typed(tree2)(using ctx2)
            val typerErrors = ctx2.reporter.allErrors.filterNot(parseErrors.contains)
            res ++= typerErrors.map(e => ErrorKind.Typer -> e)
          res.toList
        case t =>
          ctx.error("argument to compileError must be a statically known String", underlyingCodeArg.sourcePos)
          Nil
      }

    private def packError(kind: ErrorKind, error: Error)(using Context): Tree =
      def lit(x: Any) = Literal(Constant(x))
      val constructor: Tree = ref(defn.CompiletimeTesting_Error_apply)
      val parserErrorKind: Tree = ref(defn.CompiletimeTesting_ErrorKind_Parser)
      val typerErrorKind: Tree = ref(defn.CompiletimeTesting_ErrorKind_Typer)

      constructor.appliedTo(
        lit(error.message),
        lit(error.pos.lineContent.reverse.dropWhile("\n ".contains).reverse),
        lit(error.pos.column),
        if kind == ErrorKind.Parser then parserErrorKind else typerErrorKind)

    private def packErrors(errors: List[(ErrorKind, Error)])(using Context): Tree =
      val individualErrors: List[Tree] = errors.map(packError)
      val errorTpt = ref(defn.CompiletimeTesting_ErrorClass)
      mkList(individualErrors, errorTpt)

    /** Expand call to scala.compiletime.testing.typeChecks */
    def typeChecks(tree: Tree)(using Context): Tree =
      val errors = compileForErrors(tree, true)
      Literal(Constant(errors.isEmpty))

    /** Expand call to scala.compiletime.testing.typeCheckErrors */
    def typeCheckErrors(tree: Tree)(using Context): Tree =
      val errors = compileForErrors(tree, false)
      packErrors(errors)
  }
}

/** Produces an inlined version of `call` via its `inlined` method.
 *
 *  @param  call         the original call to an inlineable method
 *  @param  rhsToInline  the body of the inlineable method that replaces the call.
 */
class Inliner(call: tpd.Tree, rhsToInline: tpd.Tree)(using Context) {
  import tpd._
  import Inliner._

  private val (methPart, callTypeArgs, callValueArgss) = decomposeCall(call)
  private val inlinedMethod = methPart.symbol
  private val inlineCallPrefix =
     qualifier(methPart).orElse(This(inlinedMethod.enclosingClass.asClass))

  inlining.println(i"-----------------------\nInlining $call\nWith RHS $rhsToInline")

  // Make sure all type arguments to the call are fully determined,
  // but continue if that's not achievable (or else i7459.scala would crash).
  for arg <- callTypeArgs do
    isFullyDefined(arg.tpe, ForceDegree.flipBottom)

  /** A map from parameter names of the inlineable method to references of the actual arguments.
   *  For a type argument this is the full argument type.
   *  For a value argument, it is a reference to either the argument value
   *  (if the argument is a pure expression of singleton type), or to `val` or `def` acting
   *  as a proxy (if the argument is something else).
   */
  private val paramBinding = new mutable.HashMap[Name, Type]

  /** A map from parameter names of the inlineable method to spans of the actual arguments */
  private val paramSpan = new mutable.HashMap[Name, Span]

  /** A map from references to (type and value) parameters of the inlineable method
   *  to their corresponding argument or proxy references, as given by `paramBinding`.
   */
  private val paramProxy = new mutable.HashMap[Type, Type]

  /** A map from the classes of (direct and outer) this references in `rhsToInline`
   *  to references of their proxies.
   *  Note that we can't index by the ThisType itself since there are several
   *  possible forms to express what is logicaly the same ThisType. E.g.
   *
   *     ThisType(TypeRef(ThisType(p), cls))
   *
   *  vs
   *
   *     ThisType(TypeRef(TermRef(ThisType(<root>), p), cls))
   *
   *  These are different (wrt ==) types but represent logically the same key
   */
  private val thisProxy = new mutable.HashMap[ClassSymbol, TermRef]

  /** A buffer for bindings that define proxies for actual arguments */
  private val bindingsBuf = new mutable.ListBuffer[ValOrDefDef]

  private def newSym(name: Name, flags: FlagSet, info: Type)(using Context): Symbol =
    ctx.newSymbol(ctx.owner, name, flags, info, coord = call.span)

  /** A binding for the parameter of an inline method. This is a `val` def for
   *  by-value parameters and a `def` def for by-name parameters. `val` defs inherit
   *  inline annotations from their parameters. The generated `def` is appended
   *  to `bindingsBuf`.
   *  @param name        the name of the parameter
   *  @param paramtp     the type of the parameter
   *  @param arg         the argument corresponding to the parameter
   *  @param bindingsBuf the buffer to which the definition should be appended
   */
  private def paramBindingDef(name: Name, paramtp: Type, arg0: Tree,
                              bindingsBuf: mutable.ListBuffer[ValOrDefDef])(using Context): ValOrDefDef = {
    val arg = arg0 match {
      case Typed(arg1, tpt) if tpt.tpe.isRepeatedParam && arg1.tpe.derivesFrom(defn.ArrayClass) =>
        wrapArray(arg1, arg0.tpe.elemType)
      case _ => arg0
    }
    val argtpe = arg.tpe.dealiasKeepAnnots
    val isByName = paramtp.dealias.isInstanceOf[ExprType]
    var inlineFlags: FlagSet = InlineProxy
    if (paramtp.widenExpr.hasAnnotation(defn.InlineParamAnnot)) inlineFlags |= Inline
    if (isByName) inlineFlags |= Method
    val (bindingFlags, bindingType) =
      if (isByName) (inlineFlags, ExprType(argtpe.widen))
      else (inlineFlags, argtpe.widen)
    val boundSym = newSym(name, bindingFlags, bindingType).asTerm
    val binding = {
      if (isByName) DefDef(boundSym, arg.changeOwner(ctx.owner, boundSym))
      else ValDef(boundSym, arg)
    }.withSpan(boundSym.span)
    bindingsBuf += binding.setDefTree
    binding
  }

  /** Populate `paramBinding` and `bindingsBuf` by matching parameters with
   *  corresponding arguments. `bindingbuf` will be further extended later by
   *  proxies to this-references. Issue an error if some arguments are missing.
   */
  private def computeParamBindings(tp: Type, targs: List[Tree], argss: List[List[Tree]]): Boolean = tp match
    case tp: PolyType =>
      tp.paramNames.lazyZip(targs).foreach { (name, arg) =>
        paramSpan(name) = arg.span
        paramBinding(name) = arg.tpe.stripTypeVar
      }
      computeParamBindings(tp.resultType, Nil, argss)
    case tp: MethodType =>
      if argss.isEmpty then
        ctx.error(i"missing arguments for inline method $inlinedMethod", call.sourcePos)
        false
      else
        tp.paramNames.lazyZip(tp.paramInfos).lazyZip(argss.head).foreach { (name, paramtp, arg) =>
          paramSpan(name) = arg.span
          paramBinding(name) = arg.tpe.dealias match {
            case _: SingletonType if isIdempotentPath(arg) => arg.tpe
            case _ => paramBindingDef(name, paramtp, arg, bindingsBuf).symbol.termRef
          }
        }
        computeParamBindings(tp.resultType, targs, argss.tail)
    case _ =>
      assert(targs.isEmpty)
      assert(argss.isEmpty)
      true

  // Compute val-definitions for all this-proxies and append them to `bindingsBuf`
  private def computeThisBindings() = {
    // All needed this-proxies, paired-with and sorted-by nesting depth of
    // the classes they represent (innermost first)
    val sortedProxies = thisProxy.toList.map {
      case (cls, proxy) =>
        // The class that the this-proxy `selfSym` represents
        def classOf(selfSym: Symbol) = selfSym.info.widen.classSymbol
        // The total nesting depth of the class represented by `selfSym`.
        def outerLevel(selfSym: Symbol): Int = classOf(selfSym).ownersIterator.length
        (outerLevel(cls), proxy.symbol)
    }.sortBy(-_._1)

    var lastSelf: Symbol = NoSymbol
    var lastLevel: Int = 0
    for ((level, selfSym) <- sortedProxies) {
      lazy val rhsClsSym = selfSym.info.widenDealias.classSymbol
      val rhs =
        if (lastSelf.exists)
          ref(lastSelf).outerSelect(lastLevel - level, selfSym.info)
        else if (rhsClsSym.is(Module) && rhsClsSym.isStatic)
          ref(rhsClsSym.sourceModule)
        else
          inlineCallPrefix
      val binding = ValDef(selfSym.asTerm, rhs).withSpan(selfSym.span).setDefTree
      bindingsBuf += binding
      inlining.println(i"proxy at $level: $selfSym = ${bindingsBuf.last}")
      lastSelf = selfSym
      lastLevel = level
    }
  }

  private def canElideThis(tpe: ThisType): Boolean =
    inlineCallPrefix.tpe == tpe && ctx.owner.isContainedIn(tpe.cls) ||
    tpe.cls.isContainedIn(inlinedMethod) ||
    tpe.cls.is(Package)

  /** Very similar to TreeInfo.isPureExpr, but with the following inliner-only exceptions:
   *  - synthetic case class apply methods, when the case class constructor is empty, are
   *    elideable but not pure. Elsewhere, accessing the apply method might cause the initialization
   *    of a containing object so they are merely idempotent.
   */
  object isElideableExpr {
    def isStatElideable(tree: Tree)(implicit ctx: Context): Boolean = unsplice(tree) match {
      case EmptyTree
         | TypeDef(_, _)
         | Import(_, _)
         | DefDef(_, _, _, _, _) =>
        true
      case vdef @ ValDef(_, _, _) =>
        if (vdef.symbol.flags is Mutable) false else apply(vdef.rhs)
      case _ =>
        false
    }

    def apply(tree: Tree): Boolean = unsplice(tree) match {
      case EmptyTree
         | This(_)
         | Super(_, _)
         | Literal(_) =>
        true
      case Ident(_) =>
        isPureRef(tree)
      case Select(qual, _) =>
        if (tree.symbol.is(Erased)) true
        else isPureRef(tree) && apply(qual)
      case New(_) | Closure(_, _, _) =>
        true
      case TypeApply(fn, _) =>
        if (fn.symbol.is(Erased) || fn.symbol == defn.InternalQuoted_typeQuote) true else apply(fn)
      case Apply(fn, args) =>
        def isKnownPureOp(sym: Symbol) =
          sym.owner.isPrimitiveValueClass
          || sym.owner == defn.StringClass
          || defn.pureMethods.contains(sym)
        val isCaseClassApply = {
          val cls = tree.tpe.classSymbol
          val meth = fn.symbol
          meth.name == nme.apply &&
          meth.flags.is(Synthetic) &&
          meth.owner.linkedClass.is(Case) &&
          cls.isNoInitsClass
        }
        if (tree.tpe.isInstanceOf[ConstantType] && isKnownPureOp(tree.symbol) // A constant expression with pure arguments is pure.
            || (fn.symbol.isStableMember && !fn.symbol.is(Lazy))
            || fn.symbol.isPrimaryConstructor && fn.symbol.owner.isNoInitsClass) // TODO: include in isStable?
          apply(fn) && args.forall(apply)
        else if (isCaseClassApply)
          args.forall(apply)
        else if (fn.symbol.is(Erased)) true
        else false
      case Typed(expr, _) =>
        apply(expr)
      case Block(stats, expr) =>
        apply(expr) && stats.forall(isStatElideable)
      case Inlined(_, bindings, expr) =>
        apply(expr) && bindings.forall(isStatElideable)
      case NamedArg(_, expr) =>
        apply(expr)
      case _ =>
        false
      }
  }

  /** Populate `thisProxy` and `paramProxy` as follows:
   *
   *  1a. If given type refers to a static this, thisProxy binds it to corresponding global reference,
   *  1b. If given type refers to an instance this to a class that is not contained in the
   *      inline method, create a proxy symbol and bind the thistype to refer to the proxy.
   *      The proxy is not yet entered in `bindingsBuf`; that will come later.
   *  2.  If given type refers to a parameter, make `paramProxy` refer to the entry stored
   *      in `paramNames` under the parameter's name. This roundabout way to bind parameter
   *      references to proxies is done because  we don't know a priori what the parameter
   *      references of a method are (we only know the method's type, but that contains TypeParamRefs
   *      and MethodParams, not TypeRefs or TermRefs.
   */
  private def registerType(tpe: Type): Unit = tpe match {
    case tpe: ThisType if !canElideThis(tpe) && !thisProxy.contains(tpe.cls) =>
      val proxyName = s"${tpe.cls.name}_this".toTermName
      def adaptToPrefix(tp: Type) = tp.asSeenFrom(inlineCallPrefix.tpe, inlinedMethod.owner)
      val proxyType = inlineCallPrefix.tpe.dealias.tryNormalize match {
        case typeMatchResult if typeMatchResult.exists => typeMatchResult
        case _ => adaptToPrefix(tpe).widenIfUnstable
      }
      thisProxy(tpe.cls) = newSym(proxyName, InlineProxy, proxyType).termRef
      if (!tpe.cls.isStaticOwner)
        registerType(inlinedMethod.owner.thisType) // make sure we have a base from which to outer-select
      for (param <- tpe.cls.typeParams)
        paramProxy(param.typeRef) = adaptToPrefix(param.typeRef)
    case tpe: NamedType
    if tpe.symbol.is(Param) && tpe.symbol.owner == inlinedMethod && !paramProxy.contains(tpe) =>
      paramBinding.get(tpe.name) match
        case Some(bound) => paramProxy(tpe) = bound
        case _ =>  // can happen for params bound by type-lambda trees.
    case _ =>
  }

  /** Register type of leaf node */
  private def registerLeaf(tree: Tree): Unit = tree match {
    case _: This | _: Ident | _: TypeTree =>
      tree.typeOpt.foreachPart(registerType, stopAtStatic = true)
    case _ =>
  }

  /** Make `tree` part of inlined expansion. This means its owner has to be changed
   *  from its `originalOwner`, and, if it comes from outside the inlined method
   *  itself, it has to be marked as an inlined argument.
   */
  def integrate(tree: Tree, originalOwner: Symbol)(using Context): Tree =
    tree.changeOwner(originalOwner, ctx.owner)

  def tryConstValue: Tree =
    ctx.typeComparer.constValue(callTypeArgs.head.tpe) match {
      case Some(c) => Literal(c).withSpan(call.span)
      case _ => EmptyTree
    }

  /** The Inlined node representing the inlined call */
  def inlined(sourcePos: SourcePosition): Tree = {

  	// Special handling of `constValue[T]` and `constValueOpt[T]`
    if (callTypeArgs.length == 1)
      if (inlinedMethod == defn.Compiletime_constValue) {
        val constVal = tryConstValue
        if (!constVal.isEmpty) return constVal
        ctx.error(em"not a constant type: ${callTypeArgs.head}; cannot take constValue", call.sourcePos)
      }
      else if (inlinedMethod == defn.Compiletime_constValueOpt) {
        val constVal = tryConstValue
        return (
          if (constVal.isEmpty) ref(defn.NoneModule.termRef)
          else New(defn.SomeClass.typeRef.appliedTo(constVal.tpe), constVal :: Nil)
        )
      }

    // Compute bindings for all parameters, appending them to bindingsBuf
    if !computeParamBindings(inlinedMethod.info, callTypeArgs, callValueArgss) then
      return call

    // make sure prefix is executed if it is impure
    if (!isIdempotentExpr(inlineCallPrefix)) registerType(inlinedMethod.owner.thisType)

    // Register types of all leaves of inlined body so that the `paramProxy` and `thisProxy` maps are defined.
    rhsToInline.foreachSubTree(registerLeaf)

    // Compute bindings for all this-proxies, appending them to bindingsBuf
    computeThisBindings()

    val inlineTyper = new InlineTyper(ctx.reporter.errorCount)

    val inlineCtx = inlineContext(call).fresh.setTyper(inlineTyper).setNewScope

    def inlinedFromOutside(tree: Tree)(span: Span): Tree =
      Inlined(EmptyTree, Nil, tree)(ctx.withSource(inlinedMethod.topLevelClass.source)).withSpan(span)

    // A tree type map to prepare the inlined body for typechecked.
    // The translation maps references to `this` and parameters to
    // corresponding arguments or proxies on the type and term level. It also changes
    // the owner from the inlined method to the current owner.
    val inliner = new TreeTypeMap(
      typeMap =
        new TypeMap {
          def apply(t: Type) = t match {
            case t: ThisType => thisProxy.getOrElse(t.cls, t)
            case t: TypeRef => paramProxy.getOrElse(t, mapOver(t))
            case t: SingletonType => paramProxy.getOrElse(t, mapOver(t))
            case t => mapOver(t)
          }
          override def mapClassInfo(tp: ClassInfo) = mapFullClassInfo(tp)
        },
      treeMap = {
        case tree: This =>
          tree.tpe match {
            case thistpe: ThisType =>
              thisProxy.get(thistpe.cls) match {
                case Some(t) =>
                  val thisRef = ref(t).withSpan(call.span)
                  inlinedFromOutside(thisRef)(tree.span)
                case None => tree
              }
            case _ => tree
          }
        case tree: Ident =>
          /* Span of the argument. Used when the argument is inlined directly without a binding */
          def argSpan =
            if (tree.name == nme.WILDCARD) tree.span // From type match
            else if (tree.symbol.isTypeParam && tree.symbol.owner.isClass) tree.span // TODO is this the correct span?
            else paramSpan(tree.name)
          val inlinedCtx = ctx.withSource(inlinedMethod.topLevelClass.source)
          paramProxy.get(tree.tpe) match {
            case Some(t) if tree.isTerm && t.isSingleton =>
              val inlinedSingleton = singleton(t).withSpan(argSpan)
              inlinedFromOutside(inlinedSingleton)(tree.span)
            case Some(t) if tree.isType =>
              TypeTree(t).withSpan(argSpan)
            case _ => tree
          }
        case tree => tree
      },
      oldOwners = inlinedMethod :: Nil,
      newOwners = ctx.owner :: Nil
    )(inlineCtx)

    // Apply inliner to `rhsToInline`, split off any implicit bindings from result, and
    // make them part of `bindingsBuf`. The expansion is then the tree that remains.
    val expansion = inliner.transform(rhsToInline)

    def issueError() = callValueArgss match {
      case (msgArg :: Nil) :: Nil =>
        val message = msgArg.tpe match {
          case ConstantType(Constant(msg: String)) => msg
          case _ => s"A literal string is expected as an argument to `compiletime.error`. Got ${msgArg.show}"
        }
        // Usually `error` is called from within a rewrite method. In this
        // case we need to report the error at the point of the outermost enclosing inline
        // call. This way, a defensively written rewrite methid can always
        // report bad inputs at the point of call instead of revealing its internals.
        val callToReport = if (enclosingInlineds.nonEmpty) enclosingInlineds.last else call
        val ctxToReport = ctx.outersIterator.dropWhile(enclosingInlineds(_).nonEmpty).next
        inContext(ctxToReport) {
          ctx.error(message, callToReport.sourcePos)
        }
      case _ =>
    }

    def issueCode()(using Context): Literal = {
      def decompose(arg: Tree): String = arg match {
        case Typed(arg, _) => decompose(arg)
        case SeqLiteral(elems, _) => elems.map(decompose).mkString(", ")
        case Block(Nil, expr) => decompose(expr)
        case Inlined(_, Nil, expr) => decompose(expr)
        case arg =>
          arg.tpe.widenTermRefExpr match {
            case ConstantType(Constant(c)) => c.toString
            case _ => arg.show
          }
      }

      def malformedString(): String = {
        ctx.error("Malformed part `code` string interpolator", call.sourcePos)
        ""
      }

      callValueArgss match {
        case List(List(Apply(_,List(Typed(SeqLiteral(Literal(headConst) :: parts,_),_)))), List(Typed(SeqLiteral(interpolatedParts,_),_)))
            if parts.size == interpolatedParts.size =>
          val constantParts = parts.map {
            case Literal(const) => const.stringValue
            case _ => malformedString()
          }
          val decomposedInterpolations = interpolatedParts.map(decompose)
          val constantString = decomposedInterpolations.zip(constantParts)
              .foldLeft(headConst.stringValue) { case (acc, (p1, p2)) => acc + p1 + p2 }

          Literal(Constant(constantString)).withSpan(call.span)
        case _ =>
          Literal(Constant(malformedString()))
      }
    }

    trace(i"inlining $call", inlining, show = true) {

      // The normalized bindings collected in `bindingsBuf`
      bindingsBuf.mapInPlace { binding =>
        // Set trees to symbols allow macros to see the definition tree.
        // This is used by `underlyingArgument`.
        reducer.normalizeBinding(binding)(using inlineCtx).setDefTree
      }

      // Run a typing pass over the inlined tree. See InlineTyper for details.
      val expansion1 = inlineTyper.typed(expansion)(using inlineCtx)

      if (ctx.settings.verbose.value) {
        inlining.println(i"to inline = $rhsToInline")
        inlining.println(i"original bindings = ${bindingsBuf.toList}%\n%")
        inlining.println(i"original expansion = $expansion1")
      }

      // Drop unused bindings
      val (finalBindings, finalExpansion) = dropUnusedDefs(bindingsBuf.toList, expansion1)

      if (inlinedMethod == defn.Compiletime_error) issueError()

      if (inlinedMethod == defn.Compiletime_code)
        issueCode()(using ctx.fresh.setSetting(ctx.settings.color, "never"))
      else
        // Take care that only argument bindings go into `bindings`, since positions are
        // different for bindings from arguments and bindings from body.
        tpd.Inlined(call, finalBindings, finalExpansion)
    }
  }

  /** A utility object offering methods for rewriting inlined code */
  object reducer {

    /** An extractor for terms equivalent to `new C(args)`, returning the class `C`,
     *  a list of bindings, and the arguments `args`. Can see inside blocks and Inlined nodes and can
     *  follow a reference to an inline value binding to its right hand side.
     *
     *  @return    optionally, a triple consisting of
     *             - the class `C`
     *             - the arguments `args`
     *             - any bindings that wrap the instance creation
     *             - whether the instance creation is precomputed or by-name
     */
    private object NewInstance {
      def unapply(tree: Tree)(using Context): Option[(Symbol, List[Tree], List[Tree], Boolean)] = {
        def unapplyLet(bindings: List[Tree], expr: Tree) =
          unapply(expr) map {
            case (cls, reduced, prefix, precomputed) => (cls, reduced, bindings ::: prefix, precomputed)
          }
        tree match {
          case Apply(fn, args) =>
            fn match {
              case Select(New(tpt), nme.CONSTRUCTOR) =>
                Some((tpt.tpe.classSymbol, args, Nil, false))
              case TypeApply(Select(New(tpt), nme.CONSTRUCTOR), _) =>
                Some((tpt.tpe.classSymbol, args, Nil, false))
              case _ =>
                val meth = fn.symbol
                if (meth.name == nme.apply &&
                    meth.flags.is(Synthetic) &&
                    meth.owner.linkedClass.is(Case))
                  Some(meth.owner.linkedClass, args, Nil, false)
                else None
            }
          case Typed(inner, _) =>
            // drop the ascribed tpt. We only need it if we can't find a NewInstance
            unapply(inner)
          case Ident(_) =>
            val binding = tree.symbol.defTree
            for ((cls, reduced, prefix, precomputed) <- unapply(binding))
            yield (cls, reduced, prefix, precomputed || binding.isInstanceOf[ValDef])
          case Inlined(_, bindings, expansion) =>
            unapplyLet(bindings, expansion)
          case Block(stats, expr) if isElideableExpr(tree) =>
            unapplyLet(stats, expr)
          case _ =>
            None
        }
      }
    }

    /** If `tree` is equivalent to `new C(args).x` where class `C` does not have
     *  initialization code and `x` is a parameter corresponding to one of the
     *  arguments `args`, the corresponding argument, otherwise `tree` itself.
     *  Side effects of original arguments need to be preserved.
     */
    def reduceProjection(tree: Tree)(using Context): Tree = {
      if (ctx.debug) inlining.println(i"try reduce projection $tree")
      tree match {
        case Select(NewInstance(cls, args, prefix, precomputed), field) if cls.isNoInitsClass =>
          def matches(param: Symbol, selection: Symbol): Boolean =
            param == selection || {
              selection.name match {
                case InlineAccessorName(underlying) =>
                  param.name == underlying && selection.info.isInstanceOf[ExprType]
                case _ =>
                  false
              }
            }
          val idx = cls.asClass.paramAccessors.indexWhere(matches(_, tree.symbol))
          if (idx >= 0 && idx < args.length) {
            def finish(arg: Tree) =
              new TreeTypeMap().transform(arg) // make sure local bindings in argument have fresh symbols
                .reporting(i"projecting $tree -> $result", inlining)
            val arg = args(idx)
            if (precomputed)
              if (isElideableExpr(arg)) finish(arg)
              else tree // nothing we can do here, projection would duplicate side effect
            else {
              // newInstance is evaluated in place, need to reflect side effects of
              // arguments in the order they were written originally
              def collectImpure(from: Int, end: Int) =
                (from until end).filterNot(i => isElideableExpr(args(i))).toList.map(args)
              val leading = collectImpure(0, idx)
              val trailing = collectImpure(idx + 1, args.length)
              val argInPlace =
                if (trailing.isEmpty) arg
                else letBindUnless(TreeInfo.Pure, arg)(seq(trailing, _))
              finish(seq(prefix, seq(leading, argInPlace)))
            }
          }
          else tree
        case Block(stats, expr) if stats.forall(isPureBinding) =>
          cpy.Block(tree)(stats, reduceProjection(expr))
        case _ => tree
      }
    }

    /** If this is a value binding:
     *   - reduce its rhs if it is a projection and adjust its type accordingly,
     *   - record symbol -> rhs in the InlineBindings context propery.
     */
    def normalizeBinding(binding: ValOrDefDef)(using Context) = {
      val binding1 = binding match {
        case binding: ValDef =>
          val rhs1 = reduceProjection(binding.rhs)
          binding.symbol.defTree = rhs1
          if (rhs1 `eq` binding.rhs) binding
          else {
            binding.symbol.info = rhs1.tpe
            cpy.ValDef(binding)(tpt = TypeTree(rhs1.tpe), rhs = rhs1)
          }
        case _ =>
          binding
      }
      binding1.withSpan(call.span)
    }

    /** An extractor for references to inlineable arguments. These are :
     *   - by-value arguments marked with `inline`
     *   - all by-name arguments
     */
    private object InlineableArg {
      lazy val paramProxies = paramProxy.values.toSet
      def unapply(tree: Trees.Ident[?])(using Context): Option[Tree] = {
        def search(buf: mutable.ListBuffer[ValOrDefDef]) = buf.find(_.name == tree.name)
        if (paramProxies.contains(tree.typeOpt))
          search(bindingsBuf) match {
            case Some(bind: ValOrDefDef) if bind.symbol.is(Inline) =>
              Some(integrate(bind.rhs, bind.symbol))
            case _ => None
          }
        else None
      }
    }

    object ConstantValue {
      def unapply(tree: Tree)(using Context): Option[Any] = tree.tpe.widenTermRefExpr.normalized match {
        case ConstantType(Constant(x)) => Some(x)
        case _ => None
      }
    }

    def tryInline(tree: Tree)(using Context): Tree = tree match {
      case InlineableArg(rhs) =>
        inlining.println(i"inline arg $tree -> $rhs")
        rhs
      case _ =>
        EmptyTree
    }

    /** Rewrite an application
      *
      *    ((x1, ..., xn) => b)(e1, ..., en)
      *
      *  to
      *
      *    val/def x1 = e1; ...; val/def xn = en; b
      *
      *  where `def` is used for call-by-name parameters. However, we shortcut any NoPrefix
      *  refs among the ei's directly without creating an intermediate binding.
      */
    def betaReduce(tree: Tree)(using Context): Tree = tree match {
      case Apply(Select(cl @ closureDef(ddef), nme.apply), args) if defn.isFunctionType(cl.tpe) =>
        ddef.tpe.widen match {
          case mt: MethodType if ddef.vparamss.head.length == args.length =>
            val bindingsBuf = new mutable.ListBuffer[ValOrDefDef]
            val argSyms = mt.paramNames.lazyZip(mt.paramInfos).lazyZip(args).map { (name, paramtp, arg) =>
              arg.tpe.dealias match {
                case ref @ TermRef(NoPrefix, _) => ref.symbol
                case _ =>
                  paramBindingDef(name, paramtp, arg, bindingsBuf)(
                    using ctx.withSource(cl.source)
                  ).symbol
              }
            }
            val expander = new TreeTypeMap(
              oldOwners = ddef.symbol :: Nil,
              newOwners = ctx.owner :: Nil,
              substFrom = ddef.vparamss.head.map(_.symbol),
              substTo = argSyms)
            Block(bindingsBuf.toList, expander.transform(ddef.rhs))
          case _ => tree
        }
      case _ => tree
    }

    /** The result type of reducing a match. It consists optionally of a list of bindings
     *  for the pattern-bound variables and the RHS of the selected case.
     *  Returns `None` if no case was selected.
     */
    type MatchRedux = Option[(List[MemberDef], Tree)]

    /** Reduce an inline match
     *   @param     mtch          the match tree
     *   @param     scrutinee     the scrutinee expression, assumed to be pure, or
     *                            EmptyTree for a summonFrom
     *   @param     scrutType     its fully defined type, or
     *                            ImplicitScrutineeTypeRef for a summonFrom
     *   @param     typer         The current inline typer
     *   @return    optionally, if match can be reduced to a matching case: A pair of
     *              bindings for all pattern-bound variables and the RHS of the case.
     */
    def reduceInlineMatch(scrutinee: Tree, scrutType: Type, cases: List[CaseDef], typer: Typer)(using Context): MatchRedux = {

      val isImplicit = scrutinee.isEmpty

      /** Try to match pattern `pat` against scrutinee reference `scrut`. If successful add
       *  bindings for variables bound in this pattern to `caseBindingMap`.
       */
      def reducePattern(
        caseBindingMap: mutable.ListBuffer[(Symbol, MemberDef)],
        scrut: TermRef,
        pat: Tree
      )(using Context): Boolean = {

      	/** Create a binding of a pattern bound variable with matching part of
      	 *  scrutinee as RHS and type that corresponds to RHS.
      	 */
        def newTermBinding(sym: TermSymbol, rhs: Tree): Unit = {
          val copied = sym.copy(info = rhs.tpe.widenTermRefExpr, coord = sym.coord, flags = sym.flags &~ Case).asTerm
          caseBindingMap += ((sym, ValDef(copied, constToLiteral(rhs)).withSpan(sym.span)))
        }

        def newTypeBinding(sym: TypeSymbol, alias: Type): Unit = {
          val copied = sym.copy(info = TypeAlias(alias), coord = sym.coord).asType
          caseBindingMap += ((sym, TypeDef(copied)))
        }

        def searchImplicit(sym: TermSymbol, tpt: Tree) = {
          val evTyper = new Typer
          val evCtx = ctx.fresh.setTyper(evTyper)
          val evidence = evTyper.inferImplicitArg(tpt.tpe, tpt.span)(using evCtx)
          evidence.tpe match {
            case fail: Implicits.AmbiguousImplicits =>
              ctx.error(evTyper.missingArgMsg(evidence, tpt.tpe, ""), tpt.sourcePos)
              true // hard error: return true to stop implicit search here
            case fail: Implicits.SearchFailureType =>
              false
            case _ =>
              //inliner.println(i"inferred implicit $sym: ${sym.info} with $evidence: ${evidence.tpe.widen}, ${evCtx.gadt.constraint}, ${evCtx.typerState.constraint}")
              newTermBinding(sym, evidence)
              true
          }
        }

        type TypeBindsMap = SimpleIdentityMap[TypeSymbol, java.lang.Boolean]

        def getTypeBindsMap(pat: Tree, tpt: Tree): TypeBindsMap = {
          val getBinds = new TreeAccumulator[Set[TypeSymbol]] {
            def apply(syms: Set[TypeSymbol], t: Tree)(using Context): Set[TypeSymbol] = {
              val syms1 = t match {
                case t: Bind if t.symbol.isType =>
                  syms + t.symbol.asType
                case _ => syms
              }
              foldOver(syms1, t)
            }
          }

          // Extractors contain Bind nodes in type parameter lists, the tree looks like this:
          //   UnApply[t @ t](pats)(implicits): T[t]
          // Test case is pos/inline-caseclass.scala.
          val binds: Set[TypeSymbol] = pat match {
            case UnApply(TypeApply(_, tpts), _, _) => getBinds(Set.empty[TypeSymbol], tpts)
            case _ => getBinds(Set.empty[TypeSymbol], tpt)
          }

          val extractBindVariance = new TypeAccumulator[TypeBindsMap] {
            def apply(syms: TypeBindsMap, t: Type) = {
              val syms1 = t match {
                // `binds` is used to check if the symbol was actually bound by the pattern we're processing
                case tr: TypeRef if tr.symbol.is(Case) && binds.contains(tr.symbol.asType) =>
                  val trSym = tr.symbol.asType
                  // Exact same logic as in IsFullyDefinedAccumulator:
                  // the binding is to be maximized iff it only occurs contravariantly in the type
                  val wasToBeMinimized: Boolean = {
                    val v = syms(trSym)
                    if (v ne null) v else false
                  }
                  syms.updated(trSym, wasToBeMinimized || variance >= 0 : java.lang.Boolean)
                case _ =>
                  syms
              }
              foldOver(syms1, t)
            }
          }

          extractBindVariance(SimpleIdentityMap.Empty, tpt.tpe)
        }

        def addTypeBindings(typeBinds: TypeBindsMap)(using Context): Unit =
          typeBinds.foreachBinding { case (sym, shouldBeMinimized) =>
            newTypeBinding(sym, ctx.gadt.approximation(sym, fromBelow = shouldBeMinimized))
          }

        def registerAsGadtSyms(typeBinds: TypeBindsMap)(using Context): Unit =
          if (typeBinds.size > 0) ctx.gadt.addToConstraint(typeBinds.keys)

        pat match {
          case Typed(pat1, tpt) =>
            val typeBinds = getTypeBindsMap(pat1, tpt)
            registerAsGadtSyms(typeBinds)
            scrut <:< tpt.tpe && {
              addTypeBindings(typeBinds)
              reducePattern(caseBindingMap, scrut, pat1)
            }
          case pat @ Bind(name: TermName, Typed(_, tpt)) if isImplicit =>
            val typeBinds = getTypeBindsMap(tpt, tpt)
            registerAsGadtSyms(typeBinds)
            searchImplicit(pat.symbol.asTerm, tpt) && {
              addTypeBindings(typeBinds)
              true
            }
          case pat @ Bind(name: TermName, body) =>
            reducePattern(caseBindingMap, scrut, body) && {
              if (name != nme.WILDCARD) newTermBinding(pat.symbol.asTerm, ref(scrut))
              true
            }
          case Ident(nme.WILDCARD) =>
            true
          case pat: Literal =>
            scrut.widenTermRefExpr =:= pat.tpe
          case pat: RefTree =>
            scrut =:= pat.tpe ||
            scrut.widen.classSymbol.is(Module) && scrut.widen =:= pat.tpe.widen && {
              scrut.prefix match {
                case _: SingletonType | NoPrefix => true
                case _ => false
              }
            }
          case UnApply(unapp, _, pats) =>
            unapp.tpe.widen match {
              case mt: MethodType if mt.paramInfos.length == 1 =>

                def reduceSubPatterns(pats: List[Tree], selectors: List[Tree]): Boolean = (pats, selectors) match {
                  case (Nil, Nil) => true
                  case (pat :: pats1, selector :: selectors1) =>
                    val elem = newSym(InlineBinderName.fresh(), Synthetic, selector.tpe.widenTermRefExpr).asTerm
                    val rhs = constToLiteral(selector)
                    elem.defTree = rhs
                    caseBindingMap += ((NoSymbol, ValDef(elem, rhs).withSpan(elem.span)))
                    reducePattern(caseBindingMap, elem.termRef, pat) &&
                    reduceSubPatterns(pats1, selectors1)
                  case _ => false
                }

                val paramType = mt.paramInfos.head
                val paramCls = paramType.classSymbol
                if (paramCls.is(Case) && unapp.symbol.is(Synthetic) && scrut <:< paramType) {
                  val caseAccessors =
                    if (paramCls.is(Scala2x)) paramCls.caseAccessors.filter(_.is(Method))
                    else paramCls.asClass.paramAccessors
                  val selectors =
                    for (accessor <- caseAccessors)
                    yield constToLiteral(reduceProjection(ref(scrut).select(accessor).ensureApplied))
                  caseAccessors.length == pats.length && reduceSubPatterns(pats, selectors)
                }
                else false
              case _ =>
                false
            }
          case Inlined(EmptyTree, Nil, ipat) =>
            reducePattern(caseBindingMap, scrut, ipat)
          case _ => false
        }
      }

      /** The initial scrutinee binding: `val $scrutineeN = <scrutinee>` */
      val scrutineeSym = newSym(InlineScrutineeName.fresh(), Synthetic, scrutType).asTerm
      val scrutineeBinding = normalizeBinding(ValDef(scrutineeSym, scrutinee))

      def reduceCase(cdef: CaseDef): MatchRedux = {
        val caseBindingMap = new mutable.ListBuffer[(Symbol, MemberDef)]()

        def substBindings(
            bindings: List[(Symbol, MemberDef)],
            bbuf: mutable.ListBuffer[MemberDef],
            from: List[Symbol], to: List[Symbol]): (List[MemberDef], List[Symbol], List[Symbol]) =
          bindings match {
            case (sym, binding) :: rest =>
              bbuf += binding.subst(from, to).asInstanceOf[MemberDef]
              if (sym.exists) substBindings(rest, bbuf, sym :: from, binding.symbol :: to)
              else substBindings(rest, bbuf, from, to)
            case Nil => (bbuf.toList, from, to)
          }

        if (!isImplicit) caseBindingMap += ((NoSymbol, scrutineeBinding))
        val gadtCtx = ctx.fresh.setFreshGADTBounds.addMode(Mode.GadtConstraintInference)
        if (reducePattern(caseBindingMap, scrutineeSym.termRef, cdef.pat)(using gadtCtx)) {
          val (caseBindings, from, to) = substBindings(caseBindingMap.toList, mutable.ListBuffer(), Nil, Nil)
          val guardOK = cdef.guard.isEmpty || {
            typer.typed(cdef.guard.subst(from, to), defn.BooleanType) match {
              case ConstantValue(true) => true
              case _ => false
            }
          }
          if (guardOK) Some((caseBindings.map(_.subst(from, to)), cdef.body.subst(from, to)))
          else None
        }
        else None
      }

      def recur(cases: List[CaseDef]): MatchRedux = cases match {
        case Nil => None
        case cdef :: cases1 => reduceCase(cdef) `orElse` recur(cases1)
      }

      recur(cases)
    }
  }

  /** A typer for inlined bodies. Beyond standard typing, an inline typer performs
   *  the following functions:
   *
   *  1. Implement constant folding over inlined code
   *  2. Selectively expand ifs with constant conditions
   *  3. Inline arguments that are by-name closures
   *  4. Make sure inlined code is type-correct.
   *  5. Make sure that the tree's typing is idempotent (so that future -Ycheck passes succeed)
   */
  class InlineTyper(initialErrorCount: Int) extends ReTyper {
    import reducer._

    override def ensureAccessible(tpe: Type, superAccess: Boolean, pos: SourcePosition)(using Context): Type = {
      tpe match {
        case tpe: NamedType if tpe.symbol.exists && !tpe.symbol.isAccessibleFrom(tpe.prefix, superAccess) =>
          tpe.info match {
            case TypeAlias(alias) => return ensureAccessible(alias, superAccess, pos)
            case info: ConstantType if tpe.symbol.isStableMember => return info
            case _ =>
          }
        case _ =>
      }
      super.ensureAccessible(tpe, superAccess, pos)
    }

    override def typedIdent(tree: untpd.Ident, pt: Type)(using Context): Tree =
      checkStaging(tryInline(tree.asInstanceOf[tpd.Tree]) `orElse` super.typedIdent(tree, pt))

    override def typedSelect(tree: untpd.Select, pt: Type)(using Context): Tree = {
      assert(tree.hasType, tree)
      val qual1 = typed(tree.qualifier, selectionProto(tree.name, pt, this))
      val resNoReduce = untpd.cpy.Select(tree)(qual1, tree.name).withType(tree.typeOpt)
      val resMaybeReduced = constToLiteral(reducer.reduceProjection(resNoReduce))
      if (resNoReduce ne resMaybeReduced)
        typed(resMaybeReduced, pt) // redo typecheck if reduction changed something
      else
        val res = resMaybeReduced
        ensureAccessible(res.tpe, tree.qualifier.isInstanceOf[untpd.Super], tree.sourcePos)
        checkStaging(res)
    }

    private def checkStaging(tree: Tree): tree.type =
      val sym = tree.symbol
      if sym == defn.InternalQuoted_exprQuote || sym == defn.InternalQuoted_typeQuote then
        ctx.compilationUnit.needsStaging = true
      tree

    override def typedIf(tree: untpd.If, pt: Type)(using Context): Tree =
      typed(tree.cond, defn.BooleanType) match {
        case cond1 @ ConstantValue(b: Boolean) =>
          val selected0 = if (b) tree.thenp else tree.elsep
          val selected = if (selected0.isEmpty) tpd.Literal(Constant(())) else typed(selected0, pt)
          if (isIdempotentExpr(cond1)) selected
          else Block(cond1 :: Nil, selected)
        case cond1 =>
          if (tree.isInline)
            errorTree(tree, em"""cannot reduce inline if
                                | its condition   ${tree.cond}
                                | is not a constant value""")
          else
            cond1.computeNullableDeeply()
            val if1 = untpd.cpy.If(tree)(cond = untpd.TypedSplice(cond1))
            super.typedIf(if1, pt)
      }

    override def typedApply(tree: untpd.Apply, pt: Type)(using Context): Tree =
      constToLiteral(betaReduce(super.typedApply(tree, pt))) match {
        case res: Apply if res.symbol == defn.InternalQuoted_exprSplice
                        && level == 0
                        && call.symbol.is(Macro)
                        && !suppressInline =>
          expandMacro(res.args.head, tree.span)
        case res => res
      }

    override def typedMatchFinish(tree: untpd.Match, sel: Tree, wideSelType: Type, cases: List[untpd.CaseDef], pt: Type)(using Context) =
      if (!tree.isInline || ctx.owner.isInlineMethod) // don't reduce match of nested inline method yet
        super.typedMatchFinish(tree, sel, wideSelType, cases, pt)
      else {
        val selType = if (sel.isEmpty) wideSelType else sel.tpe
        reduceInlineMatch(sel, selType, cases.asInstanceOf[List[CaseDef]], this) match {
          case Some((caseBindings, rhs0)) =>
            // drop type ascriptions/casts hiding pattern-bound types (which are now aliases after reducing the match)
            // note that any actually necessary casts will be reinserted by the typing pass below
            val rhs1 = rhs0 match {
              case Block(stats, t) if t.span.isSynthetic =>
                t match {
                  case Typed(expr, _) =>
                    Block(stats, expr)
                  case TypeApply(sel@Select(expr, _), _) if sel.symbol.isTypeCast =>
                    Block(stats, expr)
                  case _ =>
                    rhs0
                }
              case _ => rhs0
            }
            val (usedBindings, rhs2) = dropUnusedDefs(caseBindings, rhs1)
            val rhs = seq(usedBindings, rhs2)
            inlining.println(i"""--- reduce:
                                |$tree
                                |--- to:
                                |$rhs""")
            typedExpr(rhs, pt)
          case None =>
            def guardStr(guard: untpd.Tree) = if (guard.isEmpty) "" else i" if $guard"
            def patStr(cdef: untpd.CaseDef) = i"case ${cdef.pat}${guardStr(cdef.guard)}"
            val msg =
              if (tree.selector.isEmpty)
                em"""cannot reduce summonFrom with
                   | patterns :  ${tree.cases.map(patStr).mkString("\n             ")}"""
              else
                em"""cannot reduce inline match with
                    | scrutinee:  $sel : ${selType}
                    | patterns :  ${tree.cases.map(patStr).mkString("\n             ")}"""
            errorTree(tree, msg)
        }
      }

    override def newLikeThis: Typer = new InlineTyper(initialErrorCount)

    /** Suppress further inlining if this inline typer has already issued errors */
    override def suppressInline(using Context) =
      ctx.reporter.errorCount > initialErrorCount || super.suppressInline
  }

  /** Drop any side-effect-free bindings that are unused in expansion or other reachable bindings.
   *  Inline def bindings that are used only once.
   */
  def dropUnusedDefs(bindings: List[MemberDef], tree: Tree)(using Context): (List[MemberDef], Tree) = {
    // inlining.println(i"drop unused $bindings%, % in $tree")
    val (termBindings, typeBindings) = bindings.partition(_.symbol.isTerm)
    if (typeBindings.nonEmpty) {
      val typeBindingsSet = typeBindings.foldLeft[SimpleIdentitySet[Symbol]](SimpleIdentitySet.empty)(_ + _.symbol)
      val inlineTypeBindings = new TreeTypeMap(
        typeMap = new TypeMap() {
          override def apply(tp: Type): Type = tp match {
            case tr: TypeRef if tr.prefix.eq(NoPrefix) && typeBindingsSet.contains(tr.symbol) =>
              val TypeAlias(res) = tr.info
              res
            case tp => mapOver(tp)
          }
        },
        treeMap = {
          case ident: Ident if ident.isType && typeBindingsSet.contains(ident.symbol) =>
            val TypeAlias(r) = ident.symbol.info
            TypeTree(r).withSpan(ident.span)
          case tree => tree
        }
      )
      val Block(termBindings1, tree1) = inlineTypeBindings(Block(termBindings, tree))
      dropUnusedDefs(termBindings1.asInstanceOf[List[ValOrDefDef]], tree1)
    }
    else {
      val refCount = newMutableSymbolMap[Int]
      val bindingOfSym = newMutableSymbolMap[MemberDef]

      def isInlineable(binding: MemberDef) = binding match {
        case ddef @ DefDef(_, Nil, Nil, _, _) => isElideableExpr(ddef.rhs)
        case vdef @ ValDef(_, _, _) => isElideableExpr(vdef.rhs)
        case _ => false
      }
      for (binding <- bindings if isInlineable(binding)) {
        refCount(binding.symbol) = 0
        bindingOfSym(binding.symbol) = binding
      }

      val countRefs = new TreeTraverser {
        override def traverse(t: Tree)(using Context) = {
          def updateRefCount(sym: Symbol, inc: Int) =
            for (x <- refCount.get(sym)) refCount(sym) = x + inc
          def updateTermRefCounts(t: Tree) =
            t.typeOpt.foreachPart {
              case ref: TermRef => updateRefCount(ref.symbol, 2) // can't be inlined, so make sure refCount is at least 2
              case _ =>
            }

          t match {
            case t: RefTree =>
              updateRefCount(t.symbol, 1)
              updateTermRefCounts(t)
            case _: New | _: TypeTree =>
              updateTermRefCounts(t)
            case _ =>
          }
          traverseChildren(t)
        }
      }
      countRefs.traverse(tree)
      for (binding <- bindings) countRefs.traverse(binding)

      def retain(boundSym: Symbol) = {
        refCount.get(boundSym) match {
          case Some(x) => x > 1 || x == 1 && !boundSym.is(Method)
          case none => true
        }
      } && !boundSym.is(Inline)

      val inlineBindings = new TreeMap {
        override def transform(t: Tree)(using Context) = t match {
          case t: RefTree =>
            val sym = t.symbol
            val t1 = refCount.get(sym) match {
              case Some(1) =>
                bindingOfSym(sym) match {
                  case binding: ValOrDefDef => integrate(binding.rhs, sym)
                }
              case none => t
            }
            super.transform(t1)
          case t: Apply =>
            val t1 = super.transform(t)
            if (t1 `eq` t) t else reducer.betaReduce(t1)
          case Block(Nil, expr) =>
            super.transform(expr)
          case _ =>
            super.transform(t)
        }
      }

      val retained = bindings.filterConserve(binding => retain(binding.symbol))
      if (retained `eq` bindings)
        (bindings, tree)
      else {
        val expanded = inlineBindings.transform(tree)
        dropUnusedDefs(retained, expanded)
      }
    }
  }

  private def expandMacro(body: Tree, span: Span)(using Context) = {
    assert(level == 0)
    val inlinedFrom = enclosingInlineds.last
    val dependencies = macroDependencies(body)

    if dependencies.nonEmpty && !ctx.reporter.errorsReported then
      for sym <- dependencies do
        if ctx.compilationUnit.source.file == sym.associatedFile then
          ctx.error(em"Cannot call macro $sym defined in the same source file", call.sourcePos)
        if (ctx.settings.XprintSuspension.value)
          ctx.echo(i"suspension triggered by macro call to ${sym.showLocated} in ${sym.associatedFile}", call.sourcePos)
      ctx.compilationUnit.suspend() // this throws a SuspendException

    val evaluatedSplice = inContext(tastyreflect.MacroExpansion.context(inlinedFrom)) {
      Splicer.splice(body, inlinedFrom.sourcePos, MacroClassLoader.fromContext)
    }
    val inlinedNormailizer = new TreeMap {
      override def transform(tree: tpd.Tree)(using Context): tpd.Tree = tree match {
        case Inlined(EmptyTree, Nil, expr) if enclosingInlineds.isEmpty => transform(expr)
        case _ => super.transform(tree)
      }
    }
    val normalizedSplice = inlinedNormailizer.transform(evaluatedSplice)
    if (normalizedSplice.isEmpty) normalizedSplice
    else normalizedSplice.withSpan(span)
  }

  /** Return the set of symbols that are referred at level -1 by the tree and defined in the current run.
   *  This corresponds to the symbols that will need to be interpreted.
   */
  private def macroDependencies(tree: Tree)(using Context) =
    new TreeAccumulator[List[Symbol]] {
      private var level = -1
      override def apply(syms: List[Symbol], tree: tpd.Tree)(using Context): List[Symbol] =
        if (level != -1) foldOver(syms, tree)
        else tree match {
          case tree: RefTree if level == -1 && tree.symbol.isDefinedInCurrentRun && !tree.symbol.isLocal =>
            foldOver(tree.symbol :: syms, tree)
          case Quoted(body) =>
            level += 1
            try apply(syms, body)
            finally level -= 1
          case Spliced(body) =>
            level -= 1
            try apply(syms, body)
            finally level += 1
          case _ =>
            foldOver(syms, tree)
        }
    }.apply(Nil, tree)
}
