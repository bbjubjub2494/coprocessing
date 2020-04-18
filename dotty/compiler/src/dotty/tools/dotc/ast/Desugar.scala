package dotty.tools
package dotc
package ast

import core._
import util.Spans._, Types._, Contexts._, Constants._, Names._, NameOps._, Flags._
import Symbols._, StdNames._, Trees._
import Decorators.{given _}, transform.SymUtils._
import NameKinds.{UniqueName, EvidenceParamName, DefaultGetterName}
import typer.{FrontEnd, Namer}
import util.{Property, SourceFile, SourcePosition}
import config.Feature.{sourceVersion, migrateTo3, enabled}
import config.SourceVersion._
import collection.mutable.ListBuffer
import reporting.messages._
import reporting.trace
import annotation.constructorOnly
import printing.Formatting.hl
import config.Printers

import scala.annotation.internal.sharable

object desugar {
  import untpd._
  import DesugarEnums._

  /** If a Select node carries this attachment, suppress the check
   *  that its type refers to an acessible symbol.
   */
  val SuppressAccessCheck: Property.Key[Unit] = Property.Key()

  /** An attachment for companion modules of classes that have a `derives` clause.
   *  The position value indicates the start position of the template of the
   *  deriving class.
   */
  val DerivingCompanion: Property.Key[SourcePosition] = Property.Key()

  /** An attachment for match expressions generated from a PatDef or GenFrom.
   *  Value of key == one of IrrefutablePatDef, IrrefutableGenFrom
   */
  val CheckIrrefutable: Property.Key[MatchCheck] = Property.StickyKey()

  /** A multi-line infix operation with the infix operator starting a new line.
   *  Used for explaining potential errors.
   */
  val MultiLineInfix: Property.Key[Unit] = Property.StickyKey()

  /** What static check should be applied to a Match? */
  enum MatchCheck {
    case None, Exhaustive, IrrefutablePatDef, IrrefutableGenFrom
  }

  /** Is `name` the name of a method that can be invalidated as a compiler-generated
   *  case class method if it clashes with a user-defined method?
   */
  def isRetractableCaseClassMethodName(name: Name)(implicit ctx: Context): Boolean = name match {
    case nme.apply | nme.unapply | nme.unapplySeq | nme.copy => true
    case DefaultGetterName(nme.copy, _) => true
    case _ => false
  }

  /** Is `name` the name of a method that is added unconditionally to case classes? */
  def isDesugaredCaseClassMethodName(name: Name)(implicit ctx: Context): Boolean =
    isRetractableCaseClassMethodName(name) || name.isSelectorName

// ----- DerivedTypeTrees -----------------------------------

  class SetterParamTree(implicit @constructorOnly src: SourceFile) extends DerivedTypeTree {
    def derivedTree(sym: Symbol)(implicit ctx: Context): tpd.TypeTree = tpd.TypeTree(sym.info.resultType)
  }

  class TypeRefTree(implicit @constructorOnly src: SourceFile) extends DerivedTypeTree {
    def derivedTree(sym: Symbol)(implicit ctx: Context): tpd.TypeTree = tpd.TypeTree(sym.typeRef)
  }

  class TermRefTree(implicit @constructorOnly src: SourceFile) extends DerivedTypeTree {
    def derivedTree(sym: Symbol)(implicit ctx: Context): tpd.Tree = tpd.ref(sym)
  }

  /** A type tree that computes its type from an existing parameter. */
  class DerivedFromParamTree()(implicit @constructorOnly src: SourceFile) extends DerivedTypeTree {

    /** Complete the appropriate constructors so that OriginalSymbol attachments are
     *  pushed to DerivedTypeTrees.
     */
    override def ensureCompletions(implicit ctx: Context): Unit = {
      def completeConstructor(sym: Symbol) =
        sym.infoOrCompleter match {
          case completer: Namer#ClassCompleter =>
            completer.completeConstructor(sym)
          case _ =>
        }

      if (!ctx.owner.is(Package))
        if (ctx.owner.isClass) {
          completeConstructor(ctx.owner)
          if (ctx.owner.is(ModuleClass))
            completeConstructor(ctx.owner.linkedClass)
        }
        else ensureCompletions(ctx.outer)
    }

    /** Return info of original symbol, where all references to siblings of the
     *  original symbol (i.e. sibling and original symbol have the same owner)
     *  are rewired to same-named parameters or accessors in the scope enclosing
     *  the current scope. The current scope is the scope owned by the defined symbol
     *  itself, that's why we have to look one scope further out. If the resulting
     *  type is an alias type, dealias it. This is necessary because the
     *  accessor of a type parameter is a private type alias that cannot be accessed
     *  from subclasses.
     */
    def derivedTree(sym: Symbol)(implicit ctx: Context): tpd.TypeTree = {
      val relocate = new TypeMap {
        val originalOwner = sym.owner
        def apply(tp: Type) = tp match {
          case tp: NamedType if tp.symbol.exists && (tp.symbol.owner eq originalOwner) =>
            val defctx = mapCtx.outersIterator.dropWhile(_.scope eq mapCtx.scope).next()
            var local = defctx.denotNamed(tp.name).suchThat(_.isParamOrAccessor).symbol
            if (local.exists) (defctx.owner.thisType select local).dealiasKeepAnnots
            else {
              def msg =
                s"no matching symbol for ${tp.symbol.showLocated} in ${defctx.owner} / ${defctx.effectiveScope.toList}"
              ErrorType(msg).assertingErrorsReported(msg)
            }
          case _ =>
            mapOver(tp)
        }
      }
      tpd.TypeTree(relocate(sym.info))
    }
  }

  /** A type definition copied from `tdef` with a rhs typetree derived from it */
  def derivedTypeParam(tdef: TypeDef)(implicit ctx: Context): TypeDef =
    cpy.TypeDef(tdef)(
      rhs = DerivedFromParamTree().withSpan(tdef.rhs.span).watching(tdef)
    )

  /** A derived type definition watching `sym` */
  def derivedTypeParam(sym: TypeSymbol)(implicit ctx: Context): TypeDef =
    TypeDef(sym.name, DerivedFromParamTree().watching(sym)).withFlags(TypeParam)

  /** A value definition copied from `vdef` with a tpt typetree derived from it */
  def derivedTermParam(vdef: ValDef)(implicit ctx: Context): ValDef =
    cpy.ValDef(vdef)(
      tpt = DerivedFromParamTree().withSpan(vdef.tpt.span).watching(vdef))

// ----- Desugar methods -------------------------------------------------

  /**   var x: Int = expr
   *  ==>
   *    def x: Int = expr
   *    def x_=($1: <TypeTree()>): Unit = ()
   *
   *  Generate the setter only for
   *    - non-private class members
   *    - all trait members
   *    - all package object members
   */
  def valDef(vdef0: ValDef)(implicit ctx: Context): Tree = {
    val vdef @ ValDef(name, tpt, rhs) = vdef0
    val mods = vdef.mods
    val setterNeeded =
      mods.is(Mutable)
      && ctx.owner.isClass
      && (!mods.is(Private) || ctx.owner.is(Trait) || ctx.owner.isPackageObject)
    if (setterNeeded) {
      // TODO: copy of vdef as getter needed?
      // val getter = ValDef(mods, name, tpt, rhs) withPos vdef.pos?
      // right now vdef maps via expandedTree to a thicket which concerns itself.
      // I don't see a problem with that but if there is one we can avoid it by making a copy here.
      val setterParam = makeSyntheticParameter(tpt = SetterParamTree().watching(vdef))
      // The rhs gets filled in later, when field is generated and getter has parameters (see Memoize miniphase)
      val setterRhs = if (vdef.rhs.isEmpty) EmptyTree else unitLiteral
      val setter = cpy.DefDef(vdef)(
        name     = name.setterName,
        tparams  = Nil,
        vparamss = (setterParam :: Nil) :: Nil,
        tpt      = TypeTree(defn.UnitType),
        rhs      = setterRhs
      ).withMods((mods | Accessor) &~ (CaseAccessor | GivenOrImplicit | Lazy))
      Thicket(vdef, setter)
    }
    else vdef
  }

  def makeImplicitParameters(tpts: List[Tree], implicitFlag: FlagSet, forPrimaryConstructor: Boolean = false)(implicit ctx: Context): List[ValDef] =
    for (tpt <- tpts) yield {
       val paramFlags: FlagSet = if (forPrimaryConstructor) LocalParamAccessor else Param
       val epname = EvidenceParamName.fresh()
       ValDef(epname, tpt, EmptyTree).withFlags(paramFlags | implicitFlag)
    }

