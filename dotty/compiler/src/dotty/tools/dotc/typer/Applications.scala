package dotty.tools
package dotc
package typer

import core._
import ast.{Trees, tpd, untpd, desugar}
import util.Spans._
import util.Stats.record
import util.{SourcePosition, NoSourcePosition, SourceFile}
import Trees.Untyped
import Contexts._
import Flags._
import Symbols._
import Denotations.Denotation
import Types._
import Decorators._
import ErrorReporting._
import Trees._
import Names._
import StdNames._
import NameKinds.DefaultGetterName
import ProtoTypes._
import Inferencing._
import transform.TypeUtils._
import Nullables.{postProcessByNameArgs, given _}
import config.Feature

import collection.mutable
import config.Printers.{overload, typr, unapp}
import TypeApplications._

import reporting.messages.{UnexpectedPatternForSummonFrom, NotFoundMsg, TypeMismatch}
import reporting.{trace, Message}
import Constants.{Constant, IntTag, LongTag}
import dotty.tools.dotc.reporting.messages.{UnapplyInvalidReturnType, NotAnExtractor, UnapplyInvalidNumberOfArguments}
import Denotations.SingleDenotation
import annotation.{constructorOnly, threadUnsafe}

object Applications {
  import tpd._

  def extractorMember(tp: Type, name: Name)(using Context): SingleDenotation =
    tp.member(name).suchThat(sym => sym.info.isParameterless && sym.info.widenExpr.isValueType)

  def extractorMemberType(tp: Type, name: Name, errorPos: SourcePosition)(using Context): Type = {
    val ref = extractorMember(tp, name)
    if (ref.isOverloaded)
      errorType(i"Overloaded reference to $ref is not allowed in extractor", errorPos)
    ref.info.widenExpr.annotatedToRepeated
  }

  /** Does `tp` fit the "product match" conditions as an unapply result type
   *  for a pattern with `numArgs` subpatterns?
   *  This is the case if `tp` has members `_1` to `_N` where `N == numArgs`.
   */
  def isProductMatch(tp: Type, numArgs: Int, errorPos: SourcePosition = NoSourcePosition)(using Context): Boolean =
    numArgs > 0 && productArity(tp, errorPos) == numArgs

  /** Does `tp` fit the "product-seq match" conditions as an unapply result type
   *  for a pattern with `numArgs` subpatterns?
   *  This is the case if (1) `tp` has members `_1` to `_N` where `N <= numArgs + 1`.
   *                      (2) `tp._N` conforms to Seq match
   */
  def isProductSeqMatch(tp: Type, numArgs: Int, errorPos: SourcePosition = NoSourcePosition)(using Context): Boolean = {
    val arity = productArity(tp, errorPos)
    arity > 0 && arity <= numArgs + 1 &&
      unapplySeqTypeElemTp(productSelectorTypes(tp, errorPos).last).exists
  }

  /** Does `tp` fit the "get match" conditions as an unapply result type?
   *  This is the case of `tp` has a `get` member as well as a
   *  parameterless `isEmpty` member of result type `Boolean`.
   */
  def isGetMatch(tp: Type, errorPos: SourcePosition = NoSourcePosition)(using Context): Boolean =
    extractorMemberType(tp, nme.isEmpty, errorPos).widenSingleton.isRef(defn.BooleanClass) &&
    extractorMemberType(tp, nme.get, errorPos).exists

  /** If `getType` is of the form:
    *  ```
    *  {
    *    def lengthCompare(len: Int): Int // or, def length: Int
    *    def apply(i: Int): T = a(i)
    *    def drop(n: Int): scala.collection.Seq[T]
    *    def toSeq: scala.collection.Seq[T]
    *  }
    *  ```
    *  returns `T`, otherwise NoType.
    */
  def unapplySeqTypeElemTp(getTp: Type)(using Context): Type = {
    def lengthTp = ExprType(defn.IntType)
    def lengthCompareTp = MethodType(List(defn.IntType), defn.IntType)
    def applyTp(elemTp: Type) = MethodType(List(defn.IntType), elemTp)
    def dropTp(elemTp: Type) = MethodType(List(defn.IntType), defn.CollectionSeqType.appliedTo(elemTp))
    def toSeqTp(elemTp: Type) = ExprType(defn.CollectionSeqType.appliedTo(elemTp))

    // the result type of `def apply(i: Int): T`
    val elemTp = getTp.member(nme.apply).suchThat(_.info <:< applyTp(WildcardType)).info.resultType

    def hasMethod(name: Name, tp: Type) =
      getTp.member(name).suchThat(getTp.memberInfo(_) <:< tp).exists

    val isValid =
      elemTp.exists &&
      (hasMethod(nme.lengthCompare, lengthCompareTp) || hasMethod(nme.length, lengthTp)) &&
      hasMethod(nme.drop, dropTp(elemTp)) &&
      hasMethod(nme.toSeq, toSeqTp(elemTp))

    if (isValid) elemTp else NoType
  }

  def productSelectorTypes(tp: Type, errorPos: SourcePosition)(using Context): List[Type] = {
    def tupleSelectors(n: Int, tp: Type): List[Type] = {
      val sel = extractorMemberType(tp, nme.selectorName(n), errorPos)
      // extractorMemberType will return NoType if this is the tail of tuple with an unknown tail
      // such as `Int *: T` where `T <: Tuple`.
      if (sel.exists) sel :: tupleSelectors(n + 1, tp) else Nil
    }
    def genTupleSelectors(n: Int, tp: Type): List[Type] = tp match {
      case tp: AppliedType if !tp.derivesFrom(defn.ProductClass) && tp.derivesFrom(defn.PairClass) =>
        val List(head, tail) = tp.args
        head :: genTupleSelectors(n, tail)
      case _ => tupleSelectors(n, tp)
    }
    genTupleSelectors(0, tp)
  }

  def productArity(tp: Type, errorPos: SourcePosition = NoSourcePosition)(using Context): Int =
    if (defn.isProductSubType(tp)) productSelectorTypes(tp, errorPos).size else -1

  def productSelectors(tp: Type)(using Context): List[Symbol] = {
    val sels = for (n <- Iterator.from(0)) yield
      tp.member(nme.selectorName(n)).suchThat(_.info.isParameterless).symbol
    sels.takeWhile(_.exists).toList
  }

  def getUnapplySelectors(tp: Type, args: List[untpd.Tree], pos: SourcePosition)(using Context): List[Type] =
    if (args.length > 1 && !(tp.derivesFrom(defn.SeqClass))) {
      val sels = productSelectorTypes(tp, pos)
      if (sels.length == args.length) sels
      else tp :: Nil
    }
    else tp :: Nil

  def productSeqSelectors(tp: Type, argsNum: Int, pos: SourcePosition)(using Context): List[Type] = {
    val selTps = productSelectorTypes(tp, pos)
    val arity = selTps.length
    val elemTp = unapplySeqTypeElemTp(selTps.last)
    (0 until argsNum).map(i => if (i < arity - 1) selTps(i) else elemTp).toList
  }

  def unapplyArgs(unapplyResult: Type, unapplyFn: Tree, args: List[untpd.Tree], pos: SourcePosition)(using Context): List[Type] = {

    val unapplyName = unapplyFn.symbol.name
    def getTp = extractorMemberType(unapplyResult, nme.get, pos)

    def fail = {
      ctx.error(UnapplyInvalidReturnType(unapplyResult, unapplyName), pos)
      Nil
    }

    def unapplySeq(tp: Type)(fallback: => List[Type]): List[Type] = {
      val elemTp = unapplySeqTypeElemTp(tp)
      if (elemTp.exists) args.map(Function.const(elemTp))
      else if (isProductSeqMatch(tp, args.length, pos)) productSeqSelectors(tp, args.length, pos)
      else fallback
    }

    if (unapplyName == nme.unapplySeq)
      unapplySeq(unapplyResult) {
        if (isGetMatch(unapplyResult, pos)) unapplySeq(getTp)(fail)
        else fail
      }
    else {
      assert(unapplyName == nme.unapply)
      if (isProductMatch(unapplyResult, args.length, pos))
        productSelectorTypes(unapplyResult, pos)
      else if (isGetMatch(unapplyResult, pos))
        getUnapplySelectors(getTp, args, pos)
      else if (unapplyResult.widenSingleton isRef defn.BooleanClass)
        Nil
      else if (defn.isProductSubType(unapplyResult))
        productSelectorTypes(unapplyResult, pos)
          // this will cause a "wrong number of arguments in pattern" error later on,
          // which is better than the message in `fail`.
      else fail
    }
  }

  def wrapDefs(defs: mutable.ListBuffer[Tree], tree: Tree)(using Context): Tree =
    if (defs != null && defs.nonEmpty) tpd.Block(defs.toList, tree) else tree

  /** A wrapper indicating that its `app` argument has already integrated the type arguments
   *  of the expected type, provided that type is a (possibly ignored) PolyProto.
   *  I.e., if the expected type is a PolyProto, then `app` will be a `TypeApply(_, args)` where
   *  `args` are the type arguments of the expected type.
   */
  class IntegratedTypeArgs(val app: Tree)(implicit @constructorOnly src: SourceFile) extends tpd.Tree {
    override def span = app.span

    def canEqual(that: Any): Boolean = app.canEqual(that)
    def productArity: Int = app.productArity
    def productElement(n: Int): Any = app.productElement(n)
  }

  /** The unapply method of this extractor also recognizes IntegratedTypeArgs in closure blocks.
   *  This is necessary to deal with closures as left arguments of extension method applications.
   *  A test case is i5606.scala
   */
  object IntegratedTypeArgs {
    def apply(app: Tree)(using Context) = new IntegratedTypeArgs(app)
    def unapply(tree: Tree)(using Context): Option[Tree] = tree match {
      case tree: IntegratedTypeArgs => Some(tree.app)
      case Block(stats, IntegratedTypeArgs(app)) => Some(tpd.cpy.Block(tree)(stats, app))
      case _ => None
    }
  }

  /** A wrapper indicating that its argument is an application of an extension method.
   */
  class ExtMethodApply(app: Tree)(implicit @constructorOnly src: SourceFile)
  extends IntegratedTypeArgs(app) {
    overwriteType(WildcardType)
      // ExtMethodApply always has wildcard type in order not to prompt any further adaptations
      // such as eta expansion before the method is fully applied.
  }
}

trait Applications extends Compatibility {
  self: Typer & Dynamic =>

  import Applications._
  import tpd.{ cpy => _, _ }
  import untpd.cpy

  /** @tparam Arg       the type of arguments, could be tpd.Tree, untpd.Tree, or Type
   *  @param methRef    the reference to the method of the application
   *  @param funType    the type of the function part of the application
   *  @param args       the arguments of the application
   *  @param resultType the expected result type of the application
   */
  abstract class Application[Arg](methRef: TermRef, funType: Type, args: List[Arg], resultType: Type)(using Context) {

    /** The type of typed arguments: either tpd.Tree or Type */
    type TypedArg

    /** Given an original argument and the type of the corresponding formal
     *  parameter, produce a typed argument.
     */
    protected def typedArg(arg: Arg, formal: Type): TypedArg

    /** Turn a typed tree into an argument */
    protected def treeToArg(arg: Tree): Arg