  /** 1. Expand context bounds to evidence params. E.g.,
   *
   *      def f[T >: L <: H : B](params)
   *  ==>
   *      def f[T >: L <: H](params)(implicit evidence$0: B[T])
   *
   *  2. Expand default arguments to default getters. E.g,
   *
   *      def f[T: B](x: Int = 1)(y: String = x + "m") = ...
   *  ==>
   *      def f[T](x: Int)(y: String)(implicit evidence$0: B[T]) = ...
   *      def f$default$1[T] = 1
   *      def f$default$2[T](x: Int) = x + "m"
   */
  private def defDef(meth0: DefDef, isPrimaryConstructor: Boolean = false)(implicit ctx: Context): Tree = {
    val meth @ DefDef(_, tparams, vparamss, tpt, rhs) = meth0
    val methName = normalizeName(meth, tpt).asTermName
    val mods = meth.mods
    val epbuf = ListBuffer[ValDef]()
    def desugarContextBounds(rhs: Tree): Tree = rhs match {
      case ContextBounds(tbounds, cxbounds) =>
        val iflag = if sourceVersion.isAtLeast(`3.1`) then Given else Implicit
        epbuf ++= makeImplicitParameters(cxbounds, iflag, forPrimaryConstructor = isPrimaryConstructor)
        tbounds
      case LambdaTypeTree(tparams, body) =>
        cpy.LambdaTypeTree(rhs)(tparams, desugarContextBounds(body))
      case _ =>
        rhs
    }

    def dropContextBounds(tparam: TypeDef): TypeDef = {
      def dropInRhs(rhs: Tree): Tree = rhs match {
        case ContextBounds(tbounds, _) =>
          tbounds
        case rhs @ LambdaTypeTree(tparams, body) =>
          cpy.LambdaTypeTree(rhs)(tparams, dropInRhs(body))
        case _ =>
          rhs
      }
      cpy.TypeDef(tparam)(rhs = dropInRhs(tparam.rhs))
    }

    val tparams1 = tparams mapConserve { tparam =>
      cpy.TypeDef(tparam)(rhs = desugarContextBounds(tparam.rhs))
    }

    val meth1 = addEvidenceParams(
      cpy.DefDef(meth)(name = methName, tparams = tparams1), epbuf.toList)

    /** The longest prefix of parameter lists in vparamss whose total length does not exceed `n` */
    def takeUpTo(vparamss: List[List[ValDef]], n: Int): List[List[ValDef]] = vparamss match {
      case vparams :: vparamss1 =>
        val len = vparams.length
        if (n >= len) vparams :: takeUpTo(vparamss1, n - len) else Nil
      case _ =>
        Nil
    }

    def normalizedVparamss = meth1.vparamss.nestedMapConserve(vparam =>
      if vparam.rhs.isEmpty then vparam
      else cpy.ValDef(vparam)(rhs = EmptyTree).withMods(vparam.mods | HasDefault)
    )

    def defaultGetters(vparamss: List[List[ValDef]], n: Int): List[DefDef] = vparamss match {
      case (vparam :: vparams) :: vparamss1 =>
        def defaultGetter: DefDef =
          DefDef(
            name = DefaultGetterName(methName, n),
            tparams = meth.tparams.map(tparam => dropContextBounds(toDefParam(tparam, keepAnnotations = true))),
            vparamss = takeUpTo(normalizedVparamss.nestedMap(toDefParam(_, keepAnnotations = true, keepDefault = false)), n),
            tpt = TypeTree(),
            rhs = vparam.rhs
          )
          .withMods(Modifiers(mods.flags & (AccessFlags | Synthetic), mods.privateWithin))
        val rest = defaultGetters(vparams :: vparamss1, n + 1)
        if (vparam.rhs.isEmpty) rest else defaultGetter :: rest
      case Nil :: vparamss1 =>
        defaultGetters(vparamss1, n)
      case nil =>
        Nil
    }

    val defGetters = defaultGetters(meth1.vparamss, 0)
    if (defGetters.isEmpty) meth1
    else {
      val meth2 = cpy.DefDef(meth1)(vparamss = normalizedVparamss)
      Thicket(meth2 :: defGetters)
    }
  }

  /** Add an explicit ascription to the `expectedTpt` to every tail splice.
   *
   *  - `'{ x }` -> `'{ x }`
   *  - `'{ $x }` -> `'{ $x: T }`
   *  - `'{ if (...) $x else $y }` -> `'{ if (...) ($x: T) else ($y: T) }`
   *
   *  Note that the splice `$t: T` will be typed as `${t: Expr[T]}`
   */
  def quotedPattern(tree: untpd.Tree, expectedTpt: untpd.Tree)(implicit ctx: Context): untpd.Tree = {
    def adaptToExpectedTpt(tree: untpd.Tree): untpd.Tree = tree match {
      // Add the expected type as an ascription
      case _: untpd.Splice =>
        untpd.Typed(tree, expectedTpt).withSpan(tree.span)
      case Typed(expr: untpd.Splice, tpt) =>
        cpy.Typed(tree)(expr, untpd.makeAndType(tpt, expectedTpt).withSpan(tpt.span))

      // Propagate down the expected type to the leafs of the expression
      case Block(stats, expr) =>
        cpy.Block(tree)(stats, adaptToExpectedTpt(expr))
      case If(cond, thenp, elsep) =>
        cpy.If(tree)(cond, adaptToExpectedTpt(thenp), adaptToExpectedTpt(elsep))
      case untpd.Parens(expr) =>
        cpy.Parens(tree)(adaptToExpectedTpt(expr))
      case Match(selector, cases) =>
        val newCases = cases.map(cdef => cpy.CaseDef(cdef)(body = adaptToExpectedTpt(cdef.body)))
        cpy.Match(tree)(selector, newCases)
      case untpd.ParsedTry(expr, handler, finalizer) =>
        cpy.ParsedTry(tree)(adaptToExpectedTpt(expr), adaptToExpectedTpt(handler), finalizer)

      // Tree does not need to be ascribed
      case _ =>
        tree
    }
    adaptToExpectedTpt(tree)
  }

  // Add all evidence parameters in `params` as implicit parameters to `meth` */
  private def addEvidenceParams(meth: DefDef, params: List[ValDef])(implicit ctx: Context): DefDef =
    params match {
      case Nil =>
        meth
      case evidenceParams =>
        val vparamss1 = meth.vparamss.reverse match {
          case (vparams @ (vparam :: _)) :: rvparamss if vparam.mods.isOneOf(GivenOrImplicit) =>
            ((evidenceParams ++ vparams) :: rvparamss).reverse
          case _ =>
            meth.vparamss :+ evidenceParams
        }
        cpy.DefDef(meth)(vparamss = vparamss1)
    }

  /** The implicit evidence parameters of `meth`, as generated by `desugar.defDef` */
  private def evidenceParams(meth: DefDef)(implicit ctx: Context): List[ValDef] =
    meth.vparamss.reverse match {
      case (vparams @ (vparam :: _)) :: _ if vparam.mods.isOneOf(GivenOrImplicit) =>
        vparams.dropWhile(!_.name.is(EvidenceParamName))
      case _ =>
        Nil
    }

  @sharable private val synthetic = Modifiers(Synthetic)

  private def toDefParam(tparam: TypeDef, keepAnnotations: Boolean): TypeDef = {
    var mods = tparam.rawMods
    if (!keepAnnotations) mods = mods.withAnnotations(Nil)
    tparam.withMods(mods & EmptyFlags | Param)
  }
  private def toDefParam(vparam: ValDef, keepAnnotations: Boolean, keepDefault: Boolean): ValDef = {
    var mods = vparam.rawMods
    if (!keepAnnotations) mods = mods.withAnnotations(Nil)
    val hasDefault = if keepDefault then HasDefault else EmptyFlags
    vparam.withMods(mods & (GivenOrImplicit | Erased | hasDefault) | Param)
  }

  /** The expansion of a class definition. See inline comments for what is involved */
  def classDef(cdef: TypeDef)(implicit ctx: Context): Tree = {
    val impl @ Template(constr0, _, self, _) = cdef.rhs
    val className = normalizeName(cdef, impl).asTypeName
    val parents = impl.parents
    val mods = cdef.mods
    val companionMods = mods
        .withFlags((mods.flags & (AccessFlags | Final)).toCommonFlags)
        .withMods(Nil)

    var defaultGetters: List[Tree] = Nil

    def decompose(ddef: Tree): DefDef = ddef match {
      case meth: DefDef => meth
      case Thicket((meth: DefDef) :: defaults) =>
        defaultGetters = defaults
        meth
    }

    val constr1 = decompose(defDef(impl.constr, isPrimaryConstructor = true))

    // The original type and value parameters in the constructor already have the flags
    // needed to be type members (i.e. param, and possibly also private and local unless
    // prefixed by type or val). `tparams` and `vparamss` are the type parameters that
    // go in `constr`, the constructor after desugaring.

    /** Does `tree' look like a reference to AnyVal? Temporary test before we have inline classes */
    def isAnyVal(tree: Tree): Boolean = tree match {
      case Ident(tpnme.AnyVal) => true
      case Select(qual, tpnme.AnyVal) => isScala(qual)
      case _ => false
    }
    def isScala(tree: Tree): Boolean = tree match {
      case Ident(nme.scala) => true
      case Select(Ident(nme.ROOTPKG), nme.scala) => true
      case _ => false
    }

    def namePos = cdef.sourcePos.withSpan(cdef.nameSpan)

    val isObject = mods.is(Module)
    val isCaseClass  = mods.is(Case) && !isObject
    val isCaseObject = mods.is(Case) && isObject
    val isEnum = mods.isEnumClass && !mods.is(Module)
    def isEnumCase = mods.isEnumCase
    val isValueClass = parents.nonEmpty && isAnyVal(parents.head)
      // This is not watertight, but `extends AnyVal` will be replaced by `inline` later.

    val originalTparams = constr1.tparams
    val originalVparamss = constr1.vparamss
    lazy val derivedEnumParams = enumClass.typeParams.map(derivedTypeParam)
    val impliedTparams =
      if (isEnumCase) {
        val tparamReferenced = typeParamIsReferenced(
            enumClass.typeParams, originalTparams, originalVparamss, parents)
        if (originalTparams.isEmpty && (parents.isEmpty || tparamReferenced))
          derivedEnumParams.map(tdef => tdef.withFlags(tdef.mods.flags | PrivateLocal))
        else originalTparams
      }
      else originalTparams

    // Annotations on class _type_ parameters are set on the derived parameters
    // but not on the constructor parameters. The reverse is true for
    // annotations on class _value_ parameters.
    val constrTparams = impliedTparams.map(toDefParam(_, keepAnnotations = false))
    val constrVparamss =
      if (originalVparamss.isEmpty) { // ensure parameter list is non-empty
        if (isCaseClass)
          ctx.error(CaseClassMissingParamList(cdef), namePos)
        ListOfNil
      }
      else if (isCaseClass && originalVparamss.head.exists(_.mods.isOneOf(GivenOrImplicit))) {
        ctx.error(CaseClassMissingNonImplicitParamList(cdef), namePos)
        ListOfNil
      }
      else originalVparamss.nestedMap(toDefParam(_, keepAnnotations = true, keepDefault = true))
    val derivedTparams =
      constrTparams.zipWithConserve(impliedTparams)((tparam, impliedParam) =>
        derivedTypeParam(tparam).withAnnotations(impliedParam.mods.annotations))
    val derivedVparamss =
      constrVparamss.nestedMap(vparam =>
        derivedTermParam(vparam).withAnnotations(Nil))

    val constr = cpy.DefDef(constr1)(tparams = constrTparams, vparamss = constrVparamss)

    val (normalizedBody, enumCases, enumCompanionRef) = {
      // Add constructor type parameters and evidence implicit parameters
      // to auxiliary constructors; set defaultGetters as a side effect.
      def expandConstructor(tree: Tree) = tree match {
        case ddef: DefDef if ddef.name.isConstructorName =>
          decompose(
            defDef(
              addEvidenceParams(
                cpy.DefDef(ddef)(tparams = constrTparams ++ ddef.tparams),
                evidenceParams(constr1).map(toDefParam(_, keepAnnotations = false, keepDefault = false)))))
        case stat =>
          stat
      }
      // The Identifiers defined by a case
      def caseIds(tree: Tree): List[Ident] = tree match {
        case tree: MemberDef => Ident(tree.name.toTermName) :: Nil
        case PatDef(_, ids: List[Ident] @ unchecked, _, _) => ids
      }
      val stats = impl.body.map(expandConstructor)
      if (isEnum) {
        val (enumCases, enumStats) = stats.partition(DesugarEnums.isEnumCase)
        if (enumCases.isEmpty)
          ctx.error(EnumerationsShouldNotBeEmpty(cdef), namePos)
        val enumCompanionRef = TermRefTree()
        val enumImport =
          Import(enumCompanionRef, enumCases.flatMap(caseIds).map(ImportSelector(_)))
        (enumImport :: enumStats, enumCases, enumCompanionRef)
      }
      else (stats, Nil, EmptyTree)
    }

    def anyRef = ref(defn.AnyRefAlias.typeRef)

    val arity = constrVparamss.head.length

    val classTycon: Tree = TypeRefTree() // watching is set at end of method

    def appliedTypeTree(tycon: Tree, args: List[Tree]) =
      (if (args.isEmpty) tycon else AppliedTypeTree(tycon, args))
        .withSpan(cdef.span.startPos)

    def isHK(tparam: Tree): Boolean = tparam match {
      case TypeDef(_, LambdaTypeTree(tparams, body)) => true
      case TypeDef(_, rhs: DerivedTypeTree) => isHK(rhs.watched)
      case _ => false
    }

    def appliedRef(tycon: Tree, tparams: List[TypeDef] = constrTparams, widenHK: Boolean = false) = {
      val targs = for (tparam <- tparams) yield {
        val targ = refOfDef(tparam)
        def fullyApplied(tparam: Tree): Tree = tparam match {
          case TypeDef(_, LambdaTypeTree(tparams, body)) =>
            AppliedTypeTree(targ, tparams.map(_ => TypeBoundsTree(EmptyTree, EmptyTree)))
          case TypeDef(_, rhs: DerivedTypeTree) =>
            fullyApplied(rhs.watched)
          case _ =>
            targ
        }
        if (widenHK) fullyApplied(tparam) else targ
      }
      appliedTypeTree(tycon, targs)
    }

    def isRepeated(tree: Tree): Boolean = tree match {
      case PostfixOp(_, Ident(tpnme.raw.STAR)) => true
      case ByNameTypeTree(tree1) => isRepeated(tree1)
      case _ => false
    }

    // a reference to the class type bound by `cdef`, with type parameters coming from the constructor
    val classTypeRef = appliedRef(classTycon)

    // a reference to `enumClass`, with type parameters coming from the case constructor
    lazy val enumClassTypeRef =
      if (enumClass.typeParams.isEmpty)
        enumClassRef
      else if (originalTparams.isEmpty)
        appliedRef(enumClassRef)
      else {
        ctx.error(TypedCaseDoesNotExplicitlyExtendTypedEnum(enumClass, cdef)
            , cdef.sourcePos.startPos)
        appliedTypeTree(enumClassRef, constrTparams map (_ => anyRef))
      }

    // new C[Ts](paramss)
    lazy val creatorExpr = {
      val vparamss = constrVparamss match {
        case (vparam :: _) :: _ if vparam.mods.isOneOf(GivenOrImplicit) => // add a leading () to match class parameters
          Nil :: constrVparamss
        case _ =>
          constrVparamss
      }
      val nu = vparamss.foldLeft(makeNew(classTypeRef)) { (nu, vparams) =>
        val app = Apply(nu, vparams.map(refOfDef))
        vparams match {
          case vparam :: _ if vparam.mods.is(Given) => app.setUsingApply()
          case _ => app
        }
      }
      ensureApplied(nu)
    }

    val copiedAccessFlags = if migrateTo3 then EmptyFlags else AccessFlags

    // Methods to add to a case class C[..](p1: T1, ..., pN: Tn)(moreParams)
    //     def _1: T1 = this.p1
    //     ...
    //     def _N: TN = this.pN
    //     def copy(p1: T1 = p1: @uncheckedVariance, ...,
    //              pN: TN = pN: @uncheckedVariance)(moreParams) =
    //       new C[...](p1, ..., pN)(moreParams)
    //
    // Note: copy default parameters need @uncheckedVariance; see
    // neg/t1843-variances.scala for a test case. The test would give
    // two errors without @uncheckedVariance, one of them spurious.
    val caseClassMeths = {
      def syntheticProperty(name: TermName, tpt: Tree, rhs: Tree) =
        DefDef(name, Nil, Nil, tpt, rhs).withMods(synthetic)
      def productElemMeths = {
        val caseParams = derivedVparamss.head.toArray
        for (i <- List.range(0, arity) if nme.selectorName(i) `ne` caseParams(i).name)
        yield syntheticProperty(nme.selectorName(i), caseParams(i).tpt,
          Select(This(EmptyTypeIdent), caseParams(i).name))
      }
      def ordinalMeths = if (isEnumCase) ordinalMethLit(nextOrdinal(CaseKind.Class)._1) :: Nil else Nil
      def copyMeths = {
        val hasRepeatedParam = constrVparamss.exists(_.exists {
          case ValDef(_, tpt, _) => isRepeated(tpt)
        })
        if (mods.is(Abstract) || hasRepeatedParam) Nil  // cannot have default arguments for repeated parameters, hence copy method is not issued
        else {
          def copyDefault(vparam: ValDef) =
            makeAnnotated("scala.annotation.unchecked.uncheckedVariance", refOfDef(vparam))
          val copyFirstParams = derivedVparamss.head.map(vparam =>
            cpy.ValDef(vparam)(rhs = copyDefault(vparam)))
          val copyRestParamss = derivedVparamss.tail.nestedMap(vparam =>
            cpy.ValDef(vparam)(rhs = EmptyTree))
          DefDef(nme.copy, derivedTparams, copyFirstParams :: copyRestParamss, TypeTree(), creatorExpr)
            .withMods(Modifiers(Synthetic | constr1.mods.flags & copiedAccessFlags, constr1.mods.privateWithin)) :: Nil
        }
      }

      if (isCaseClass)
        copyMeths ::: ordinalMeths ::: productElemMeths
      else Nil
    }

    var parents1 = parents
    if (isEnumCase && parents.isEmpty)
      parents1 = enumClassTypeRef :: Nil
    if (isCaseClass | isCaseObject)
      parents1 = parents1 :+ scalaDot(str.Product.toTypeName) :+ scalaDot(nme.Serializable.toTypeName)
    else if (isObject)
      parents1 = parents1 :+ scalaDot(nme.Serializable.toTypeName)
    if (isEnum)
      parents1 = parents1 :+ ref(defn.EnumClass.typeRef)

    // derived type classes of non-module classes go to their companions
    val (clsDerived, companionDerived) =
      if (mods.is(Module)) (impl.derived, Nil) else (Nil, impl.derived)

    // The thicket which is the desugared version of the companion object
    //     synthetic object C extends parentTpt derives class-derived { defs }
    def companionDefs(parentTpt: Tree, defs: List[Tree]) = {
      val mdefs = moduleDef(
        ModuleDef(
          className.toTermName, Template(emptyConstructor, parentTpt :: Nil, companionDerived, EmptyValDef, defs))
            .withMods(companionMods | Synthetic))
        .withSpan(cdef.span).toList
      if (companionDerived.nonEmpty)
        for (modClsDef @ TypeDef(_, _) <- mdefs)
          modClsDef.putAttachment(DerivingCompanion, impl.sourcePos.startPos)
      mdefs
    }

    val companionMembers = defaultGetters ::: enumCases

    // The companion object definitions, if a companion is needed, Nil otherwise.
    // companion definitions include:
    // 1. If class is a case class case class C[Ts](p1: T1, ..., pN: TN)(moreParams):
    //     def apply[Ts](p1: T1, ..., pN: TN)(moreParams) = new C[Ts](p1, ..., pN)(moreParams)  (unless C is abstract)
    //     def unapply[Ts]($1: C[Ts]) = $1        // if not repeated
    //     def unapplySeq[Ts]($1: C[Ts]) = $1     // if repeated
    // 2. The default getters of the constructor
    // The parent of the companion object of a non-parameterized case class
    //     (T11, ..., T1N) => ... => (TM1, ..., TMN) => C
    // For all other classes, the parent is AnyRef.
    val companions =
      if (isCaseClass) {
        // The return type of the `apply` method, and an (empty or singleton) list
        // of widening coercions
        val (applyResultTpt, widenDefs) =
          if (!isEnumCase)
            (TypeTree(), Nil)
          else if (parents.isEmpty || enumClass.typeParams.isEmpty)
            (enumClassTypeRef, Nil)
          else
            enumApplyResult(cdef, parents, derivedEnumParams, appliedRef(enumClassRef, derivedEnumParams))

        // true if access to the apply method has to be restricted
        // i.e. if the case class constructor is either private or qualified private
        def restrictedAccess = {
          val mods = constr1.mods
          mods.is(Private) || (!mods.is(Protected) && mods.hasPrivateWithin)
        }

        /** Does one of the parameter's types (in the first param clause)
         *  mention a preceding parameter?
         */
        def isParamDependent = constrVparamss match
          case vparams :: _ =>
            val paramNames = vparams.map(_.name).toSet
            vparams.exists(_.tpt.existsSubTree {
              case Ident(name: TermName) => paramNames.contains(name)
              case _ => false
            })
          case _ => false

        val companionParent =
          if constrTparams.nonEmpty
             || constrVparamss.length > 1
             || mods.is(Abstract)
             || restrictedAccess
             || isParamDependent
             || isEnumCase
          then anyRef
          else
            constrVparamss.foldRight(classTypeRef)((vparams, restpe) => Function(vparams map (_.tpt), restpe))
        def widenedCreatorExpr =
          widenDefs.foldLeft(creatorExpr)((rhs, meth) => Apply(Ident(meth.name), rhs :: Nil))
        val applyMeths =
          if (mods.is(Abstract)) Nil
          else {
            val copiedFlagsMask = copiedAccessFlags & Private
            val appMods = {
              val mods = Modifiers(Synthetic | constr1.mods.flags & copiedFlagsMask)
              if (restrictedAccess) mods.withPrivateWithin(constr1.mods.privateWithin)
              else mods
            }
            val appParamss =
              derivedVparamss.nestedZipWithConserve(constrVparamss)((ap, cp) =>
                ap.withMods(ap.mods | (cp.mods.flags & HasDefault)))
            val app = DefDef(nme.apply, derivedTparams, appParamss, applyResultTpt, widenedCreatorExpr)
              .withMods(appMods)
            app :: widenDefs
          }
        val unapplyMeth = {
          val hasRepeatedParam = constrVparamss.head.exists {
            case ValDef(_, tpt, _) => isRepeated(tpt)
          }
          val methName = if (hasRepeatedParam) nme.unapplySeq else nme.unapply
          val unapplyParam = makeSyntheticParameter(tpt = classTypeRef)
          val unapplyRHS = if (arity == 0) Literal(Constant(true)) else Ident(unapplyParam.name)
          val unapplyResTp = if (arity == 0) Literal(Constant(true)) else TypeTree()
          DefDef(methName, derivedTparams, (unapplyParam :: Nil) :: Nil, unapplyResTp, unapplyRHS)
            .withMods(synthetic)
        }
        companionDefs(companionParent, applyMeths ::: unapplyMeth :: companionMembers)
      }
      else if (companionMembers.nonEmpty || companionDerived.nonEmpty || isEnum)
        companionDefs(anyRef, companionMembers)
      else if (isValueClass)
        companionDefs(anyRef, Nil)
      else Nil

    enumCompanionRef match {
      case ref: TermRefTree => // have the enum import watch the companion object
        val (modVal: ValDef) :: _ = companions
        ref.watching(modVal)
      case _ =>
    }

    // For an implicit class C[Ts](p11: T11, ..., p1N: T1N) ... (pM1: TM1, .., pMN: TMN), the method
    //     synthetic implicit C[Ts](p11: T11, ..., p1N: T1N) ... (pM1: TM1, ..., pMN: TMN): C[Ts] =
    //       new C[Ts](p11, ..., p1N) ... (pM1, ..., pMN) =
    val implicitWrappers =
      if (!mods.isOneOf(GivenOrImplicit))
        Nil
      else if (ctx.owner.is(Package)) {
        ctx.error(TopLevelImplicitClass(cdef), cdef.sourcePos)
        Nil
      }
      else if (mods.is(Trait)) {
        ctx.error(TypesAndTraitsCantBeImplicit(), cdef.sourcePos)
        Nil
      }
      else if (isCaseClass) {
        ctx.error(ImplicitCaseClass(cdef), cdef.sourcePos)
        Nil
      }
      else if (arity != 1 && !mods.is(Given)) {
        ctx.error(ImplicitClassPrimaryConstructorArity(), cdef.sourcePos)
        Nil
      }
      else {
        val defParamss = constrVparamss match {
          case Nil :: paramss =>
            paramss // drop leading () that got inserted by class
                    // TODO: drop this once we do not silently insert empty class parameters anymore
          case paramss => paramss
        }
        // implicit wrapper is typechecked in same scope as constructor, so
        // we can reuse the constructor parameters; no derived params are needed.
        DefDef(className.toTermName, constrTparams, defParamss, classTypeRef, creatorExpr)
          .withMods(companionMods | mods.flags.toTermFlags & GivenOrImplicit | Synthetic | Final)
          .withSpan(cdef.span) :: Nil
      }

    val self1 = {
      val selfType = if (self.tpt.isEmpty) classTypeRef else self.tpt
      if (self.isEmpty) self
      else cpy.ValDef(self)(tpt = selfType).withMods(self.mods | SelfName)
    }

    val cdef1 = addEnumFlags {
      val tparamAccessors = {
        val impliedTparamsIt = impliedTparams.iterator
        derivedTparams.map(_.withMods(impliedTparamsIt.next().mods))
      }
      val caseAccessor = if (isCaseClass) CaseAccessor else EmptyFlags
      val vparamAccessors = {
        val originalVparamsIt = originalVparamss.iterator.flatten
        derivedVparamss match {
          case first :: rest =>
            // Annotations on the class _value_ parameters are not set on the parameter accessors
            def mods(vdef: ValDef) = vdef.mods.withAnnotations(Nil)
            first.map(_.withMods(mods(originalVparamsIt.next()) | caseAccessor)) ++
            rest.flatten.map(_.withMods(mods(originalVparamsIt.next())))
          case _ =>
            Nil
        }
      }
      cpy.TypeDef(cdef: TypeDef)(
        name = className,
        rhs = cpy.Template(impl)(constr, parents1, clsDerived, self1,
          tparamAccessors ::: vparamAccessors ::: normalizedBody ::: caseClassMeths)): TypeDef
    }

    // install the watch on classTycon
    classTycon match {
      case tycon: DerivedTypeTree => tycon.watching(cdef1)
      case _ =>
    }

    flatTree(cdef1 :: companions ::: implicitWrappers)
  }.reporting(i"desugared: $result", Printers.desugar)