    /** Check that argument corresponds to type `formal` and
     *  possibly add it to the list of adapted arguments
     */
    protected def addArg(arg: TypedArg, formal: Type): Unit

    /** Is this an argument of the form `expr: _*` or a RepeatedParamType
     *  derived from such an argument?
     */
    protected def isVarArg(arg: Arg): Boolean

    /** If constructing trees, turn last `n` processed arguments into a
     *  `SeqLiteral` tree with element type `elemFormal`.
     */
    protected def makeVarArg(n: Int, elemFormal: Type): Unit

    /** If all `args` have primitive numeric types, make sure it's the same one */
    protected def harmonizeArgs(args: List[TypedArg]): List[TypedArg]

    /** Signal failure with given message at position of given argument */
    protected def fail(msg: Message, arg: Arg): Unit

    /** Signal failure with given message at position of the application itself */
    protected def fail(msg: Message): Unit

    protected def appPos: SourcePosition

    /** The current function part, which might be affected by lifting.
     */
    protected def normalizedFun: Tree

    protected def typeOfArg(arg: Arg): Type

    /** If constructing trees, pull out all parts of the function
     *  which are not idempotent into separate prefix definitions
     */
    protected def liftFun(): Unit = ()

    /** Whether `liftFun` is needed? It is the case if default arguments are used.
     */
    protected def needLiftFun: Boolean = {
      def requiredArgNum(tp: Type): Int = tp.widen match {
        case funType: MethodType =>
          val paramInfos = funType.paramInfos
          val argsNum = paramInfos.size
          if (argsNum >= 1 && paramInfos.last.isRepeatedParam)
            // Repeated arguments do not count as required arguments
            argsNum - 1
          else
            argsNum
        case funType: PolyType => requiredArgNum(funType.resultType)
        case tp => args.size
      }

      !isJavaAnnotConstr(methRef.symbol) &&
      args.size < requiredArgNum(funType)
    }

    /** A flag signalling that the typechecking the application was so far successful */
    private var _ok = true

    def ok: Boolean = _ok
    def ok_=(x: Boolean): Unit = _ok = x

    /** The function's type after widening and instantiating polytypes
     *  with TypeParamRefs in constraint set
     */
    @threadUnsafe lazy val methType: Type = liftedFunType.widen match {
      case funType: MethodType => funType
      case funType: PolyType => constrained(funType).resultType
      case tp => tp //was: funType
    }

    @threadUnsafe lazy val liftedFunType: Type =
      if (needLiftFun) {
        liftFun()
        normalizedFun.tpe
      }
      else funType

    /** The arguments re-ordered so that each named argument matches the
     *  same-named formal parameter.
     */
    @threadUnsafe lazy val orderedArgs: List[Arg] =
      if (hasNamedArg(args))
        reorder(args.asInstanceOf[List[untpd.Tree]]).asInstanceOf[List[Arg]]
      else
        args

    protected def init(): Unit = methType match {
      case methType: MethodType =>
        // apply the result type constraint, unless method type is dependent
        val resultApprox = resultTypeApprox(methType)
        if (!constrainResult(methRef.symbol, resultApprox, resultType))
          if (ctx.typerState.isCommittable)
            // defer the problem until after the application;
            // it might be healed by an implicit conversion
            ()
          else
            fail(TypeMismatch(methType.resultType, resultType))

        // match all arguments with corresponding formal parameters
        matchArgs(orderedArgs, methType.paramInfos, 0)
      case _ =>
        if (methType.isError) ok = false
        else fail(s"$methString does not take parameters")
    }

    /** The application was successful */
    def success: Boolean = ok

    protected def methodType: MethodType = methType.asInstanceOf[MethodType]
    private def methString: String = i"${methRef.symbol}: ${methType.show}"

    /** Re-order arguments to correctly align named arguments */
    def reorder[T >: Untyped](args: List[Trees.Tree[T]]): List[Trees.Tree[T]] = {

      /** @param pnames    The list of parameter names that are missing arguments
       *  @param args      The list of arguments that are not yet passed, or that are waiting to be dropped
       *  @param nameToArg A map from as yet unseen names to named arguments
       *  @param toDrop    A set of names that have already be passed as named arguments
       *
       *  For a well-typed application we have the invariants
       *
       *  1. `(args diff toDrop)` can be reordered to match `pnames`
       *  2. For every `(name -> arg)` in `nameToArg`, `arg` is an element of `args`
       */
      def handleNamed(pnames: List[Name], args: List[Trees.Tree[T]],
                      nameToArg: Map[Name, Trees.NamedArg[T]], toDrop: Set[Name]): List[Trees.Tree[T]] = pnames match {
        case pname :: pnames1 if nameToArg contains pname =>
          // there is a named argument for this parameter; pick it
          nameToArg(pname) :: handleNamed(pnames1, args, nameToArg - pname, toDrop + pname)
        case _ =>
          def pnamesRest = if (pnames.isEmpty) pnames else pnames.tail
          args match {
            case (arg @ NamedArg(aname, _)) :: args1 =>
              if (toDrop contains aname) // argument is already passed
                handleNamed(pnames, args1, nameToArg, toDrop - aname)
              else if ((nameToArg contains aname) && pnames.nonEmpty) // argument is missing, pass an empty tree
                genericEmptyTree :: handleNamed(pnames.tail, args, nameToArg, toDrop)
              else { // name not (or no longer) available for named arg
                def msg =
                  if (methodType.paramNames contains aname)
                    s"parameter $aname of $methString is already instantiated"
                  else
                    s"$methString does not have a parameter $aname"
                fail(msg, arg.asInstanceOf[Arg])
                arg :: handleNamed(pnamesRest, args1, nameToArg, toDrop)
              }
            case arg :: args1 =>
              arg :: handleNamed(pnamesRest, args1, nameToArg, toDrop) // unnamed argument; pick it
            case Nil => // no more args, continue to pick up any preceding named args
              if (pnames.isEmpty) Nil
              else handleNamed(pnamesRest, args, nameToArg, toDrop)
          }
      }

      def handlePositional(pnames: List[Name], args: List[Trees.Tree[T]]): List[Trees.Tree[T]] =
        args match {
          case (arg: NamedArg @unchecked) :: _ =>
            val nameAssocs = for (arg @ NamedArg(name, _) <- args) yield (name, arg)
            handleNamed(pnames, args, nameAssocs.toMap, Set())
          case arg :: args1 =>
            arg :: handlePositional(if (pnames.isEmpty) Nil else pnames.tail, args1)
          case Nil => Nil
        }

      handlePositional(methodType.paramNames, args)
    }

    /** Splice new method reference into existing application */
    def spliceMeth(meth: Tree, app: Tree): Tree = app match {
      case Apply(fn, args) =>
        spliceMeth(meth, fn).appliedToArgs(args)
      case TypeApply(fn, targs) =>
        // Note: It is important that the type arguments `targs` are passed in new trees
        // instead of being spliced in literally. Otherwise, a type argument to a default
        // method could be constructed as the definition site of the type variable for
        // that default constructor. This would interpolate type variables too early,
        // causing lots of tests (among them tasty_unpickleScala2) to fail.
        //
        // The test case is in i1757.scala. Here we have a variable `s` and a method `cpy`
        // defined like this:
        //
        //      var s
        //      def cpy[X](b: List[Int] = b): B[X] = new B[X](b)
        //
        // The call `s.cpy()` then gets expanded to
        //
        //      { val $1$: B[Int] = this.s
        //        $1$.cpy[X']($1$.cpy$default$1[X']
        //      }
        //
        // A type variable gets interpolated if it does not appear in the type
        // of the current tree and the current tree contains the variable's "definition".
        // Previously, the polymorphic function tree to which the variable was first added
        // was taken as the variable's definition. But that fails here because that
        // tree was `s.cpy` but got transformed into `$1$.cpy`. We now take the type argument
        // [X'] of the variable as its definition tree, which is more robust. But then
        // it's crucial that the type tree is not copied directly as argument to
        // `cpy$default$1`. If it was, the variable `X'` would already be interpolated
        // when typing the default argument, which is too early.
        spliceMeth(meth, fn).appliedToTypes(targs.tpes)
      case _ => meth
    }

    /** Find reference to default parameter getter for parameter #n in current
     *  parameter list, or NoType if none was found
     */
    def findDefaultGetter(n: Int)(using Context): Tree = {
      val meth = methRef.symbol.asTerm
      val receiver: Tree = methPart(normalizedFun) match {
        case Select(receiver, _) => receiver
        case mr => mr.tpe.normalizedPrefix match {
          case mr: TermRef => ref(mr)
          case mr =>
            if (this.isInstanceOf[TestApplication[?]])
              // In this case it is safe to skolemize now; we will produce a stable prefix for the actual call.
              ref(mr.narrow)
            else
              EmptyTree
        }
      }
      val getterPrefix =
        if (meth.is(Synthetic) && meth.name == nme.apply) nme.CONSTRUCTOR else meth.name
      def getterName = DefaultGetterName(getterPrefix, n)
      if (!meth.hasDefaultParams)
        EmptyTree
      else if (receiver.isEmpty) {
        def findGetter(cx: Context): Tree =
          if (cx eq NoContext) EmptyTree
          else if (cx.scope != cx.outer.scope &&
            cx.denotNamed(meth.name).hasAltWith(_.symbol == meth)) {
            val denot = cx.denotNamed(getterName)
            if (denot.exists) ref(TermRef(cx.owner.thisType, getterName, denot))
            else findGetter(cx.outer)
          }
          else findGetter(cx.outer)
        findGetter(ctx)
      }
      else {
        def selectGetter(qual: Tree): Tree = {
          val getterDenot = qual.tpe.member(getterName)
          if (getterDenot.exists) qual.select(TermRef(qual.tpe, getterName, getterDenot))
          else EmptyTree
        }
        if (!meth.isClassConstructor)
          selectGetter(receiver)
        else {
          // default getters for class constructors are found in the companion object
          val cls = meth.owner
          val companion = cls.companionModule
          if (companion.isTerm) {
            val prefix = receiver.tpe.baseType(cls).normalizedPrefix
            if (prefix.exists) selectGetter(ref(TermRef(prefix, companion.asTerm)))
            else EmptyTree
          }
          else EmptyTree
        }
      }
    }

    /** Is `sym` a constructor of a Java-defined annotation? */
    def isJavaAnnotConstr(sym: Symbol): Boolean =
      sym.is(JavaDefined) && sym.isConstructor && sym.owner.derivesFrom(defn.AnnotationClass)

    /** Match re-ordered arguments against formal parameters
     *  @param n   The position of the first parameter in formals in `methType`.
     */
    def matchArgs(args: List[Arg], formals: List[Type], n: Int): Unit =
      if (success) formals match {
        case formal :: formals1 =>

          /** Add result of typing argument `arg` against parameter type `formal`.
           *  @return  A type transformation to apply to all arguments following this one.
           */
          def addTyped(arg: Arg, formal: Type): Type => Type = {
            addArg(typedArg(arg, formal), formal)
            if (methodType.isParamDependent && typeOfArg(arg).exists)
              // `typeOfArg(arg)` could be missing because the evaluation of `arg` produced type errors
              safeSubstParam(_, methodType.paramRefs(n), typeOfArg(arg))
            else identity
          }

          def missingArg(n: Int): Unit = {
            val pname = methodType.paramNames(n)
            fail(
              if (pname.firstPart contains '$') s"not enough arguments for $methString"
              else s"missing argument for parameter $pname of $methString")
          }

          def tryDefault(n: Int, args1: List[Arg]): Unit = {
            val sym = methRef.symbol

            val defaultExpr =
              if (isJavaAnnotConstr(sym)) {
                val cinfo = sym.owner.asClass.classInfo
                val pname = methodType.paramNames(n)
                val hasDefault = cinfo.member(pname)
                  .suchThat(d => d.is(Method) && d.hasAnnotation(defn.AnnotationDefaultAnnot)).exists

                // Use `_` as a placeholder for the default value of an
                // annotation parameter, this will be recognized by the backend.
                if (hasDefault)
                  tpd.Underscore(formal)
                else
                  EmptyTree
              }
              else {
                val getter =
                  if (sym.exists) // `sym` doesn't exist for structural calls
                    findDefaultGetter(n + numArgs(normalizedFun))
                  else
                    EmptyTree

                if (!getter.isEmpty)
                  spliceMeth(getter.withSpan(normalizedFun.span), normalizedFun)
                else
                  EmptyTree
              }

            if (!defaultExpr.isEmpty) {
              val substParam = addTyped(treeToArg(defaultExpr), formal)
              matchArgs(args1, formals1.mapconserve(substParam), n + 1)
            }
            else
              missingArg(n)
          }

          if (formal.isRepeatedParam)
            args match {
              case arg :: Nil if isVarArg(arg) =>
                addTyped(arg, formal)
              case Typed(Literal(Constant(null)), _) :: Nil =>
                addTyped(args.head, formal)
              case _ =>
                val elemFormal = formal.widenExpr.argTypesLo.head
                val typedArgs =
                  harmonic(harmonizeArgs, elemFormal)(args.map(typedArg(_, elemFormal)))
                typedArgs.foreach(addArg(_, elemFormal))
                makeVarArg(args.length, elemFormal)
            }
          else args match {
            case EmptyTree :: args1 =>
              tryDefault(n, args1)
            case arg :: args1 =>
              val substParam = addTyped(arg, formal)
              matchArgs(args1, formals1.mapconserve(substParam), n + 1)
            case nil =>
              tryDefault(n, args)
          }

        case nil =>
          args match {
            case arg :: args1 => fail(s"too many arguments for $methString", arg)
            case nil =>
          }
      }
  }

  /** Subclass of Application for the cases where we are interested only
   *  in a "can/cannot apply" answer, without needing to construct trees or
   *  issue error messages.
   */
  abstract class TestApplication[Arg](methRef: TermRef, funType: Type, args: List[Arg], resultType: Type)(using Context)
  extends Application[Arg](methRef, funType, args, resultType) {
    type TypedArg = Arg
    type Result = Unit

    protected def argOK(arg: TypedArg, formal: Type): Boolean = argType(arg, formal) match {
      case ref: TermRef if ref.denot.isOverloaded =>
        // in this case we could not resolve overloading because no alternative
        // matches expected type
        false
      case argtpe =>
        def SAMargOK = formal match {
          case SAMType(sam) => argtpe <:< sam.toFunctionType()
          case _ => false
        }
        isCompatible(argtpe, formal) || ctx.mode.is(Mode.ImplicitsEnabled) && SAMargOK
    }

    /** The type of the given argument */
    protected def argType(arg: Arg, formal: Type): Type

    def typedArg(arg: Arg, formal: Type): Arg = arg
    final def addArg(arg: TypedArg, formal: Type): Unit = ok = ok & argOK(arg, formal)
    def makeVarArg(n: Int, elemFormal: Type): Unit = {}
    def fail(msg: Message, arg: Arg): Unit =
      ok = false
    def fail(msg: Message): Unit =
      ok = false
    def appPos: SourcePosition = NoSourcePosition
    @threadUnsafe lazy val normalizedFun:   Tree = ref(methRef)
    init()
  }

  /** Subclass of Application for applicability tests with type arguments and value
   *  argument trees.
   */
  class ApplicableToTrees(methRef: TermRef, args: List[Tree], resultType: Type)(using Context)
  extends TestApplication(methRef, methRef.widen, args, resultType) {
    def argType(arg: Tree, formal: Type): Type = normalize(arg.tpe, formal)
    def treeToArg(arg: Tree): Tree = arg
    def isVarArg(arg: Tree): Boolean = tpd.isWildcardStarArg(arg)
    def typeOfArg(arg: Tree): Type = arg.tpe
    def harmonizeArgs(args: List[Tree]): List[Tree] = harmonize(args)
  }

  /** Subclass of Application for applicability tests with type arguments and value
   * argument trees.
   */
  class ApplicableToTreesDirectly(methRef: TermRef, args: List[Tree], resultType: Type)(using Context)
  extends ApplicableToTrees(methRef, args, resultType)(using ctx) {
    override def argOK(arg: TypedArg, formal: Type): Boolean =
      argType(arg, formal) relaxed_<:< formal.widenExpr
  }

  /** Subclass of Application for applicability tests with value argument types. */
  class ApplicableToTypes(methRef: TermRef, args: List[Type], resultType: Type)(using Context)
  extends TestApplication(methRef, methRef, args, resultType) {
    def argType(arg: Type, formal: Type): Type = arg
    def treeToArg(arg: Tree): Type = arg.tpe
    def isVarArg(arg: Type): Boolean = arg.isRepeatedParam
    def typeOfArg(arg: Type): Type = arg
    def harmonizeArgs(args: List[Type]): List[Type] = harmonizeTypes(args)
  }

  /** Subclass of Application for type checking an Apply node, where
   *  types of arguments are either known or unknown.
   */
  abstract class TypedApply[T >: Untyped](
    app: untpd.Apply, fun: Tree, methRef: TermRef, args: List[Trees.Tree[T]], resultType: Type)(using Context)
  extends Application(methRef, fun.tpe, args, resultType) {
    type TypedArg = Tree
    def isVarArg(arg: Trees.Tree[T]): Boolean = untpd.isWildcardStarArg(arg)
    private var typedArgBuf = new mutable.ListBuffer[Tree]
    private var liftedDefs: mutable.ListBuffer[Tree] = null
    private var myNormalizedFun: Tree = fun
    init()

    def addArg(arg: Tree, formal: Type): Unit =
      typedArgBuf += adapt(arg, formal.widenExpr)

    def makeVarArg(n: Int, elemFormal: Type): Unit = {
      val args = typedArgBuf.takeRight(n).toList
      typedArgBuf.trimEnd(n)
      val elemtpt = TypeTree(elemFormal)
      typedArgBuf += seqToRepeated(SeqLiteral(args, elemtpt))
    }

    def harmonizeArgs(args: List[TypedArg]): List[Tree] = harmonize(args)

    override def appPos: SourcePosition = app.sourcePos

    def fail(msg: Message, arg: Trees.Tree[T]): Unit = {
      ctx.error(msg, arg.sourcePos)
      ok = false
    }

    def fail(msg: Message): Unit = {
      ctx.error(msg, app.sourcePos)
      ok = false
    }

    def normalizedFun:  Tree = myNormalizedFun

    private def lifter(using Context) =
      if (methRef.symbol.hasDefaultParams) LiftComplex else LiftImpure

    override def liftFun(): Unit =
      if (liftedDefs == null) {
        liftedDefs = new mutable.ListBuffer[Tree]
        myNormalizedFun = lifter.liftApp(liftedDefs, myNormalizedFun)
      }

    /** The index of the first difference between lists of trees `xs` and `ys`
     *  -1 if there are no differences.
     */
    private def firstDiff[T <: Trees.Tree[?]](xs: List[T], ys: List[T], n: Int = 0): Int = xs match {
      case x :: xs1 =>
        ys match {
          case y :: ys1 => if (x ne y) n else firstDiff(xs1, ys1, n + 1)
          case nil => n
        }
      case nil =>
        ys match {
          case y :: ys1 => n
          case nil => -1
        }
    }
    private def sameSeq[T <: Trees.Tree[?]](xs: List[T], ys: List[T]): Boolean = firstDiff(xs, ys) < 0

    val result:   Tree = {
      var typedArgs = typedArgBuf.toList
      def app0 = cpy.Apply(app)(normalizedFun, typedArgs) // needs to be a `def` because typedArgs can change later
      val app1 =
        if (!success) app0.withType(UnspecifiedErrorType)
        else {
          if (!sameSeq(args, orderedArgs.dropWhile(_ eq EmptyTree)) && !isJavaAnnotConstr(methRef.symbol)) {
            // need to lift arguments to maintain evaluation order in the
            // presence of argument reorderings.

            liftFun()

            // lift arguments in the definition order
            val argDefBuf = mutable.ListBuffer.empty[Tree]
            typedArgs = lifter.liftArgs(argDefBuf, methType, typedArgs)
            // Lifted arguments ordered based on the original order of typedArgBuf and
            // with all non-explicit default parameters at the end in declaration order.
            val orderedArgDefs = {
              // Indices of original typed arguments that are lifted by liftArgs
              val impureArgIndices = typedArgBuf.zipWithIndex.collect {
                case (arg, idx) if !lifter.noLift(arg) => idx
              }
              def position(arg: Trees.Tree[T]) = {
                val i = args.indexOf(arg)
                if (i >= 0) i else orderedArgs.length
              }
              // The original indices of all ordered arguments, as an array
              val originalIndices = orderedArgs.map(position).toArray
              // Assuming stable sorting all non-explicit default parameters will remain in the end with the same order
              val defaultParamIndex = typedArgs.size
              // A map from lifted argument index to the corresponding position in the original argument list
              def originalIndex(n: Int) =
                if (n < originalIndices.length) originalIndices(n) else orderedArgs.length
              scala.util.Sorting.stableSort[(Tree, Int), Int](
                argDefBuf.zip(impureArgIndices), (arg, idx) => originalIndex(idx)).map(_._1)
            }
            liftedDefs ++= orderedArgDefs
          }
          if (sameSeq(typedArgs, args)) // trick to cut down on tree copying
            typedArgs = args.asInstanceOf[List[Tree]]
          assignType(app0, normalizedFun, typedArgs)
        }
      wrapDefs(liftedDefs, app1)
    }
  }

  /** Subclass of Application for type checking an Apply node with untyped arguments. */
  class ApplyToUntyped(app: untpd.Apply, fun: Tree, methRef: TermRef, proto: FunProto, resultType: Type)(using Context)
  extends TypedApply(app, fun, methRef, proto.args, resultType) {
    def typedArg(arg: untpd.Tree, formal: Type): TypedArg = proto.typedArg(arg, formal)
    def treeToArg(arg: Tree): untpd.Tree = untpd.TypedSplice(arg)
    def typeOfArg(arg: untpd.Tree): Type = proto.typeOfArg(arg)
  }