  /** Expand
   *
   *    package object name { body }
   *
   *  to:
   *
   *    package name {
   *      object `package` { body }
   *    }
   */
  def packageModuleDef(mdef: ModuleDef)(implicit ctx: Context): Tree =
    val impl = mdef.impl
    val mods = mdef.mods
    val moduleName = normalizeName(mdef, impl).asTermName
    if (mods.is(Package))
      PackageDef(Ident(moduleName),
        cpy.ModuleDef(mdef)(nme.PACKAGE, impl).withMods(mods &~ Package) :: Nil)
    else
      mdef

  /** Expand
   *
   *    object name extends parents { self => body }
   *
   *  to:
   *
   *    <module> val name: name$ = New(name$)
   *    <module> final class name$ extends parents { self: name.type => body }
   *
   *  Special case for extension methods with collective parameters. Expand:
   *
   *     given object name[tparams](x: T) extends parents { self => bpdy }
   *
   *  to:
   *
   *     given object name extends parents { self => body' }
   *
   *  where every definition in `body` is expanded to an extension method
   *  taking type parameters `tparams` and a leading paramter `(x: T)`.
   *  See: collectiveExtensionBody
   */
  def moduleDef(mdef: ModuleDef)(implicit ctx: Context): Tree = {
    val impl = mdef.impl
    val mods = mdef.mods
    impl.constr match {
      case DefDef(_, tparams, vparamss @ (vparam :: Nil) :: givenParamss, _, _) =>
        // Transform collective extension
        assert(mods.is(Given))
        return moduleDef(
          cpy.ModuleDef(mdef)(
            mdef.name,
            cpy.Template(impl)(
              constr = emptyConstructor,
              body = collectiveExtensionBody(impl.body, tparams, vparamss))))
      case _ =>
    }

    val moduleName = normalizeName(mdef, impl).asTermName
    def isEnumCase = mods.isEnumCase

    def flagSourcePos(flag: FlagSet) = mods.mods.find(_.flags == flag).fold(mdef.sourcePos)(_.sourcePos)

    if (mods.is(Abstract))
      ctx.error(AbstractCannotBeUsedForObjects(mdef), flagSourcePos(Abstract))
    if (mods.is(Sealed))
      ctx.error(ModifierRedundantForObjects(mdef, "sealed"), flagSourcePos(Sealed))
    // Maybe this should be an error; see https://github.com/scala/bug/issues/11094.
    if (mods.is(Final) && !mods.is(Synthetic))
      ctx.warning(ModifierRedundantForObjects(mdef, "final"), flagSourcePos(Final))

    if (mods.is(Package))
      packageModuleDef(mdef)
    else if (isEnumCase) {
      typeParamIsReferenced(enumClass.typeParams, Nil, Nil, impl.parents)
        // used to check there are no illegal references to enum's type parameters in parents
      expandEnumModule(moduleName, impl, mods, mdef.span)
    }
    else {
      val clsName = moduleName.moduleClassName
      val clsRef = Ident(clsName)
      val modul = ValDef(moduleName, clsRef, New(clsRef, Nil))
        .withMods(mods.toTermFlags & RetainedModuleValFlags | ModuleValCreationFlags)
        .withSpan(mdef.span.startPos)
      val ValDef(selfName, selfTpt, _) = impl.self
      val selfMods = impl.self.mods
      if (!selfTpt.isEmpty) ctx.error(ObjectMayNotHaveSelfType(mdef), impl.self.sourcePos)
      val clsSelf = ValDef(selfName, SingletonTypeTree(Ident(moduleName)), impl.self.rhs)
        .withMods(selfMods)
        .withSpan(impl.self.span.orElse(impl.span.startPos))
      val clsTmpl = cpy.Template(impl)(self = clsSelf, body = impl.body)
      val cls = TypeDef(clsName, clsTmpl)
        .withMods(mods.toTypeFlags & RetainedModuleClassFlags | ModuleClassCreationFlags)
      Thicket(modul, classDef(cls).withSpan(mdef.span))
    }
  }

  /** Transform the statements of a collective extension
   *   @param stats    the original statements as they were parsed
   *   @param tparams  the collective type parameters
   *   @param vparamss the collective value parameters, consisting
   *                   of a single leading value parameter, followed by
   *                   zero or more context parameter clauses
   *
   *  Note: It is already assured by Parser.checkExtensionMethod that all
   *  statements conform to requirements.
   *
   *  Each method in stats is transformed into an extension method. Example:
   *
   *    extension on [Ts](x: T)(using C):
   *      def f(y: T) = ???
   *      def g(z: T) = f(z)
   *
   *  is turned into
   *
   *    extension:
   *      <extension> def f[Ts](x: T)(using C)(y: T) = ???
   *      <extension> def g[Ts](x: T)(using C)(z: T) = f(z)
   */
  def collectiveExtensionBody(stats: List[Tree],
      tparams: List[TypeDef], vparamss: List[List[ValDef]])(using Context): List[Tree] =
    for stat <- stats yield
      stat match
        case mdef: DefDef =>
          cpy.DefDef(mdef)(
            tparams = tparams ++ mdef.tparams,
            vparamss = vparamss ::: mdef.vparamss,
          ).withMods(mdef.mods | Extension)
        case mdef =>
          mdef
  end collectiveExtensionBody

  /** Transforms
   *
   *    <mods> type $T >: Low <: Hi
   *
   *  to
   *
   *    @patternType <mods> type $T >: Low <: Hi
   *
   *  if the type is a type splice.
   */
  def quotedPatternTypeDef(tree: TypeDef)(implicit ctx: Context): TypeDef = {
    assert(ctx.mode.is(Mode.QuotedPattern))
    if (tree.name.startsWith("$") && !tree.isBackquoted) {
      val patternBindHoleAnnot = New(ref(defn.InternalQuoted_patternTypeAnnot.typeRef)).withSpan(tree.span)
      val mods = tree.mods.withAddedAnnotation(patternBindHoleAnnot)
      tree.withMods(mods)
    }
    else tree
  }

  /** The normalized name of `mdef`. This means
   *   1. Check that the name does not redefine a Scala core class.
   *      If it does redefine, issue an error and return a mangled name instead of the original one.
   *   2. If the name is missing (this can be the case for instance definitions), invent one instead.
   */
  def normalizeName(mdef: MemberDef, impl: Tree)(implicit ctx: Context): Name = {
    var name = mdef.name
    if (name.isEmpty) name = name.likeSpaced(inventGivenOrExtensionName(impl))
    if (ctx.owner == defn.ScalaPackageClass && defn.reservedScalaClassNames.contains(name.toTypeName)) {
      val kind = if (name.isTypeName) "class" else "object"
      ctx.error(IllegalRedefinitionOfStandardKind(kind, name), mdef.sourcePos)
      name = name.errorName
    }
    name
  }

  /** Invent a name for an anonympus given or extension of type or template `impl`. */
  def inventGivenOrExtensionName(impl: Tree)(using Context): SimpleName =
    val str = impl match
      case impl: Template =>
        if impl.parents.isEmpty then
          impl.body.find {
            case dd: DefDef if dd.mods.is(Extension) => true
            case _ => false
          }
          match
            case Some(DefDef(name, _, (vparam :: _) :: _, _, _)) =>
              s"extension_${name}_${inventTypeName(vparam.tpt)}"
            case _ =>
              ctx.error(AnonymousInstanceCannotBeEmpty(impl), impl.sourcePos)
              nme.ERROR.toString
        else
          impl.parents.map(inventTypeName(_)).mkString("given_", "_", "")
      case impl: Tree =>
        "given_" ++ inventTypeName(impl)
    str.toTermName.asSimpleName

  private class NameExtractor(followArgs: Boolean) extends UntypedTreeAccumulator[String] {
    private def extractArgs(args: List[Tree])(implicit ctx: Context): String =
      args.map(argNameExtractor.apply("", _)).mkString("_")
    override def apply(x: String, tree: Tree)(implicit ctx: Context): String =
      if (x.isEmpty)
        tree match {
          case Select(pre, nme.CONSTRUCTOR) => foldOver(x, pre)
          case tree: RefTree if tree.name.isTypeName => tree.name.toString
          case tree: TypeDef => tree.name.toString
          case tree: AppliedTypeTree if followArgs && tree.args.nonEmpty =>
            s"${apply(x, tree.tpt)}_${extractArgs(tree.args)}"
          case tree: LambdaTypeTree =>
            apply(x, tree.body)
          case tree: Tuple =>
            extractArgs(tree.trees)
          case tree: Function if tree.args.nonEmpty =>
            if (followArgs) s"${extractArgs(tree.args)}_to_${apply("", tree.body)}" else "Function"
          case _ => foldOver(x, tree)
        }
      else x
  }
  private val typeNameExtractor = NameExtractor(followArgs = true)
  private val argNameExtractor = NameExtractor(followArgs = false)

  private def inventTypeName(tree: Tree)(implicit ctx: Context): String = typeNameExtractor("", tree)

  /**     val p1, ..., pN: T = E
   *  ==>
   *      makePatDef[[val p1: T1 = E]]; ...; makePatDef[[val pN: TN = E]]
   *
   *      case e1, ..., eN
   *  ==>
   *      expandSimpleEnumCase([case e1]); ...; expandSimpleEnumCase([case eN])
   */
  def patDef(pdef: PatDef)(implicit ctx: Context): Tree = flatTree {
    val PatDef(mods, pats, tpt, rhs) = pdef
    if (mods.isEnumCase)
      pats map {
        case id: Ident =>
          expandSimpleEnumCase(id.name.asTermName, mods,
            Span(id.span.start, id.span.end, id.span.start))
      }
    else {
      val pats1 = if (tpt.isEmpty) pats else pats map (Typed(_, tpt))
      pats1 map (makePatDef(pdef, mods, _, rhs))
    }
  }

  /** The selector of a match, which depends of the given `checkMode`.
   *  @param  sel  the original selector
   *  @return if `checkMode` is
   *           - None              :  sel @unchecked
   *           - Exhaustive        :  sel
   *           - IrrefutablePatDef,
   *             IrrefutableGenFrom:  sel @unchecked with attachment `CheckIrrefutable -> checkMode`
   */
  def makeSelector(sel: Tree, checkMode: MatchCheck)(implicit ctx: Context): Tree =
    if (checkMode == MatchCheck.Exhaustive) sel
    else {
      val sel1 = Annotated(sel, New(ref(defn.UncheckedAnnot.typeRef)))
      if (checkMode != MatchCheck.None) sel1.pushAttachment(CheckIrrefutable, checkMode)
      sel1
    }

  /** If `pat` is a variable pattern,
   *
   *    val/var/lazy val p = e
   *
   *  Otherwise, in case there is exactly one variable x_1 in pattern
   *   val/var/lazy val p = e  ==>  val/var/lazy val x_1 = (e: @unchecked) match (case p => (x_1))
   *
   *   in case there are zero or more than one variables in pattern
   *   val/var/lazy p = e  ==>  private[this] synthetic [lazy] val t$ = (e: @unchecked) match (case p => (x_1, ..., x_N))
   *                   val/var/def x_1 = t$._1
   *                   ...
   *                   val/var/def x_N = t$._N
   *  If the original pattern variable carries a type annotation, so does the corresponding
   *  ValDef or DefDef.
   */
  def makePatDef(original: Tree, mods: Modifiers, pat: Tree, rhs: Tree)(implicit ctx: Context): Tree = pat match {
    case IdPattern(named, tpt) =>
      derivedValDef(original, named, tpt, rhs, mods)
    case _ =>
      def isTuplePattern(arity: Int): Boolean = pat match {
        case Tuple(pats) if pats.size == arity =>
          pats.forall(isVarPattern)
        case _ => false
      }
      val isMatchingTuple: Tree => Boolean = {
        case Tuple(es) => isTuplePattern(es.length)
        case _ => false
      }

      // We can only optimize `val pat = if (...) e1 else e2` if:
      // - `e1` and `e2` are both tuples of arity N
      // - `pat` is a tuple of N variables or wildcard patterns like `(x1, x2, ..., xN)`
      val tupleOptimizable = forallResults(rhs, isMatchingTuple)

      val vars =
        if (tupleOptimizable) // include `_`
          pat match {
            case Tuple(pats) =>
              pats.map { case id: Ident => id -> TypeTree() }
          }
        else getVariables(pat)  // no `_`

      val ids = for ((named, _) <- vars) yield Ident(named.name)
      val caseDef = CaseDef(pat, EmptyTree, makeTuple(ids))
      val matchExpr =
        if (tupleOptimizable) rhs
        else Match(makeSelector(rhs, MatchCheck.IrrefutablePatDef), caseDef :: Nil)
      vars match {
        case Nil if !mods.is(Lazy) =>
          matchExpr
        case (named, tpt) :: Nil =>
          derivedValDef(original, named, tpt, matchExpr, mods)
        case _ =>
          val tmpName = UniqueName.fresh()
          val patMods =
            mods & Lazy | Synthetic | (if (ctx.owner.isClass) PrivateLocal else EmptyFlags)
          val firstDef =
            ValDef(tmpName, TypeTree(), matchExpr)
              .withSpan(pat.span.union(rhs.span)).withMods(patMods)
          val useSelectors = vars.length <= 22
          def selector(n: Int) =
            if useSelectors then Select(Ident(tmpName), nme.selectorName(n))
            else Apply(Select(Ident(tmpName), nme.apply), Literal(Constant(n)) :: Nil)
          val restDefs =
            for (((named, tpt), n) <- vars.zipWithIndex if named.name != nme.WILDCARD)
            yield
              if (mods.is(Lazy)) derivedDefDef(original, named, tpt, selector(n), mods &~ Lazy)
              else derivedValDef(original, named, tpt, selector(n), mods)
          flatTree(firstDef :: restDefs)
      }
  }

  /** Expand variable identifier x to x @ _ */
  def patternVar(tree: Tree)(implicit ctx: Context): Bind = {
    val Ident(name) = unsplice(tree)
    Bind(name, Ident(nme.WILDCARD)).withSpan(tree.span)
  }