  /** Subclass of Application for type checking an Apply node with typed arguments. */
  class ApplyToTyped(app: untpd.Apply, fun: Tree, methRef: TermRef, args: List[Tree], resultType: Type)(using Context)
  extends TypedApply(app, fun, methRef, args, resultType) {
    def typedArg(arg: Tree, formal: Type): TypedArg = arg
    def treeToArg(arg: Tree): Tree = arg
    def typeOfArg(arg: Tree): Type = arg.tpe
  }

  /** If `app` is a `this(...)` constructor call, the this-call argument context,
   *  otherwise the current context.
   */
  def argCtx(app: untpd.Tree)(using Context): Context =
    if (ctx.owner.isClassConstructor && untpd.isSelfConstrCall(app)) ctx.thisCallArgContext
    else ctx

  /** Typecheck the function part of an application.
   *  Fallback if this fails: try to convert `E` to `new E`.
   */
  def typedFunPart(fn: untpd.Tree, pt: Type)(using Context): Tree =
    tryEither {
      typedExpr(fn, pt)
    } { (result, tstate) =>
      def fallBack(nuState: TyperState) =
        if (nuState ne ctx.typerState) && !saysNotFound(nuState, EmptyTypeName)
        then nuState.commit() // nuState messages are more interesting that tstate's "not found"
        else tstate.commit()  // it's "not found" both ways; keep original message
        result
      if untpd.isPath(fn) then tryNew(untpd)(fn, pt, fallBack)
      else fallBack(ctx.typerState)
    }

  /** Typecheck application. Result could be an `Apply` node,
   *  or, if application is an operator assignment, also an `Assign` or
   *  Block node.
   */
  def typedApply(tree: untpd.Apply, pt: Type)(using Context): Tree = {

    def realApply(using Context): Tree = {
      val originalProto = new FunProto(tree.args, IgnoredProto(pt))(this, tree.isUsingApply)(using argCtx(tree))
      record("typedApply")
      val fun1 = typedFunPart(tree.fun, originalProto)

      // Warning: The following lines are dirty and fragile. We record that auto-tupling was demanded as
      // a side effect in adapt. If it was, we assume the tupled proto-type in the rest of the application,
      // until, possibly, we have to fall back to insert an implicit on the qualifier.
      // This crucially relies on he fact that `proto` is used only in a single call of `adapt`,
      // otherwise we would get possible cross-talk between different `adapt` calls using the same
      // prototype. A cleaner alternative would be to return a modified prototype from `adapt` together with
      // a modified tree but this would be more convoluted and less efficient.
      val proto = if (originalProto.isTupled) originalProto.tupled else originalProto

      // If some of the application's arguments are function literals without explicitly declared
      // parameter types, relate the normalized result type of the application with the
      // expected type through `constrainResult`. This can add more constraints which
      // help sharpen the inferred parameter types for the argument function literal(s).
      // This tweak is needed to make i1378 compile.
      if (tree.args.exists(untpd.isFunctionWithUnknownParamType(_)))
        if (!constrainResult(tree.symbol, fun1.tpe.widen, proto.derivedFunProto(resultType = pt)))
          typr.println(i"result failure for $tree with type ${fun1.tpe.widen}, expected = $pt")

      /** Type application where arguments come from prototype, and no implicits are inserted */
      def simpleApply(fun1: Tree, proto: FunProto)(using Context): Tree =
        methPart(fun1).tpe match {
          case funRef: TermRef =>
            val app = ApplyTo(tree, fun1, funRef, proto, pt)
            convertNewGenericArray(
              postProcessByNameArgs(funRef, app).computeNullable())
          case _ =>
            handleUnexpectedFunType(tree, fun1)
        }

      /** Try same application with an implicit inserted around the qualifier of the function
       *  part. Return an optional value to indicate success.
       */
      def tryWithImplicitOnQualifier(fun1: Tree, proto: FunProto)(using Context): Option[Tree] =
        if (ctx.mode.is(Mode.SynthesizeExtMethodReceiver))
          // Suppress insertion of apply or implicit conversion on extension method receiver
          None
        else
          tryInsertImplicitOnQualifier(fun1, proto, ctx.typerState.ownedVars) flatMap { fun2 =>
            tryEither {
              Some(simpleApply(fun2, proto)): Option[Tree]
            } {
              (_, _) => None
            }
          }

      fun1.tpe match {
        case err: ErrorType => cpy.Apply(tree)(fun1, proto.typedArgs()).withType(err)
        case TryDynamicCallType => typedDynamicApply(tree, pt)
        case _ =>
          if (originalProto.isDropped) fun1
          else if (fun1.symbol == defn.Compiletime_summonFrom)
            // Special handling of `summonFrom { ... }`.
            // We currently cannot use a macro for that since unlike other inline methods
            // summonFrom needs to expand lazily. For instance, in
            //
            //    summonFrom {
            //      case given A[t] =>
            //        summonFrom
            //          case given `t` => ...
            //        }
            //    }
            //
            // the second `summonFrom` should expand only once the first `summonFrom` is
            // evaluated and `t` is bound. But normal inline expansion does not behave that
            // way: arguments to inline function are expanded before the function call.
            // To make this work using regular inlining, we'd need a way to annotate
            // an inline function that it should expand only if there are no enclosing
            // applications of inline functions.
            tree.args match {
              case (arg @ Match(EmptyTree, cases)) :: Nil =>
                cases.foreach {
                  case CaseDef(Typed(_: Ident, _), _, _) => // OK
                  case CaseDef(Bind(_, Typed(_: Ident, _)), _, _) => // OK
                  case CaseDef(Ident(name), _, _) if name == nme.WILDCARD => // Ok
                  case CaseDef(pat, _, _) =>
                    ctx.error(UnexpectedPatternForSummonFrom(pat), pat.sourcePos)
                }
                typed(untpd.InlineMatch(EmptyTree, cases).withSpan(arg.span), pt)
              case _ =>
                errorTree(tree, em"argument to summonFrom must be a pattern matching closure")
            }
          else
            tryEither {
              simpleApply(fun1, proto)
            } {
              (failedVal, failedState) =>
                def fail = { failedState.commit(); failedVal }
                // Try once with original prototype and once (if different) with tupled one.
                // The reason we need to try both is that the decision whether to use tupled
                // or not was already taken but might have to be revised when an implicit
                // is inserted on the qualifier.
                tryWithImplicitOnQualifier(fun1, originalProto).getOrElse(
                  if (proto eq originalProto) fail
                  else tryWithImplicitOnQualifier(fun1, proto).getOrElse(fail))
            }
      }
    }

    /** Convert expression like
     *
     *     e += (args)
     *
     *  where the lifted-for-assignment version of e is { val xs = es; e' } to
     *
     *     { val xs = es; e' = e' + args }
     */
    def typedOpAssign(using Context): Tree = {
      val (lhs1, name, rhss) = tree match
        case Apply(Select(lhs, name), rhss) => (typedExpr(lhs), name, rhss)
        case Apply(untpd.TypedSplice(Select(lhs1, name)), rhss) => (lhs1, name, rhss)
      val liftedDefs = new mutable.ListBuffer[Tree]
      val lhs2 = untpd.TypedSplice(LiftComplex.liftAssigned(liftedDefs, lhs1))
      val assign = untpd.Assign(lhs2,
          untpd.Apply(untpd.Select(lhs2, name.asSimpleName.dropRight(1)), rhss))
      wrapDefs(liftedDefs, typed(assign))
    }

    val app1 =
      if (untpd.isOpAssign(tree))
        tryEither {
          realApply
        } { (failedVal, failedState) =>
          tryEither {
            typedOpAssign
          } { (_, _) =>
            failedState.commit()
            failedVal
          }
        }
      else {
        val app = realApply
        app match {
          case Apply(fn @ Select(left, _), right :: Nil) if fn.hasType =>
            val op = fn.symbol
            if (op == defn.Any_== || op == defn.Any_!=)
              checkCanEqual(left.tpe.widen, right.tpe.widen, app.span)
          case _ =>
        }
        app
      }
    app1 match {
      case Apply(Block(stats, fn), args) =>
        tpd.cpy.Block(app1)(stats, tpd.cpy.Apply(app1)(fn, args))
      case _ =>
        app1
    }
  }

  /** Typecheck an Apply node with a typed function and possibly-typed arguments coming from `proto` */
  def ApplyTo(app: untpd.Apply, fun: tpd.Tree, methRef: TermRef, proto: FunProto, resultType: Type)(using Context): tpd.Tree =
    val typer = ctx.typer
    if (proto.allArgTypesAreCurrent())
      typer.ApplyToTyped(app, fun, methRef, proto.typedArgs(), resultType).result
    else
      typer.ApplyToUntyped(app, fun, methRef, proto, resultType)(
        using fun.nullableInArgContext(using argCtx(app))).result

  /** Overridden in ReTyper to handle primitive operations that can be generated after erasure */
  protected def handleUnexpectedFunType(tree: untpd.Apply, fun: Tree)(using Context): Tree =
    if ctx.reporter.errorsReported then
      throw TypeError(i"unexpected function type: ${methPart(fun).tpe}")
    else
      throw Error(i"unexpected type.\n  fun = $fun,\n  methPart(fun) = ${methPart(fun)},\n  methPart(fun).tpe = ${methPart(fun).tpe},\n  tpe = ${fun.tpe}")

  def typedNamedArgs(args: List[untpd.Tree])(using Context): List[NamedArg] =
    for (arg @ NamedArg(id, argtpt) <- args) yield {
      val argtpt1 = typedType(argtpt)
      cpy.NamedArg(arg)(id, argtpt1).withType(argtpt1.tpe)
    }

  def typedTypeApply(tree: untpd.TypeApply, pt: Type)(using Context): Tree = {
    if (ctx.mode.is(Mode.Pattern))
      return errorTree(tree, "invalid pattern")

    val isNamed = hasNamedArg(tree.args)
    val typedArgs = if (isNamed) typedNamedArgs(tree.args) else tree.args.mapconserve(typedType(_))
    record("typedTypeApply")
    typedFunPart(tree.fun, PolyProto(typedArgs, pt)) match {
      case IntegratedTypeArgs(app) =>
        app
      case _: TypeApply if !ctx.isAfterTyper =>
        errorTree(tree, "illegal repeated type application")
      case typedFn =>
        typedFn.tpe.widen match {
          case pt: PolyType =>
            if (typedArgs.length <= pt.paramInfos.length && !isNamed)
              if (typedFn.symbol == defn.Predef_classOf && typedArgs.nonEmpty) {
                val arg = typedArgs.head
                if (!arg.symbol.is(Module)) // Allow `classOf[Foo.type]` if `Foo` is an object
                  checkClassType(arg.tpe, arg.sourcePos, traitReq = false, stablePrefixReq = false)
              }
          case _ =>
        }
        def tryDynamicTypeApply(): Tree = typedFn match {
          case typedFn: Select if !pt.isInstanceOf[FunProto] => typedDynamicSelect(typedFn, typedArgs, pt)
          case _                                             => tree.withType(TryDynamicCallType)
        }
        if (typedFn.tpe eq TryDynamicCallType) tryDynamicTypeApply()
        else assignType(cpy.TypeApply(tree)(typedFn, typedArgs), typedFn, typedArgs)
    }
  }

  /** Rewrite `new Array[T](....)` if T is an unbounded generic to calls to newGenericArray.
   *  It is performed during typer as creation of generic arrays needs a classTag.
   *  we rely on implicit search to find one.
   */
  def convertNewGenericArray(tree: Tree)(using Context):  Tree = tree match {
    case Apply(TypeApply(tycon, targs@(targ :: Nil)), args) if tycon.symbol == defn.ArrayConstructor =>
      fullyDefinedType(tree.tpe, "array", tree.span)

      def newGenericArrayCall =
        ref(defn.DottyArraysModule)
          .select(defn.newGenericArrayMethod).withSpan(tree.span)
          .appliedToTypeTrees(targs).appliedToArgs(args)

      if (TypeErasure.isGeneric(targ.tpe))
        newGenericArrayCall
      else tree
    case _ =>
      tree
  }

  /** Does `state` contain a  "NotAMember" or "MissingIdent" message as
   *  first pending error message? That message would be
   *  `$memberName is not a member of ...` or `Not found: $memberName`.
   *  If memberName is empty, any name will do.
   */
  def saysNotFound(state: TyperState, memberName: Name)(using Context): Boolean =
    state.reporter.pendingMessages match
      case dia :: _ =>
        dia.msg match
          case msg: NotFoundMsg => memberName.isEmpty || msg.name == memberName
          case _ => false
      case _ => false

  def typedUnApply(tree: untpd.Apply, selType: Type)(using Context): Tree = {
    record("typedUnApply")
    val Apply(qual, args) = tree

    def notAnExtractor(tree: Tree): Tree =
      // prefer inner errors
      // e.g. report not found ident instead of not an extractor in tests/neg/i2950.scala
      if (!tree.tpe.isError && tree.tpe.isErroneous) tree
      else errorTree(tree, NotAnExtractor(qual))

    /** Report errors buffered in state.
     *  @pre state has errors to report
     *  If there is a single error stating that "unapply" is not a member, print
     *  the more informative "notAnExtractor" message instead.
     */
    def reportErrors(tree: Tree, state: TyperState): Tree =
      assert(state.reporter.hasErrors)
      if saysNotFound(state, nme.unapply) then notAnExtractor(tree)
      else
        state.reporter.flush()
        tree

    /** If this is a term ref tree, try to typecheck with its type name.
     *  If this refers to a type alias, follow the alias, and if
     *  one finds a class, reference the class companion module.
     */
    def followTypeAlias(tree: untpd.Tree): untpd.Tree = {
      tree match {
        case tree: untpd.RefTree =>
          val nestedCtx = ctx.fresh.setNewTyperState()
          val ttree =
            typedType(untpd.rename(tree, tree.name.toTypeName))(using nestedCtx)
          ttree.tpe match {
            case alias: TypeRef if alias.info.isTypeAlias && !nestedCtx.reporter.hasErrors =>
              companionRef(alias) match {
                case companion: TermRef => return untpd.ref(companion).withSpan(tree.span)
                case _ =>
              }
            case _ =>
          }
        case _ =>
      }
      untpd.EmptyTree
    }

    /** A typed qual.unapply or qual.unapplySeq tree, if this typechecks.
     *  Otherwise fallBack with (maltyped) qual.unapply as argument
     *  Note: requires special handling for overloaded occurrences of
     *  unapply or unapplySeq. We first try to find a non-overloaded
     *  method which matches any type. If that fails, we try to find an
     *  overloaded variant which matches one of the argument types.
     *  In fact, overloaded unapply's are problematic because a non-
     *  overloaded unapply does *not* need to be applicable to its argument
     *  whereas overloaded variants need to have a conforming variant.
     */
    def trySelectUnapply(qual: untpd.Tree)(fallBack: (Tree, TyperState) => Tree): Tree = {
      // try first for non-overloaded, then for overloaded ocurrences
      def tryWithName(name: TermName)(fallBack: (Tree, TyperState) => Tree)(using Context): Tree = {
        def tryWithProto(pt: Type)(using Context) = {
          val result = typedExpr(untpd.Select(qual, name), new UnapplyFunProto(pt, this))
          if !result.symbol.exists
             || result.symbol.name == name
             || ctx.reporter.hasErrors
          then result
          else notAnExtractor(result)
          	// It might be that the result of typedExpr is an `apply` selection or implicit conversion.
          	// Reject in this case.
        }
        tryEither {
          tryWithProto(selType)
        } {
          (sel, state) =>
            tryEither {
              tryWithProto(WildcardType)
            } {
              (_, _) => fallBack(sel, state)
            }
        }
      }

      // try first for unapply, then for unapplySeq
      tryWithName(nme.unapply) {
        (sel, state) =>
          tryWithName(nme.unapplySeq) {
            (sel2, state2) =>
              // if both fail, return unapply error, unless that is simply a
              // "not a member", and the unapplySeq error is more refined.
              if saysNotFound(state, nme.unapply) && !saysNotFound(state2, nme.unapplySeq)
              then fallBack(sel2, state2)
              else fallBack(sel, state)
          }
      }
    }

    /** Produce a typed qual.unapply or qual.unapplySeq tree, or
     *  else if this fails follow a type alias and try again.
     */
    var unapplyFn =
      trySelectUnapply(qual) {
        (sel, state) =>
          val qual1 = followTypeAlias(qual)
          if (qual1.isEmpty) reportErrors(sel, state)
          else trySelectUnapply(qual1) {
            (_, state) => reportErrors(sel, state)
          }
      }

    /** Add a `Bind` node for each `bound` symbol in a type application `unapp` */
    def addBinders(unapp: Tree, bound: List[Symbol]) = unapp match {
      case TypeApply(fn, args) =>
        var remain = bound.toSet
        def addBinder(arg: Tree) = arg.tpe.stripTypeVar match {
          case ref: TypeRef if remain.contains(ref.symbol) =>
            remain -= ref.symbol
            tpd.Bind(ref.symbol, Ident(ref))
          case _ =>
            arg
        }
        tpd.cpy.TypeApply(unapp)(fn, args.mapConserve(addBinder))
      case _ =>
        unapp
    }

    def fromScala2x = unapplyFn.symbol.exists && (unapplyFn.symbol.owner is Scala2x)

    unapplyFn.tpe.widen match {
      case mt: MethodType if mt.paramInfos.length == 1 =>
        val unapplyArgType = mt.paramInfos.head
        unapp.println(i"unapp arg tpe = $unapplyArgType, pt = $selType")
        val ownType =
          if (selType <:< unapplyArgType) {
            unapp.println(i"case 1 $unapplyArgType ${ctx.typerState.constraint}")
            fullyDefinedType(unapplyArgType, "pattern selector", tree.span)
            selType.dropAnnot(defn.UncheckedAnnot) // need to drop @unchecked. Just because the selector is @unchecked, the pattern isn't.
          }
          else {
            // We ignore whether constraining the pattern succeeded.
            // Constraining only fails if the pattern cannot possibly match,
            // but useless pattern checks detect more such cases, so we simply rely on them instead.
            ctx.addMode(Mode.GadtConstraintInference).typeComparer.constrainPatternType(unapplyArgType, selType)
            val patternBound = maximizeType(unapplyArgType, tree.span, fromScala2x)
            if (patternBound.nonEmpty) unapplyFn = addBinders(unapplyFn, patternBound)
            unapp.println(i"case 2 $unapplyArgType ${ctx.typerState.constraint}")
            unapplyArgType
          }
        val dummyArg = dummyTreeOfType(ownType)
        val unapplyApp = typedExpr(untpd.TypedSplice(Apply(unapplyFn, dummyArg :: Nil)))
        def unapplyImplicits(unapp: Tree): List[Tree] = unapp match {
          case Apply(Apply(unapply, `dummyArg` :: Nil), args2) => assert(args2.nonEmpty); args2
          case Apply(unapply, `dummyArg` :: Nil) => Nil
          case Inlined(u, _, _) => unapplyImplicits(u)
          case _ => Nil.assertingErrorsReported
        }

        var argTypes = unapplyArgs(unapplyApp.tpe, unapplyFn, args, tree.sourcePos)
        for (argType <- argTypes) assert(!isBounds(argType), unapplyApp.tpe.show)
        val bunchedArgs = argTypes match {
          case argType :: Nil =>
            if (args.lengthCompare(1) > 0 && Feature.autoTuplingEnabled) untpd.Tuple(args) :: Nil
            else args
          case _ => args
        }
        if (argTypes.length != bunchedArgs.length) {
          ctx.error(UnapplyInvalidNumberOfArguments(qual, argTypes), tree.sourcePos)
          argTypes = argTypes.take(args.length) ++
            List.fill(argTypes.length - args.length)(WildcardType)
        }
        val unapplyPatterns = bunchedArgs.lazyZip(argTypes) map (typed(_, _))
        val result = assignType(cpy.UnApply(tree)(unapplyFn, unapplyImplicits(unapplyApp), unapplyPatterns), ownType)
        unapp.println(s"unapply patterns = $unapplyPatterns")
        if ((ownType eq selType) || ownType.isError) result
        else tryWithClassTag(Typed(result, TypeTree(ownType)), selType)
      case tp =>
        val unapplyErr = if (tp.isError) unapplyFn else notAnExtractor(unapplyFn)
        val typedArgsErr = args mapconserve (typed(_, defn.AnyType))
        cpy.UnApply(tree)(unapplyErr, Nil, typedArgsErr) withType unapplyErr.tpe
    }
  }

  /** A typed unapply hook, can be overridden by re any-typers between frontend
   *  and pattern matcher.
   */
  def typedUnApply(tree: untpd.UnApply, selType: Type)(using Context): UnApply =
    throw new UnsupportedOperationException("cannot type check an UnApply node")

  /** Is given method reference applicable to argument trees `args`?
   *  @param  resultType   The expected result type of the application
   */
  def isApplicableMethodRef(methRef: TermRef, args: List[Tree], resultType: Type, keepConstraint: Boolean)(using Context): Boolean = {
    def isApp(using Context): Boolean =
      new ApplicableToTrees(methRef, args, resultType).success
    if (keepConstraint) isApp else ctx.test(isApp)
  }

  /** Is given method reference applicable to argument trees `args` without inferring views?
    *  @param  resultType   The expected result type of the application
    */
  def isDirectlyApplicableMethodRef(methRef: TermRef, args: List[Tree], resultType: Type)(using Context): Boolean =
    ctx.test(new ApplicableToTreesDirectly(methRef, args, resultType).success)

  /** Is given method reference applicable to argument types `args`?
   *  @param  resultType   The expected result type of the application
   */
  def isApplicableMethodRef(methRef: TermRef, args: List[Type], resultType: Type)(using Context): Boolean =
    ctx.test(new ApplicableToTypes(methRef, args, resultType).success)

  /** Is given type applicable to argument trees `args`, possibly after inserting an `apply`?
   *  @param  resultType   The expected result type of the application
   */
  def isApplicableType(tp: Type, args: List[Tree], resultType: Type, keepConstraint: Boolean)(using Context): Boolean =
    onMethod(tp, args.nonEmpty) {
      isApplicableMethodRef(_, args, resultType, keepConstraint)
    }