  /** The type of tests that check whether a MemberDef is OK for some flag.
   *  The test succeeds if the partial function is defined and returns true.
   */
  type MemberDefTest = PartialFunction[MemberDef, Boolean]

  val legalOpaque: MemberDefTest = {
    case TypeDef(_, rhs) =>
      def rhsOK(tree: Tree): Boolean = tree match {
        case bounds: TypeBoundsTree => !bounds.alias.isEmpty
        case _: Template => false
        case LambdaTypeTree(_, body) => rhsOK(body)
        case _ => true
      }
      rhsOK(rhs)
  }

  /** Check that modifiers are legal for the definition `tree`.
   *  Right now, we only check for `opaque`. TODO: Move other modifier checks here.
   */
  def checkModifiers(tree: Tree)(implicit ctx: Context): Tree = tree match {
    case tree: MemberDef =>
      var tested: MemberDef = tree
      def checkApplicable(flag: Flag, test: MemberDefTest): Unit =
        if (tested.mods.is(flag) && !test.applyOrElse(tree, (md: MemberDef) => false)) {
          ctx.error(ModifierNotAllowedForDefinition(flag), tree.sourcePos)
          tested = tested.withMods(tested.mods.withoutFlags(flag))
        }
      checkApplicable(Opaque, legalOpaque)
      tested
    case _ =>
      tree
  }

  def defTree(tree: Tree)(implicit ctx: Context): Tree =
    checkModifiers(tree) match {
      case tree: ValDef => valDef(tree)
      case tree: TypeDef =>
        if (tree.isClassDef) classDef(tree)
        else if (ctx.mode.is(Mode.QuotedPattern)) quotedPatternTypeDef(tree)
        else tree
      case tree: DefDef =>
        if (tree.name.isConstructorName) tree // was already handled by enclosing classDef
        else defDef(tree)
      case tree: ModuleDef => moduleDef(tree)
      case tree: PatDef => patDef(tree)
    }

  /**     { stats; <empty > }
   *  ==>
   *      { stats; () }
   */
  def block(tree: Block)(implicit ctx: Context): Block = tree.expr match {
    case EmptyTree =>
      cpy.Block(tree)(tree.stats,
        unitLiteral.withSpan(if (tree.stats.isEmpty) tree.span else tree.span.endPos))
    case _ =>
      tree
  }

  /** Translate infix operation expression
    *
    *     l op r     ==>    l.op(r)  if op is left-associative
    *                ==>    r.op(l)  if op is right-associative
    */
  def binop(left: Tree, op: Ident, right: Tree)(implicit ctx: Context): Apply = {
    def assignToNamedArg(arg: Tree) = arg match {
      case Assign(Ident(name), rhs) => cpy.NamedArg(arg)(name, rhs)
      case _ => arg
    }
    def makeOp(fn: Tree, arg: Tree, selectPos: Span) = {
      val args: List[Tree] = arg match {
        case Parens(arg) => assignToNamedArg(arg) :: Nil
        case Tuple(args) => args.mapConserve(assignToNamedArg)
        case _ => arg :: Nil
      }
      val sel = Select(fn, op.name).withSpan(selectPos)
      if (left.sourcePos.endLine < op.sourcePos.startLine)
        sel.pushAttachment(MultiLineInfix, ())
      Apply(sel, args)
    }

    if (isLeftAssoc(op.name))
      makeOp(left, right, Span(left.span.start, op.span.end, op.span.start))
    else
      makeOp(right, left, Span(op.span.start, right.span.end))
  }

  /** Translate tuple expressions of arity <= 22
   *
   *     ()             ==>   ()
   *     (t)            ==>   t
   *     (t1, ..., tN)  ==>   TupleN(t1, ..., tN)
   */
  def smallTuple(tree: Tuple)(implicit ctx: Context): Tree = {
    val ts = tree.trees
    val arity = ts.length
    assert(arity <= Definitions.MaxTupleArity)
    def tupleTypeRef = defn.TupleType(arity)
    if (arity == 0)
      if (ctx.mode is Mode.Type) TypeTree(defn.UnitType) else unitLiteral
    else if (ctx.mode is Mode.Type) AppliedTypeTree(ref(tupleTypeRef), ts)
    else Apply(ref(tupleTypeRef.classSymbol.companionModule.termRef), ts)
  }

  private def isTopLevelDef(stat: Tree)(using Context): Boolean = stat match
    case _: ValDef | _: PatDef | _: DefDef | _: Export => true
    case stat: ModuleDef =>
      stat.mods.isOneOf(GivenOrImplicit)
    case stat: TypeDef =>
      !stat.isClassDef || stat.mods.isOneOf(GivenOrImplicit)
    case _ =>
      false

  /** Assuming `src` contains top-level definition, returns the name that should
   *  be using for the package object that will wrap them.
   */
  def packageObjectName(src: SourceFile): TermName =
    val fileName = src.file.name
    val sourceName = fileName.take(fileName.lastIndexOf('.'))
    (sourceName ++ str.TOPLEVEL_SUFFIX).toTermName

  /** Group all definitions that can't be at the toplevel in
   *  an object named `<source>$package` where `<source>` is the name of the source file.
   *  Definitions that can't be at the toplevel are:
   *
   *   - all pattern, value and method definitions
   *   - non-class type definitions
   *   - implicit classes and objects
   *   - "companion objects" of wrapped type definitions
   *     (i.e. objects having the same name as a wrapped type)
   */
  def packageDef(pdef: PackageDef)(implicit ctx: Context): PackageDef = {
    val wrappedTypeNames = pdef.stats.collect {
      case stat: TypeDef if isTopLevelDef(stat) => stat.name
    }
    def inPackageObject(stat: Tree) =
      isTopLevelDef(stat) || {
        stat match
          case stat: ModuleDef =>
            wrappedTypeNames.contains(stat.name.stripModuleClassSuffix.toTypeName)
          case _ =>
            false
      }
    val (nestedStats, topStats) = pdef.stats.partition(inPackageObject)
    if (nestedStats.isEmpty) pdef
    else {
      val name = packageObjectName(ctx.source)
      val grouped = ModuleDef(name, Template(emptyConstructor, Nil, Nil, EmptyValDef, nestedStats))
      cpy.PackageDef(pdef)(pdef.pid, topStats :+ grouped)
    }
  }

  /** Make closure corresponding to function.
   *      params => body
   *  ==>
   *      def $anonfun(params) = body
   *      Closure($anonfun)
   */
  def makeClosure(params: List[ValDef], body: Tree, tpt: Tree = null, isContextual: Boolean)(implicit ctx: Context): Block =
    Block(
      DefDef(nme.ANON_FUN, Nil, params :: Nil, if (tpt == null) TypeTree() else tpt, body)
        .withMods(synthetic | Artifact),
      Closure(Nil, Ident(nme.ANON_FUN), if (isContextual) ContextualEmptyTree else EmptyTree))

  /** If `nparams` == 1, expand partial function
   *
   *       { cases }
   *  ==>
   *       x$1 => (x$1 @unchecked?) match { cases }
   *
   *  If `nparams` != 1, expand instead to
   *
   *       (x$1, ..., x$n) => (x$0, ..., x${n-1} @unchecked?) match { cases }
   */
  def makeCaseLambda(cases: List[CaseDef], checkMode: MatchCheck, nparams: Int = 1)(implicit ctx: Context): Function = {
    val params = (1 to nparams).toList.map(makeSyntheticParameter(_))
    val selector = makeTuple(params.map(p => Ident(p.name)))
    Function(params, Match(makeSelector(selector, checkMode), cases))
  }

  /** Map n-ary function `(x1: T1, ..., xn: Tn) => body` where n != 1 to unary function as follows:
   *
   *    (x$1: (T1, ..., Tn)) => {
   *      def x1: T1 = x$1._1
   *      ...
   *      def xn: Tn = x$1._n
   *      body
   *    }
   *
   *  or if `isGenericTuple`
   *
   *    (x$1: (T1, ... Tn) => {
   *      def x1: T1 = x$1.apply(0)
   *      ...
   *      def xn: Tn = x$1.apply(n-1)
   *      body
   *    }
   *
   *  If some of the Ti's are absent, omit the : (T1, ..., Tn) type ascription
   *  in the selector.
   */
  def makeTupledFunction(params: List[ValDef], body: Tree, isGenericTuple: Boolean)(implicit ctx: Context): Tree = {
    val param = makeSyntheticParameter(
      tpt =
        if params.exists(_.tpt.isEmpty) then TypeTree()
        else Tuple(params.map(_.tpt)))
    def selector(n: Int) =
      if (isGenericTuple) Apply(Select(refOfDef(param), nme.apply), Literal(Constant(n)))
      else Select(refOfDef(param), nme.selectorName(n))
    val vdefs =
      params.zipWithIndex.map {
        case (param, idx) =>
          DefDef(param.name, Nil, Nil, param.tpt, selector(idx)).withSpan(param.span)
      }
    Function(param :: Nil, Block(vdefs, body))
  }

  def makeContextualFunction(formals: List[Type], body: Tree, isErased: Boolean)(implicit ctx: Context): Tree = {
    val mods = if (isErased) Given | Erased else Given
    val params = makeImplicitParameters(formals.map(TypeTree), mods)
    FunctionWithMods(params, body, Modifiers(mods))
  }

  /** Add annotation to tree:
   *      tree @fullName
   *
   *  The annotation is usually represented as a TypeTree referring to the class
   *  with the given name `fullName`. However, if the annotation matches a file name
   *  that is still to be entered, the annotation is represented as a cascade of `Selects`
   *  following `fullName`. This is necessary so that we avoid reading an annotation from
   *  the classpath that is also compiled from source.
   */
  def makeAnnotated(fullName: String, tree: Tree)(implicit ctx: Context): Annotated = {
    val parts = fullName.split('.')
    val ttree = ctx.typerPhase match {
      case phase: FrontEnd if phase.stillToBeEntered(parts.last) =>
        val prefix =
          parts.init.foldLeft(Ident(nme.ROOTPKG): Tree)((qual, name) =>
            Select(qual, name.toTermName))
        Select(prefix, parts.last.toTypeName)
      case _ =>
        TypeTree(ctx.requiredClass(fullName).typeRef)
    }
    Annotated(tree, New(ttree, Nil))
  }