  /** Is given type applicable to argument types `args`, possibly after inserting an `apply`?
   *  @param  resultType   The expected result type of the application
   */
  def isApplicableType(tp: Type, args: List[Type], resultType: Type)(using Context): Boolean =
    onMethod(tp, args.nonEmpty) {
      isApplicableMethodRef(_, args, resultType)
    }

  private def onMethod(tp: Type, followApply: Boolean)(p: TermRef => Boolean)(using Context): Boolean = tp match {
    case methRef: TermRef if methRef.widenSingleton.isInstanceOf[MethodicType] =>
      p(methRef)
    case mt: MethodicType =>
      p(mt.narrow)
    case _ =>
      followApply && tp.member(nme.apply).hasAltWith(d => p(TermRef(tp, nme.apply, d)))
  }

  /** Does `tp` have an extension method named `name` with this-argument `argType` and
   *  result matching `resultType`?
   */
  def hasExtensionMethod(tp: Type, name: TermName, argType: Type, resultType: Type)(using Context) = {
    def qualifies(mbr: Denotation) =
      mbr.exists && isApplicableType(tp.select(name, mbr), argType :: Nil, resultType)
    tp.memberBasedOnFlags(name, required = ExtensionMethod) match {
      case mbr: SingleDenotation => qualifies(mbr)
      case mbr => mbr.hasAltWith(qualifies(_))
    }
  }

  /** Drop any implicit parameter section */
  def stripImplicit(tp: Type)(using Context): Type = tp match {
    case mt: MethodType if mt.isImplicitMethod =>
      stripImplicit(resultTypeApprox(mt))
    case pt: PolyType =>
      pt.derivedLambdaType(pt.paramNames, pt.paramInfos, stripImplicit(pt.resultType))
    case _ =>
      tp
  }

  /** Compare owner inheritance level.
    *  @param    sym1 The first owner
    *  @param    sym2 The second owner
    *  @return    1   if `sym1` properly derives from `sym2`
    *            -1   if `sym2` properly derives from `sym1`
    *             0   otherwise
    *  Module classes also inherit the relationship from their companions.
    */
  def compareOwner(sym1: Symbol, sym2: Symbol)(using Context): Int =
    if (sym1 == sym2) 0
    else if (sym1 isSubClass sym2) 1
    else if (sym2 isSubClass sym1) -1
    else if (sym2.is(Module)) compareOwner(sym1, sym2.companionClass)
    else if (sym1.is(Module)) compareOwner(sym1.companionClass, sym2)
    else 0

  /** Compare to alternatives of an overloaded call or an implicit search.
   *
   *  @param  alt1, alt2      Non-overloaded references indicating the two choices
   *  @return  1   if 1st alternative is preferred over 2nd
   *          -1   if 2nd alternative is preferred over 1st
   *           0   if neither alternative is preferred over the other
   *
   *  An alternative A1 is preferred over an alternative A2 if it wins in a tournament
   *  that awards one point for each of the following:
   *
   *   - A1's owner derives from A2's owner.
   *   - A1's type is more specific than A2's type.
   *
   *  If that tournament yields a draw, a tiebreak is applied where
   *  an alternative that takes more implicit parameters wins over one
   *  that takes fewer.
   */
  def compare(alt1: TermRef, alt2: TermRef)(using Context): Int = trace(i"compare($alt1, $alt2)", overload) {
    record("compare")
    assert(alt1 ne alt2)

    /** Is alternative `alt1` with type `tp1` as specific as alternative
     *  `alt2` with type `tp2` ?
     *
     *    1. A method `alt1` of type (p1: T1, ..., pn: Tn)U is as specific as `alt2`
     *       if `alt2` is applicable to arguments (p1, ..., pn) of types T1,...,Tn
     *       or if `alt1` is nullary.
     *    2. A polymorphic member of type [a1 >: L1 <: U1, ..., an >: Ln <: Un]T is as
     *       specific as `alt2` of type `tp2` if T is as specific as `tp2` under the
     *       assumption that for i = 1,...,n each ai is an abstract type name bounded
     *       from below by Li and from above by Ui.
     *    3. A member of any other type `tp1` is:
     *       a. always as specific as a method or a polymorphic method.
     *       b. as specific as a member of any other type `tp2` if `tp1` is compatible
     *          with `tp2`.
     */
    def isAsSpecific(alt1: TermRef, tp1: Type, alt2: TermRef, tp2: Type): Boolean = trace(i"isAsSpecific $tp1 $tp2", overload) { tp1 match {
      case tp1: MethodType => // (1)
        val formals1 =
          if (tp1.isVarArgsMethod && tp2.isVarArgsMethod) tp1.paramInfos.map(_.repeatedToSingle)
          else tp1.paramInfos
        isApplicableMethodRef(alt2, formals1, WildcardType) ||
        tp1.paramInfos.isEmpty && tp2.isInstanceOf[LambdaType]
      case tp1: PolyType => // (2)
        val nestedCtx = ctx.fresh.setExploreTyperState()
        locally {
          implicit val ctx = nestedCtx

          // Fully define the PolyType parameters so that the infos of the
          // tparams created below never contain TypeRefs whose underling types
          // contain uninstantiated TypeVars, this could lead to cycles in
          // `isSubType` as a TypeVar might get constrained by a TypeRef it's
          // part of.
          val tp1Params = tp1.newLikeThis(tp1.paramNames, tp1.paramInfos, defn.AnyType)
          fullyDefinedType(tp1Params, "type parameters of alternative", alt1.symbol.span)

          val tparams = ctx.newTypeParams(alt1.symbol, tp1.paramNames, EmptyFlags, tp1.instantiateParamInfos(_))
          isAsSpecific(alt1, tp1.instantiate(tparams.map(_.typeRef)), alt2, tp2)
        }
      case _ => // (3)
        tp2 match {
          case tp2: MethodType => true // (3a)
          case tp2: PolyType if tp2.resultType.isInstanceOf[MethodType] => true // (3a)
          case tp2: PolyType => // (3b)
            ctx.test(isAsSpecificValueType(tp1, constrained(tp2).resultType))
          case _ => // (3b)
            isAsSpecificValueType(tp1, tp2)
        }
    }}

    /** Test whether value type `tp1` is as specific as value type `tp2`.
     *  Let's abbreviate this to `tp1 <:s tp2`.
     *  Previously, `<:s` was the same as `<:`. This behavior is still
     *  available under mode `Mode.OldOverloadingResolution`. The new behavior
     *  is different, however. Here, `T <:s U` iff
     *
     *    flip(T) <: flip(U)
     *
     *  where `flip` changes covariant occurrences of contravariant type parameters to
     *  covariant ones. Intuitively `<:s` means subtyping `<:`, except that all arguments
     *  to contravariant parameters are compared as if they were covariant. E.g. given class
     *
     *     class Cmp[-X]
     *
     *  `Cmp[T] <:s Cmp[U]` if `T <: U`. On the other hand, non-variant occurrences
     *  of parameters are not affected. So `T <: U` would imply `Set[Cmp[U]] <:s Set[Cmp[T]]`,
     *  as usual, because `Set` is non-variant.
     *
     *  This relation might seem strange, but it models closely what happens for methods.
     *  Indeed, if we integrate the existing rules for methods into `<:s` we have now that
     *
     *     (T)R  <:s  (U)R
     *
     *  iff
     *
     *     T => R  <:s  U => R
     *
     *  Also: If a compared type refers to a given or its module class, use
     *  the intersection of its parent classes instead.
     */
    def isAsSpecificValueType(tp1: Type, tp2: Type)(using Context) =
      if (ctx.mode.is(Mode.OldOverloadingResolution))
        isCompatible(tp1, tp2)
      else {
        val flip = new TypeMap {
          def apply(t: Type) = t match {
            case t @ AppliedType(tycon, args) =>
              def mapArg(arg: Type, tparam: TypeParamInfo) =
                if (variance > 0 && tparam.paramVarianceSign < 0) defn.FunctionOf(arg :: Nil, defn.UnitType)
                else arg
              mapOver(t.derivedAppliedType(tycon, args.zipWithConserve(tycon.typeParams)(mapArg)))
            case _ => mapOver(t)
          }
        }
        def prepare(tp: Type) = tp.stripTypeVar match {
          case tp: NamedType if tp.symbol.is(Module) && tp.symbol.sourceModule.is(Given) =>
            flip(tp.widen.widenToParents)
          case _ => flip(tp)
        }
        (prepare(tp1) relaxed_<:< prepare(tp2)) || viewExists(tp1, tp2)
      }

    /** Widen the result type of synthetic given methods from the implementation class to the
     *  type that's implemented. Example
     *
     *      given I[X] as T { ... }
     *
     *  This desugars to
     *
     *      class I[X] extends T { ... }
     *      implicit def I[X]: I[X] = new I[X]
     *
     *  To compare specificity we should compare with `T`, not with its implementation `I[X]`.
     *  No such widening is performed for given aliases, which are not synthetic. E.g.
     *
     *      given J[X] as T = rhs
     *
     *  already has the right result type `T`. Neither is widening performed for given
     *  objects, since these are anyway taken to be more specific than methods
     *  (by condition 3a above).
     */
    def widenGiven(tp: Type, alt: TermRef): Type = tp match {
      case mt: MethodType if mt.isImplicitMethod =>
        mt.derivedLambdaType(mt.paramNames, mt.paramInfos, widenGiven(mt.resultType, alt))
      case pt: PolyType =>
        pt.derivedLambdaType(pt.paramNames, pt.paramInfos, widenGiven(pt.resultType, alt))
      case _ =>
        if (alt.symbol.isAllOf(SyntheticGivenMethod)) tp.widenToParents
        else tp
    }

    def compareWithTypes(tp1: Type, tp2: Type) = {
      val ownerScore = compareOwner(alt1.symbol.maybeOwner, alt2.symbol.maybeOwner)
      def winsType1 = isAsSpecific(alt1, tp1, alt2, tp2)
      def winsType2 = isAsSpecific(alt2, tp2, alt1, tp1)

      overload.println(i"compare($alt1, $alt2)? $tp1 $tp2 $ownerScore $winsType1 $winsType2")
      if (ownerScore == 1)
        if (winsType1 || !winsType2) 1 else 0
      else if (ownerScore == -1)
        if (winsType2 || !winsType1) -1 else 0
      else if (winsType1)
        if (winsType2) 0 else 1
      else
        if (winsType2) -1 else 0
    }

    val fullType1 = widenGiven(alt1.widen, alt1)
    val fullType2 = widenGiven(alt2.widen, alt2)
    val strippedType1 = stripImplicit(fullType1)
    val strippedType2 = stripImplicit(fullType2)

    val result = compareWithTypes(strippedType1, strippedType2)
    if (result != 0) result
    else if (strippedType1 eq fullType1)
      if (strippedType2 eq fullType2) 0         // no implicits either side: its' a draw
      else 1                                    // prefer 1st alternative with no implicits
    else if (strippedType2 eq fullType2) -1     // prefer 2nd alternative with no implicits
    else compareWithTypes(fullType1, fullType2) // continue by comparing implicits parameters
  }

  def narrowMostSpecific(alts: List[TermRef])(using Context): List[TermRef] = {
    record("narrowMostSpecific")
    alts match {
      case Nil => alts
      case _ :: Nil => alts
      case alt1 :: alt2 :: Nil =>
        compare(alt1, alt2) match {
          case  1 => alt1 :: Nil
          case -1 => alt2 :: Nil
          case  0 => alts
        }
      case alt :: alts1 =>
        def survivors(previous: List[TermRef], alts: List[TermRef]): List[TermRef] = alts match {
          case alt :: alts1 =>
            compare(previous.head, alt) match {
              case  1 => survivors(previous, alts1)
              case -1 => survivors(alt :: previous.tail, alts1)
              case  0 => survivors(alt :: previous, alts1)
            }
          case Nil => previous
        }
        val best :: rest = survivors(alt :: Nil, alts1)
        def asGood(alts: List[TermRef]): List[TermRef] = alts match {
          case alt :: alts1 =>
            if (compare(alt, best) < 0) asGood(alts1) else alt :: asGood(alts1)
          case nil =>
            Nil
        }
        best :: asGood(rest)
    }
  }

  /** Resolve overloaded alternative `alts`, given expected type `pt`.
   *  Two trials: First, without implicits or SAM conversions enabled. Then,
   *  if the first finds no eligible candidates, with implicits and SAM conversions enabled.
   */
  def resolveOverloaded(alts: List[TermRef], pt: Type)(using Context): List[TermRef] =
    record("resolveOverloaded")

    /** Is `alt` a method or polytype whose result type after the first value parameter
     *  section conforms to the expected type `resultType`? If `resultType`
     *  is a `IgnoredProto`, pick the underlying type instead.
     */
    def resultConforms(altSym: Symbol, altType: Type, resultType: Type)(using Context): Boolean =
      resultType.revealIgnored match {
        case resultType: ValueType =>
          altType.widen match {
            case tp: PolyType => resultConforms(altSym, constrained(tp).resultType, resultType)
            case tp: MethodType => constrainResult(altSym, tp.resultType, resultType)
            case _ => true
          }
        case _ => true
      }

    /** If the `chosen` alternative has a result type incompatible with the expected result
     *  type `pt`, run overloading resolution again on all alternatives that do match `pt`.
     *  If the latter succeeds with a single alternative, return it, otherwise
     *  fallback to `chosen`.
     *
     *  Note this order of events is done for speed. One might be tempted to
     *  preselect alternatives by result type. But this is slower, because it discriminates
     *  less. The idea is when searching for a best solution, as is the case in overloading
     *  resolution, we should first try criteria which are cheap and which have a high
     *  probability of pruning the search. result type comparisons are neither cheap nor
     *  do they prune much, on average.
     */
    def adaptByResult(chosen: TermRef, alts: List[TermRef]) = pt match {
      case pt: FunProto if !ctx.test(resultConforms(chosen.symbol, chosen, pt.resultType)) =>
        val conformingAlts = alts.filter(alt =>
          (alt ne chosen) && ctx.test(resultConforms(alt.symbol, alt, pt.resultType)))
        conformingAlts match {
          case Nil => chosen
          case alt2 :: Nil => alt2
          case alts2 =>
            resolveOverloaded(alts2, pt) match {
              case alt2 :: Nil => alt2
              case _ => chosen
            }
        }
      case _ => chosen
    }

    def resolve(alts: List[TermRef]): List[TermRef] =
      pt match
        case pt: FunProto =>
          if pt.isUsingApply then
            val alts0 = alts.filterConserve { alt =>
              val mt = alt.widen.stripPoly
              mt.isImplicitMethod || mt.isContextualMethod
            }
            if alts0 ne alts then return resolve(alts0)
          else if alts.exists(_.widen.stripPoly.isContextualMethod) then
            return resolveMapped(alts, alt => stripImplicit(alt.widen), pt)
        case _ =>

      var found = resolveOverloaded1(alts, pt)(using ctx.retractMode(Mode.ImplicitsEnabled))
      if found.isEmpty && ctx.mode.is(Mode.ImplicitsEnabled) then
        found = resolveOverloaded1(alts, pt)
      found match
        case alt :: Nil => adaptByResult(alt, alts) :: Nil
        case _ => found
    end resolve

    /** Try an apply method, if
     *   - the result is applied to value arguments and alternative is not a method, or
     *   - the result is applied to type arguments and alternative is not polymorphic
     */
    val tryApply: Type => Boolean = alt => pt match {
      case pt: FunProto => !alt.widen.stripPoly.isInstanceOf[MethodType]
      case pt: PolyProto => !alt.widen.isInstanceOf[PolyType]
      case _ => false
    }

    /** Replace each alternative by its apply members where necessary */
    def applyMembers(alt: TermRef): List[TermRef] =
      if (tryApply(alt)) {
        val qual = alt.widen match {
          case pt: PolyType =>
            wildApprox(pt.resultType)
          case _ =>
            alt
        }
        qual.member(nme.apply).alternatives.map(TermRef(alt, nme.apply, _))
      }
      else alt :: Nil

    /** Fall back from an apply method to its original alternative */
    def retract(alt: TermRef): TermRef =
      if (alt.name == nme.apply && !alts.contains(alt))
        alts.find(_.symbol == alt.prefix.termSymbol).getOrElse(alt)
      else alt

    if (alts.exists(tryApply)) {
      val expanded = alts.flatMap(applyMembers)
      resolve(expanded).map(retract)
    }
    else resolve(alts)
  end resolveOverloaded

  /** This private version of `resolveOverloaded` does the bulk of the work of
   *  overloading resolution, but does neither result adaptation nor apply insertion.
   *  It might be called twice from the public `resolveOverloaded` method, once with
   *  implicits and SAM conversions enabled, and once without.
   */
  private def resolveOverloaded1(alts: List[TermRef], pt: Type)(using Context): List[TermRef] =
    trace(i"resolve over $alts%, %, pt = $pt", typr, show = true) {
    record("resolveOverloaded1")

    def isDetermined(alts: List[TermRef]) = alts.isEmpty || alts.tail.isEmpty

    /** The shape of given tree as a type; cannot handle named arguments. */
    def typeShape(tree: untpd.Tree): Type = tree match {
      case untpd.Function(args, body) =>
        defn.FunctionOf(args map Function.const(defn.AnyType), typeShape(body))
      case Match(EmptyTree, _) =>
        defn.PartialFunctionClass.typeRef.appliedTo(defn.AnyType :: defn.NothingType :: Nil)
      case _ =>
        defn.NothingType
    }

    /** The shape of given tree as a type; is more expensive than
     *  typeShape but can can handle named arguments.
     */
    def treeShape(tree: untpd.Tree): Tree = tree match {
      case NamedArg(name, arg) =>
        val argShape = treeShape(arg)
        cpy.NamedArg(tree)(name, argShape).withType(argShape.tpe)
      case _ =>
        dummyTreeOfType(typeShape(tree))
    }

    def narrowByTypes(alts: List[TermRef], argTypes: List[Type], resultType: Type): List[TermRef] =
      alts filter (isApplicableMethodRef(_, argTypes, resultType))

    /** Normalization steps before checking arguments:
     *
     *                 { expr }   -->   expr
     *    (x1, ..., xn) => expr   -->   ((x1, ..., xn)) => expr
     *       if n != 1, no alternative has a corresponding formal parameter that
     *       is an n-ary function, and at least one alternative has a corresponding
     *       formal parameter that is a unary function.
     */
    def normArg(alts: List[TermRef], arg: untpd.Tree, idx: Int): untpd.Tree = arg match
      case Block(Nil, expr) => normArg(alts, expr, idx)
      case untpd.Function(args: List[untpd.ValDef] @unchecked, body) =>

        // If ref refers to a method whose parameter at index `idx` is a function type,
        // the arity of that function, otherise -1.
        def paramCount(ref: TermRef) =
          val formals = ref.widen.firstParamTypes
          if formals.length > idx then
            formals(idx) match
              case defn.FunctionOf(args, _, _, _) => args.length
              case _ => -1
          else -1

        val numArgs = args.length
        if numArgs != 1
           && !alts.exists(paramCount(_) == numArgs)
           && alts.exists(paramCount(_) == 1)
        then
          desugar.makeTupledFunction(args, body, isGenericTuple = true)
            // `isGenericTuple = true` is the safe choice here. It means the i'th tuple
            // element is selected with `(i)` instead of `_i`, which gives the same code
            // in the end, but the compilation time and the ascribed type are more involved.
            // It also means that -Ytest-pickler -Xprint-types fails for sources exercising
            // the idiom since after pickling the target is known, so _i is used directly.
        else arg
      case _ => arg
    end normArg

    val candidates = pt match {
      case pt @ FunProto(args, resultType) =>
        val numArgs = args.length
        def sizeFits(alt: TermRef): Boolean = alt.widen.stripPoly match {
          case tp: MethodType =>
            val ptypes = tp.paramInfos
            val numParams = ptypes.length
            def isVarArgs = ptypes.nonEmpty && ptypes.last.isRepeatedParam
            def hasDefault = alt.symbol.hasDefaultParams
            if (numParams == numArgs) true
            else if (numParams < numArgs) isVarArgs
            else if (numParams > numArgs + 1) hasDefault
            else isVarArgs || hasDefault
          case _ =>
            numArgs == 0
        }

        def narrowBySize(alts: List[TermRef]): List[TermRef] =
          alts.filter(sizeFits(_))

        def narrowByShapes(alts: List[TermRef]): List[TermRef] =
          val normArgs = args.mapWithIndexConserve(normArg(alts, _, _))
          if normArgs.exists(untpd.isFunctionWithUnknownParamType) then
            if hasNamedArg(args) then narrowByTrees(alts, normArgs.map(treeShape), resultType)
            else narrowByTypes(alts, normArgs.map(typeShape), resultType)
          else
            alts

        def narrowByTrees(alts: List[TermRef], args: List[Tree], resultType: Type): List[TermRef] = {
          val alts2 = alts.filter(alt =>
            isDirectlyApplicableMethodRef(alt, args, resultType)
          )
          if (alts2.isEmpty && !ctx.isAfterTyper)
            alts.filter(alt =>
              isApplicableMethodRef(alt, args, resultType, keepConstraint = false)
            )
          else
            alts2
        }

        val alts1 = narrowBySize(alts)
        //ctx.log(i"narrowed by size: ${alts1.map(_.symbol.showDcl)}%, %")
        if isDetermined(alts1) then alts1
        else
          val alts2 = narrowByShapes(alts1)
          //ctx.log(i"narrowed by shape: ${alts2.map(_.symbol.showDcl)}%, %")
          if isDetermined(alts2) then alts2
          else
            pretypeArgs(alts2, pt)
            narrowByTrees(alts2, pt.typedArgs(normArg(alts2, _, _)), resultType)

      case pt @ PolyProto(targs1, pt1) =>
        val alts1 = alts.filter(pt.canInstantiate)
        if isDetermined(alts1) then alts1
        else resolveMapped(alts1, _.widen.appliedTo(targs1.tpes), pt1)

      case defn.FunctionOf(args, resultType, _, _) =>
        narrowByTypes(alts, args, resultType)

      case pt =>
        val compat = alts.filter(normalizedCompatible(_, pt, keepConstraint = false))
        if (compat.isEmpty)
          /*
           * the case should not be moved to the enclosing match
           * since SAM type must be considered only if there are no candidates
           * For example, the second f should be chosen for the following code:
           *   def f(x: String): Unit = ???
           *   def f: java.io.OutputStream = ???
           *   new java.io.ObjectOutputStream(f)
           */
          pt match {
            case SAMType(mtp) => narrowByTypes(alts, mtp.paramInfos, mtp.resultType)
            case _ => compat
          }
        else compat
    }

    /** The type of alternative `alt` after instantiating its first parameter
     *  clause with `argTypes`.
     */
    def skipParamClause(argTypes: List[Type])(alt: TermRef): Type =
      def skip(tp: Type): Type = tp match {
        case tp: PolyType =>
          val rt = skip(tp.resultType)
          if (rt.exists) tp.derivedLambdaType(resType = rt) else rt
        case tp: MethodType =>
          tp.instantiate(argTypes)
        case _ =>
          NoType
      }
      skip(alt.widen)

    def resultIsMethod(tp: Type): Boolean = tp.widen.stripPoly match
      case tp: MethodType => stripImplicit(tp.resultType).isInstanceOf[MethodType]
      case _ => false

    val found = narrowMostSpecific(candidates)
    if (found.length <= 1) found
    else
      val deepPt = pt.deepenProto
      deepPt match
        case pt @ FunProto(_, resType: FunProto) =>
          // try to narrow further with snd argument list
          resolveMapped(candidates, skipParamClause(pt.typedArgs().tpes), resType)
        case _ =>
          // prefer alternatives that need no eta expansion
          val noCurried = alts.filter(!resultIsMethod(_))
          val noCurriedCount = noCurried.length
          if noCurriedCount == 1 then
            noCurried
          else if noCurriedCount > 1 && noCurriedCount < alts.length then
            resolveOverloaded1(noCurried, pt)
          else
            // prefer alternatves that match without default parameters
            val noDefaults = alts.filter(!_.symbol.hasDefaultParams)
            val noDefaultsCount = noDefaults.length
            if noDefaultsCount == 1 then
              noDefaults
            else if noDefaultsCount > 1 && noDefaultsCount < alts.length then
              resolveOverloaded1(noDefaults, pt)
            else if deepPt ne pt then
              // try again with a deeper known expected type
              resolveOverloaded1(alts, deepPt)
            else
              candidates
    }
  end resolveOverloaded1