  private def derivedValDef(original: Tree, named: NameTree, tpt: Tree, rhs: Tree, mods: Modifiers)(implicit ctx: Context) = {
    val vdef = ValDef(named.name.asTermName, tpt, rhs)
      .withMods(mods)
      .withSpan(original.span.withPoint(named.span.start))
    val mayNeedSetter = valDef(vdef)
    mayNeedSetter
  }

  private def derivedDefDef(original: Tree, named: NameTree, tpt: Tree, rhs: Tree, mods: Modifiers)(implicit src: SourceFile) =
    DefDef(named.name.asTermName, Nil, Nil, tpt, rhs)
      .withMods(mods)
      .withSpan(original.span.withPoint(named.span.start))

  /** Main desugaring method */
  def apply(tree: Tree)(implicit ctx: Context): Tree = {

    /** Create tree for for-comprehension `<for (enums) do body>` or
     *   `<for (enums) yield body>` where mapName and flatMapName are chosen
     *  corresponding to whether this is a for-do or a for-yield.
     *  The creation performs the following rewrite rules:
     *
     *  1.
     *
     *    for (P <- G) E   ==>   G.foreach (P => E)
     *
     *     Here and in the following (P => E) is interpreted as the function (P => E)
     *     if P is a variable pattern and as the partial function { case P => E } otherwise.
     *
     *  2.
     *
     *    for (P <- G) yield E  ==>  G.map (P => E)
     *
     *  3.
     *
     *    for (P_1 <- G_1; P_2 <- G_2; ...) ...
     *      ==>
     *    G_1.flatMap (P_1 => for (P_2 <- G_2; ...) ...)
     *
     *  4.
     *
     *    for (P <- G; E; ...) ...
     *      =>
     *    for (P <- G.filter (P => E); ...) ...
     *
     *  5. For any N:
     *
     *    for (P_1 <- G; P_2 = E_2; val P_N = E_N; ...)
     *      ==>
     *    for (TupleN(P_1, P_2, ... P_N) <-
     *      for (x_1 @ P_1 <- G) yield {
     *        val x_2 @ P_2 = E_2
     *        ...
     *        val x_N & P_N = E_N
     *        TupleN(x_1, ..., x_N)
     *      } ...)
     *
     *    If any of the P_i are variable patterns, the corresponding `x_i @ P_i` is not generated
     *    and the variable constituting P_i is used instead of x_i
     *
     *  @param mapName      The name to be used for maps (either map or foreach)
     *  @param flatMapName  The name to be used for flatMaps (either flatMap or foreach)
     *  @param enums        The enumerators in the for expression
     *  @param body         The body of the for expression
     */
    def makeFor(mapName: TermName, flatMapName: TermName, enums: List[Tree], body: Tree): Tree = trace(i"make for ${ForYield(enums, body)}", show = true) {

      /** Let `pat` be `gen`'s pattern. Make a function value `pat => body`.
       *  If `pat` is a var pattern `id: T` then this gives `(id: T) => body`.
       *  Otherwise this gives `{ case pat => body }`, where `pat` is checked to be
       *  irrefutable if `gen`'s checkMode is GenCheckMode.Check.
       */
      def makeLambda(gen: GenFrom, body: Tree): Tree = gen.pat match {
        case IdPattern(named, tpt) if gen.checkMode != GenCheckMode.FilterAlways =>
          Function(derivedValDef(gen.pat, named, tpt, EmptyTree, Modifiers(Param)) :: Nil, body)
        case _ =>
          val matchCheckMode =
            if (gen.checkMode == GenCheckMode.Check) MatchCheck.IrrefutableGenFrom
            else MatchCheck.None
          makeCaseLambda(CaseDef(gen.pat, EmptyTree, body) :: Nil, matchCheckMode)
      }

      /** If `pat` is not an Identifier, a Typed(Ident, _), or a Bind, wrap
       *  it in a Bind with a fresh name. Return the transformed pattern, and the identifier
       *  that refers to the bound variable for the pattern.
       */
      def makeIdPat(pat: Tree): (Tree, Ident) = pat match {
        case Bind(name, _) => (pat, Ident(name))
        case id: Ident if isVarPattern(id) && id.name != nme.WILDCARD => (id, id)
        case Typed(id: Ident, _) if isVarPattern(id) && id.name != nme.WILDCARD => (pat, id)
        case _ =>
          val name = UniqueName.fresh()
          (Bind(name, pat), Ident(name))
      }

      /** Make a pattern filter:
       *    rhs.withFilter { case pat => true case _ => false }
       *
       *  On handling irrefutable patterns:
       *  The idea is to wait until the pattern matcher sees a call
       *
       *      xs withFilter { cases }
       *
       *  where cases can be proven to be refutable i.e. cases would be
       *  equivalent to  { case _ => true }
       *
       *  In that case, compile to
       *
       *      xs withFilter alwaysTrue
       *
       *  where `alwaysTrue` is a predefined function value:
       *
       *      val alwaysTrue: Any => Boolean = true
       *
       *  In the libraries operations can take advantage of alwaysTrue to shortcircuit the
       *  withFilter call.
       *
       *  def withFilter(f: Elem => Boolean) =
       *    if (f eq alwaysTrue) this // or rather identity filter monadic applied to this
       *    else real withFilter
       */
      def makePatFilter(rhs: Tree, pat: Tree): Tree = {
        val cases = List(
          CaseDef(pat, EmptyTree, Literal(Constant(true))),
          CaseDef(Ident(nme.WILDCARD), EmptyTree, Literal(Constant(false))))
        Apply(Select(rhs, nme.withFilter), makeCaseLambda(cases, MatchCheck.None))
      }

      /** Is pattern `pat` irrefutable when matched against `rhs`?
       *  We only can do a simple syntactic check here; a more refined check
       *  is done later in the pattern matcher (see discussion in @makePatFilter).
       */
      def isIrrefutable(pat: Tree, rhs: Tree): Boolean = {
        def matchesTuple(pats: List[Tree], rhs: Tree): Boolean = rhs match {
          case Tuple(trees) => (pats corresponds trees)(isIrrefutable)
          case Parens(rhs1) => matchesTuple(pats, rhs1)
          case Block(_, rhs1) => matchesTuple(pats, rhs1)
          case If(_, thenp, elsep) => matchesTuple(pats, thenp) && matchesTuple(pats, elsep)
          case Match(_, cases) => cases forall (matchesTuple(pats, _))
          case CaseDef(_, _, rhs1) => matchesTuple(pats, rhs1)
          case Throw(_) => true
          case _ => false
        }
        pat match {
          case Bind(_, pat1) => isIrrefutable(pat1, rhs)
          case Parens(pat1) => isIrrefutable(pat1, rhs)
          case Tuple(pats) => matchesTuple(pats, rhs)
          case _ => isVarPattern(pat)
        }
      }

      def needsNoFilter(gen: GenFrom): Boolean =
        if (gen.checkMode == GenCheckMode.FilterAlways) // pattern was prefixed by `case`
          false
        else (
          gen.checkMode != GenCheckMode.FilterNow ||
          IdPattern.unapply(gen.pat).isDefined ||
          isIrrefutable(gen.pat, gen.expr)
        )

      /** rhs.name with a pattern filter on rhs unless `pat` is irrefutable when
       *  matched against `rhs`.
       */
      def rhsSelect(gen: GenFrom, name: TermName) = {
        val rhs = if (needsNoFilter(gen)) gen.expr else makePatFilter(gen.expr, gen.pat)
        Select(rhs, name)
      }

      def checkMode(gen: GenFrom) =
        if (gen.checkMode == GenCheckMode.Check) MatchCheck.IrrefutableGenFrom
        else MatchCheck.None // refutable paterns were already eliminated in filter step

      enums match {
        case (gen: GenFrom) :: Nil =>
          Apply(rhsSelect(gen, mapName), makeLambda(gen, body))
        case (gen: GenFrom) :: (rest @ (GenFrom(_, _, _) :: _)) =>
          val cont = makeFor(mapName, flatMapName, rest, body)
          Apply(rhsSelect(gen, flatMapName), makeLambda(gen, cont))
        case (gen: GenFrom) :: (rest @ GenAlias(_, _) :: _) =>
          val (valeqs, rest1) = rest.span(_.isInstanceOf[GenAlias])
          val pats = valeqs map { case GenAlias(pat, _) => pat }
          val rhss = valeqs map { case GenAlias(_, rhs) => rhs }
          val (defpat0, id0) = makeIdPat(gen.pat)
          val (defpats, ids) = (pats map makeIdPat).unzip
          val pdefs = valeqs.lazyZip(defpats).lazyZip(rhss).map(makePatDef(_, Modifiers(), _, _))
          val rhs1 = makeFor(nme.map, nme.flatMap, GenFrom(defpat0, gen.expr, gen.checkMode) :: Nil, Block(pdefs, makeTuple(id0 :: ids)))
          val allpats = gen.pat :: pats
          val vfrom1 = GenFrom(makeTuple(allpats), rhs1, GenCheckMode.Ignore)
          makeFor(mapName, flatMapName, vfrom1 :: rest1, body)
        case (gen: GenFrom) :: test :: rest =>
          val filtered = Apply(rhsSelect(gen, nme.withFilter), makeLambda(gen, test))
          val genFrom = GenFrom(gen.pat, filtered, GenCheckMode.Ignore)
          makeFor(mapName, flatMapName, genFrom :: rest, body)
        case _ =>
          EmptyTree //may happen for erroneous input
      }
    }

    def makePolyFunction(targs: List[Tree], body: Tree): Tree = body match {
      case Parens(body1) =>
        makePolyFunction(targs, body1)
      case Function(vargs, res) =>
        // TODO: Figure out if we need a `PolyFunctionWithMods` instead.
        val mods = body match {
          case body: FunctionWithMods => body.mods
          case _ => untpd.EmptyModifiers
        }
        val polyFunctionTpt = ref(defn.PolyFunctionType)
        val applyTParams = targs.asInstanceOf[List[TypeDef]]
        if (ctx.mode.is(Mode.Type)) {
          // Desugar [T_1, ..., T_M] -> (P_1, ..., P_N) => R
          // Into    scala.PolyFunction { def apply[T_1, ..., T_M](x$1: P_1, ..., x$N: P_N): R }

          val applyVParams = vargs.zipWithIndex.map {
            case (p: ValDef, _) => p.withAddedFlags(mods.flags)
            case (p, n) => makeSyntheticParameter(n + 1, p).withAddedFlags(mods.flags)
          }
          RefinedTypeTree(polyFunctionTpt, List(
            DefDef(nme.apply, applyTParams, List(applyVParams), res, EmptyTree)
          ))
        }
        else {
          // Desugar [T_1, ..., T_M] -> (x_1: P_1, ..., x_N: P_N) => body
          // Into    new scala.PolyFunction { def apply[T_1, ..., T_M](x_1: P_1, ..., x_N: P_N) = body }

          val applyVParams = vargs.asInstanceOf[List[ValDef]]
            .map(varg => varg.withAddedFlags(mods.flags | Param))
            New(Template(emptyConstructor, List(polyFunctionTpt), Nil, EmptyValDef,
              List(DefDef(nme.apply, applyTParams, List(applyVParams), TypeTree(), res))
              ))
        }
      case _ =>
        // may happen for erroneous input. An error will already have been reported.
        assert(ctx.reporter.errorsReported)
        EmptyTree
    }

    // begin desugar

    // Special case for `Parens` desugaring: unlike all the desugarings below,
    // its output is not a new tree but an existing one whose position should
    // be preserved, so we shouldn't call `withPos` on it.
    tree match {
      case Parens(t) =>
        return t
      case _ =>
    }

    val desugared = tree match {
      case PolyFunction(targs, body) =>
        makePolyFunction(targs, body) orElse tree
      case SymbolLit(str) =>
        Apply(
          ref(defn.ScalaSymbolClass.companionModule.termRef),
          Literal(Constant(str)) :: Nil)
      case InterpolatedString(id, segments) =>
        val strs = segments map {
          case ts: Thicket => ts.trees.head
          case t => t
        }
        val elems = segments flatMap {
          case ts: Thicket => ts.trees.tail
          case t => Nil
        } map {
          case Block(Nil, EmptyTree) => Literal(Constant(())) // for s"... ${} ..."
          case Block(Nil, expr) => expr // important for interpolated string as patterns, see i1773.scala
          case t => t
        }
        // This is a deliberate departure from scalac, where StringContext is not rooted (See #4732)
        Apply(Select(Apply(scalaDot(nme.StringContext), strs), id).withSpan(tree.span), elems)
      case PostfixOp(t, op) =>
        if ((ctx.mode is Mode.Type) && !isBackquoted(op) && op.name == tpnme.raw.STAR) {
          val seqType = if (ctx.compilationUnit.isJava) defn.ArrayType else defn.SeqType
          Annotated(
            AppliedTypeTree(ref(seqType), t),
            New(ref(defn.RepeatedAnnot.typeRef), Nil :: Nil))
        }
        else {
          assert(ctx.mode.isExpr || ctx.reporter.errorsReported || ctx.mode.is(Mode.Interactive), ctx.mode)
          if (!enabled(nme.postfixOps)) {
            ctx.error(
              s"""postfix operator `${op.name}` needs to be enabled
                 |by making the implicit value scala.language.postfixOps visible.
                 |----
                 |This can be achieved by adding the import clause 'import scala.language.postfixOps'
                 |or by setting the compiler option -language:postfixOps.
                 |See the Scaladoc for value scala.language.postfixOps for a discussion
                 |why the feature needs to be explicitly enabled.""".stripMargin, t.sourcePos)
          }
          Select(t, op.name)
        }
      case PrefixOp(op, t) =>
        val nspace = if (ctx.mode.is(Mode.Type)) tpnme else nme
        Select(t, nspace.UNARY_PREFIX ++ op.name)
      case ForDo(enums, body) =>
        makeFor(nme.foreach, nme.foreach, enums, body) orElse tree
      case ForYield(enums, body) =>
        makeFor(nme.map, nme.flatMap, enums, body) orElse tree
      case PatDef(mods, pats, tpt, rhs) =>
        val pats1 = if (tpt.isEmpty) pats else pats map (Typed(_, tpt))
        flatTree(pats1 map (makePatDef(tree, mods, _, rhs)))
    }
    desugared.withSpan(tree.span)
  }