  /** Resolve overloading by mapping to a different problem where each alternative's
   *  type is mapped with `f`, alternatives with non-existing types are dropped, and the
   *  expected type is `pt`. Map the results back to the original alternatives.
   */
  def resolveMapped(alts: List[TermRef], f: TermRef => Type, pt: Type)(using Context): List[TermRef] =
    val reverseMapping = alts.flatMap { alt =>
      val t = f(alt)
      if t.exists then
        Some((TermRef(NoPrefix, alt.symbol.asTerm.copy(info = t)), alt))
      else
        None
    }
    val mapped = reverseMapping.map(_._1)
    overload.println(i"resolve mapped: $mapped")
    resolveOverloaded(mapped, pt).map(reverseMapping.toMap)

  /** Try to typecheck any arguments in `pt` that are function values missing a
   *  parameter type. If the formal parameter types corresponding to a closure argument
   *  all agree on their argument types, typecheck the argument with an expected
   *  function or partial function type that contains these argument types,
   *  The result of the typecheck is stored in `pt`, to be retrieved when its `typedArgs` are selected.
   *  The benefit of doing this is to allow idioms like this:
   *
   *     def map(f: Char => Char): String = ???
   *     def map[U](f: Char => U): Seq[U] = ???
   *     map(x => x.toUpper)
   *
   *  Without `pretypeArgs` we'd get a "missing parameter type" error for `x`.
   *  With `pretypeArgs`, we use the `Char => ?` as the expected type of the
   *  closure `x => x.toUpper`, which makes the code typecheck.
   */
  private def pretypeArgs(alts: List[TermRef], pt: FunProto)(using Context): Unit = {
    def recur(altFormals: List[List[Type]], args: List[untpd.Tree]): Unit = args match {
      case arg :: args1 if !altFormals.exists(_.isEmpty) =>
        untpd.functionWithUnknownParamType(arg) match {
          case Some(fn) =>
            def isUniform[T](xs: List[T])(p: (T, T) => Boolean) = xs.forall(p(_, xs.head))
            val formalsForArg: List[Type] = altFormals.map(_.head)
            def argTypesOfFormal(formal: Type): List[Type] =
              formal match {
                case defn.FunctionOf(args, result, isImplicit, isErased) => args
                case defn.PartialFunctionOf(arg, result) => arg :: Nil
                case _ => Nil
              }
            val formalParamTypessForArg: List[List[Type]] =
              formalsForArg.map(argTypesOfFormal)
            if (formalParamTypessForArg.forall(_.nonEmpty) &&
                isUniform(formalParamTypessForArg)((x, y) => x.length == y.length)) {
              val commonParamTypes = formalParamTypessForArg.transpose.map(ps =>
                // Given definitions above, for i = 1,...,m,
                //   ps(i) = List(p_i_1, ..., p_i_n)  -- i.e. a column
                // If all p_i_k's are the same, assume the type as formal parameter
                // type of the i'th parameter of the closure.
                if (isUniform(ps)(_ frozen_=:= _)) ps.head
                else WildcardType)
              def isPartial = // we should generate a partial function for the arg
                fn.isInstanceOf[untpd.Match] &&
                formalsForArg.exists(_.isRef(defn.PartialFunctionClass))
              val commonFormal =
                if (isPartial) defn.PartialFunctionOf(commonParamTypes.head, WildcardType)
                else defn.FunctionOf(commonParamTypes, WildcardType)
              overload.println(i"pretype arg $arg with expected type $commonFormal")
              if (commonParamTypes.forall(isFullyDefined(_, ForceDegree.flipBottom)))
                pt.typedArg(arg, commonFormal)(using ctx.addMode(Mode.ImplicitsEnabled))
            }
          case None =>
        }
        recur(altFormals.map(_.tail), args1)
      case _ =>
    }
    recur(alts.map(_.widen.firstParamTypes), pt.args)
  }

  private def harmonizeWith[T <: AnyRef](ts: List[T])(tpe: T => Type, adapt: (T, Type) => T)(using Context): List[T] = {
    def targetClass(ts: List[T], cls: Symbol, intLitSeen: Boolean): Symbol = ts match {
      case t :: ts1 =>
        tpe(t).widenTermRefExpr match {
          case ConstantType(c: Constant) if c.tag == IntTag =>
            targetClass(ts1, cls, true)
          case t =>
            val sym = t.widen.classSymbol
            if (!sym.isNumericValueClass || cls.exists && cls != sym) NoSymbol
            else targetClass(ts1, sym, intLitSeen)
        }
      case Nil =>
        if (cls != defn.IntClass && intLitSeen) cls else NoSymbol
    }
    val cls = targetClass(ts, NoSymbol, false)
    if (cls.exists) {
      def lossOfPrecision(n: Int): Boolean =
        cls == defn.FloatClass && n.toFloat.toInt != n
      var canAdapt = true
      val ts1 = ts.mapConserve { t =>
        tpe(t).widenTermRefExpr match {
          case ConstantType(c: Constant) if c.tag == IntTag =>
            canAdapt &= c.convertTo(cls.typeRef) != null && !lossOfPrecision(c.intValue)
            if (canAdapt) adapt(t, cls.typeRef) else t
          case _ => t
        }
      }
      if (canAdapt) ts1 else ts
    }
    else ts
  }

  /** If `trees` all have numeric value types, and they do not have all the same type,
   *  pick a common numeric supertype and try to convert all constant Int literals to this type.
   *  If the resulting trees all have the same type, return them instead of the original ones.
   */
  def harmonize(trees: List[Tree])(using Context): List[Tree] = {
    def adaptDeep(tree: Tree, pt: Type): Tree = tree match {
      case cdef: CaseDef => tpd.cpy.CaseDef(cdef)(body = adaptDeep(cdef.body, pt))
      case _ => adapt(tree, pt)
    }
    if (ctx.isAfterTyper) trees else harmonizeWith(trees)(_.tpe, adaptDeep)
  }

  /** Apply a transformation `harmonize` on the results of operation `op`,
   *  unless the expected type `pt` is fully defined.
   *  If the result is different (wrt eq) from the original results of `op`,
   *  revert back to the constraint in force before computing `op`.
   *  This reset is needed because otherwise the original results might
   *  have added constraints to type parameters which are no longer
   *  implied after harmonization. No essential constraints are lost by this because
   *  the result of harmomization will be compared again with the expected type.
   *  Test cases where this matters are in pos/harmomize.scala.
   */
  def harmonic[T](harmonize: List[T] => List[T], pt: Type)(op: => List[T])(using Context): List[T] =
    if (!isFullyDefined(pt, ForceDegree.none)) {
      val origConstraint = ctx.typerState.constraint
      val origElems = op
      val harmonizedElems = harmonize(origElems)
      if (harmonizedElems ne origElems) ctx.typerState.constraint = origConstraint
      harmonizedElems
    }
    else op

  /** If all `types` are numeric value types, and they are not all the same type,
   *  pick a common numeric supertype and widen any constant types in `tpes` to it.
   *  If the resulting types are all the same, return them instead of the original ones.
   */
  private def harmonizeTypes(tpes: List[Type])(using Context): List[Type] =
    harmonizeWith(tpes)(identity, (tp, pt) => pt)

  /** The typed application
   *
   *   <methodRef>(<receiver>)    or
   *   <methodRef>[<type-args>](<receiver>)
   *
   *  where <type-args> comes from `pt` if it is a (possibly ignored) PolyProto.
   */
  def extMethodApply(methodRef: untpd.Tree, receiver: Tree, pt: Type)(using Context) = {
    /** Integrate the type arguments from `currentPt` into `methodRef`, and produce
     *  a matching expected type.
     *  If `currentPt` is ignored, the new expected type will be ignored too.
     */
    def integrateTypeArgs(currentPt: Type, wasIgnored: Boolean = false): (untpd.Tree, Type) = currentPt match {
      case IgnoredProto(ignored) =>
        integrateTypeArgs(ignored, wasIgnored = true)
      case PolyProto(targs, restpe) =>
        val core = untpd.TypeApply(methodRef, targs.map(untpd.TypedSplice(_)))
        (core, if (wasIgnored) IgnoredProto(restpe) else restpe)
      case _ =>
        (methodRef, pt)
    }
    val (core, pt1) = integrateTypeArgs(pt)
    val app =
      typed(untpd.Apply(core, untpd.TypedSplice(receiver) :: Nil), pt1, ctx.typerState.ownedVars)(
        using ctx.addMode(Mode.SynthesizeExtMethodReceiver))
    def isExtension(tree: Tree): Boolean = methPart(tree) match {
      case Inlined(call, _, _) => isExtension(call)
      case tree @ Select(qual, nme.apply) => tree.symbol.is(Extension) || isExtension(qual)
      case tree => tree.symbol.is(Extension)
    }
    if (!isExtension(app))
      ctx.error(em"not an extension method: $methodRef", receiver.sourcePos)
    app
  }
}