  /** Turn a fucntion value `handlerFun` into a catch case for a try.
   *  If `handlerFun` is a partial function, translate to
   *
   *    case ex =>
   *      val ev$1 = handlerFun
   *      if ev$1.isDefinedAt(ex) then ev$1.apply(ex) else throw ex
   *
   *  Otherwise translate to
   *
   *     case ex => handlerFun.apply(ex)
   */
  def makeTryCase(handlerFun: tpd.Tree)(using Context): CaseDef =
    val handler = TypedSplice(handlerFun)
    val excId = Ident(nme.DEFAULT_EXCEPTION_NAME)
    val rhs =
      if handlerFun.tpe.widen.isRef(defn.PartialFunctionClass) then
        val tmpName = UniqueName.fresh()
        val tmpId = Ident(tmpName)
        val init = ValDef(tmpName, TypeTree(), handler)
        val test = If(
          Apply(Select(tmpId, nme.isDefinedAt), excId),
          Apply(Select(tmpId, nme.apply), excId),
          Throw(excId))
        Block(init :: Nil, test)
      else
        Apply(Select(handler, nme.apply), excId)
    CaseDef(excId, EmptyTree, rhs)

  /** Create a class definition with the same info as the refined type given by `parent`
   *  and `refinements`.
   *
   *      parent { refinements }
   *  ==>
   *      trait <refinement> extends core { this: self => refinements }
   *
   *  Here, `core` is the (possibly parameterized) class part of `parent`.
   *  If `parent` is the same as `core`, self is empty. Otherwise `self` is `parent`.
   *
   *  Example: Given
   *
   *      class C
   *      type T1 = C { type T <: A }
   *
   *  the refined type
   *
   *      T1 { type T <: B }
   *
   *  is expanded to
   *
   *      trait <refinement> extends C { this: T1 => type T <: A }
   *
   *  The result of this method is used for validity checking, is thrown away afterwards.
   *  @param parent  The type of `parent`
   */
  def refinedTypeToClass(parent: tpd.Tree, refinements: List[Tree])(implicit ctx: Context): TypeDef = {
    def stripToCore(tp: Type): List[Type] = tp match {
      case tp: AppliedType => tp :: Nil
      case tp: TypeRef if tp.symbol.isClass => tp :: Nil     // monomorphic class type
      case tp: TypeProxy => stripToCore(tp.underlying)
      case AndType(tp1, tp2) => stripToCore(tp1) ::: stripToCore(tp2)
      case _ => defn.AnyType :: Nil
    }
    val parentCores = stripToCore(parent.tpe)
    val untpdParent = TypedSplice(parent)
    val (classParents, self) =
      if (parentCores.length == 1 && (parent.tpe eq parentCores.head)) (untpdParent :: Nil, EmptyValDef)
      else (parentCores map TypeTree, ValDef(nme.WILDCARD, untpdParent, EmptyTree))
    val impl = Template(emptyConstructor, classParents, Nil, self, refinements)
    TypeDef(tpnme.REFINE_CLASS, impl).withFlags(Trait)
  }

  /** Returns list of all pattern variables, possibly with their types,
   *  without duplicates
   */
  private def getVariables(tree: Tree)(implicit ctx: Context): List[VarInfo] = {
    val buf = ListBuffer[VarInfo]()
    def seenName(name: Name) = buf exists (_._1.name == name)
    def add(named: NameTree, t: Tree): Unit =
      if (!seenName(named.name) && named.name.isTermName) buf += ((named, t))
    def collect(tree: Tree): Unit = tree match {
      case Bind(nme.WILDCARD, tree1) =>
        collect(tree1)
      case tree @ Bind(_, Typed(tree1, tpt)) =>
        add(tree, tpt)
        collect(tree1)
      case tree @ Bind(_, tree1) =>
        add(tree, TypeTree())
        collect(tree1)
      case Typed(id: Ident, t) if isVarPattern(id) && id.name != nme.WILDCARD && !isWildcardStarArg(tree) =>
        add(id, t)
      case id: Ident if isVarPattern(id) && id.name != nme.WILDCARD =>
        add(id, TypeTree())
      case Apply(_, args) =>
        args foreach collect
      case Typed(expr, _) =>
        collect(expr)
      case NamedArg(_, arg) =>
        collect(arg)
      case SeqLiteral(elems, _) =>
        elems foreach collect
      case Alternative(trees) =>
        for (tree <- trees; (vble, _) <- getVariables(tree))
          ctx.error(IllegalVariableInPatternAlternative(), vble.sourcePos)
      case Annotated(arg, _) =>
        collect(arg)
      case InterpolatedString(_, segments) =>
        segments foreach collect
      case InfixOp(left, _, right) =>
        collect(left)
        collect(right)
      case PrefixOp(_, od) =>
        collect(od)
      case Parens(tree) =>
        collect(tree)
      case Tuple(trees) =>
        trees foreach collect
      case Thicket(trees) =>
        trees foreach collect
      case Block(Nil, expr) =>
        collect(expr)
      case Quote(expr) =>
        new UntypedTreeTraverser {
          def traverse(tree: untpd.Tree)(implicit ctx: Context): Unit = tree match {
            case Splice(expr) => collect(expr)
            case TypSplice(expr) =>
              ctx.error(TypeSpliceInValPattern(expr), tree.sourcePos)
            case _ => traverseChildren(tree)
          }
        }.traverse(expr)
      case _ =>
    }
    collect(tree)
    buf.toList
  }
}
