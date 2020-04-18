package dotty.tools.dotc
package tastyreflect

import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.ast.{TreeTypeMap, Trees, tpd, untpd}
import dotty.tools.dotc.typer.{Implicits, Typer}
import dotty.tools.dotc.core._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.quoted.PickledQuotes
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Types.SingletonType
import dotty.tools.dotc.tastyreflect.FromSymbol.{definitionFromSym, packageDefFromSym}
import dotty.tools.dotc.typer.Implicits.{AmbiguousImplicits, DivergingImplicit, NoMatchingImplicits, SearchFailure, SearchFailureType}
import dotty.tools.dotc.util.{SourceFile, SourcePosition, Spans}

import scala.internal.quoted.Unpickler
import scala.tasty.reflect.CompilerInterface

import scala.tasty.reflect.IsInstanceOf

class ReflectionCompilerInterface(val rootContext: core.Contexts.Context) extends CompilerInterface {
  import tpd._

  private given core.Contexts.Context = rootContext

  def settings: Settings = rootContext.settings

  def rootPosition: util.SourcePosition =
    tastyreflect.MacroExpansion.position.getOrElse(SourcePosition(rootContext.source, Spans.NoSpan))


  //////////////////////
  // QUOTE UNPICKLING //
  //////////////////////

  def unpickleExpr(repr: Unpickler.PickledQuote, args: Unpickler.PickledArgs): scala.quoted.Expr[?] =
    new TastyTreeExpr(PickledQuotes.unpickleExpr(repr, args), compilerId)

  def unpickleType(repr: Unpickler.PickledQuote, args: Unpickler.PickledArgs): scala.quoted.Type[?] =
    new TreeType(PickledQuotes.unpickleType(repr, args), compilerId)


  /////////////
  // CONTEXT //
  /////////////

  type Context = core.Contexts.Context

  def Context_owner(self: Context): Symbol = self.owner

  def Context_source(self: Context): java.nio.file.Path = self.compilationUnit.source.file.jpath

  def Context_GADT_setFreshGADTBounds(self: Context): Context =
    self.fresh.setFreshGADTBounds.addMode(Mode.GadtConstraintInference)

  def Context_GADT_addToConstraint(self: Context)(syms: List[Symbol]): Boolean =
    self.gadt.addToConstraint(syms)

  def Context_GADT_approximation(self: Context)(sym: Symbol, fromBelow: Boolean): Type =
    self.gadt.approximation(sym, fromBelow)

  def Context_requiredPackage(self: Context)(path: String): Symbol = self.requiredPackage(path)
  def Context_requiredClass(self: Context)(path: String): Symbol = self.requiredClass(path)
  def Context_requiredModule(self: Context)(path: String): Symbol = self.requiredModule(path)
  def Context_requiredMethod(self: Context)(path: String): Symbol = self.requiredMethod(path)
  def Context_isJavaCompilationUnit(self: Context): Boolean = self.compilationUnit.isInstanceOf[fromtasty.JavaCompilationUnit]
  def Context_isScala2CompilationUnit(self: Context): Boolean = self.compilationUnit.isInstanceOf[fromtasty.Scala2CompilationUnit]
  def Context_isAlreadyLoadedCompilationUnit(self: Context): Boolean = self.compilationUnit.isInstanceOf[fromtasty.AlreadyLoadedCompilationUnit]
  def Context_compilationUnitClassname(self: Context): String =
    self.compilationUnit match {
      case cu: fromtasty.JavaCompilationUnit => cu.className
      case cu: fromtasty.Scala2CompilationUnit => cu.className
      case cu: fromtasty.AlreadyLoadedCompilationUnit => cu.className
      case cu => ""
    }


  ///////////////
  // REPORTING //
  ///////////////

  def error(msg: => String, pos: Position)(using ctx: Context): Unit =
    ctx.error(msg, pos)

  def error(msg: => String, sourceFile: SourceFile, start: Int, end: Int)(using ctx: Context): Unit =
    ctx.error(msg, util.SourcePosition(sourceFile, util.Spans.Span(start, end)))

  def warning(msg: => String, pos: Position)(using ctx: Context): Unit =
    ctx.warning(msg, pos)

  def warning(msg: => String, sourceFile: SourceFile, start: Int, end: Int)(using ctx: Context): Unit =
    ctx.error(msg, util.SourcePosition(sourceFile, util.Spans.Span(start, end)))


  //////////////
  // Settings //
  //////////////

  type Settings = config.ScalaSettings

  def Settings_color(self: Settings): Boolean = self.color.value(rootContext) == "always"


  ///////////
  // TREES //
  ///////////

  type Tree = tpd.Tree

  def Tree_pos(self: Tree)(using ctx: Context): Position = self.sourcePos
  def Tree_symbol(self: Tree)(using ctx: Context): Symbol = self.symbol

  type PackageClause = tpd.PackageDef

  def isInstanceOfPackageClause(using ctx: Context): IsInstanceOf[PackageClause] = new {
    def runtimeClass: Class[?] = classOf[PackageClause]
    override def unapply(x: Any): Option[PackageClause] = x match
      case x: tpd.PackageDef => Some(x)
      case _ => None
  }

  def PackageClause_pid(self: PackageClause)(using ctx: Context): Ref = self.pid
  def PackageClause_stats(self: PackageClause)(using ctx: Context): List[Tree] = self.stats

  def PackageClause_apply(pid: Ref, stats: List[Tree])(using ctx: Context): PackageClause =
    withDefaultPos(tpd.PackageDef(pid.asInstanceOf[tpd.RefTree], stats))

  def PackageClause_copy(original: Tree)(pid: Ref, stats: List[Tree])(using ctx: Context): PackageClause =
    tpd.cpy.PackageDef(original)(pid, stats)

  type Statement = tpd.Tree

  def isInstanceOfStatement(using ctx: Context): IsInstanceOf[Statement] = new {
    def runtimeClass: Class[?] = classOf[Statement]
    override def unapply(x: Any): Option[Statement] = x match
      case _: PatternTree => None
      case tree: Tree if tree.isTerm => isInstanceOfTerm.unapply(tree)
      case tree: Tree => isInstanceOfDefinition.unapply(tree)
      case _ => None
  }

  type Import = tpd.Import

  def isInstanceOfImport(using ctx: Context): IsInstanceOf[Import] = new {
    def runtimeClass: Class[?] = classOf[Import]
    override def unapply(x: Any): Option[Import] = x match
      case tree: tpd.Import => Some(tree)
      case _ => None
  }

  def Import_implied(self: Import): Boolean = false // TODO: adapt to new import scheme
  def Import_expr(self: Import)(using ctx: Context): Tree = self.expr
  def Import_selectors(self: Import)(using ctx: Context): List[ImportSelector] = self.selectors

  def Import_apply(expr: Term, selectors: List[ImportSelector])(using ctx: Context): Import =
    withDefaultPos(tpd.Import(expr, selectors))

  def Import_copy(original: Tree)(expr: Term, selectors: List[ImportSelector])(using ctx: Context): Import =
    tpd.cpy.Import(original)(expr, selectors)

  type Definition = tpd.Tree

  def isInstanceOfDefinition(using ctx: Context): IsInstanceOf[Definition] = new {
    def runtimeClass: Class[?] = classOf[Definition]
    override def unapply(x: Any): Option[Definition] = x match
      case x: tpd.MemberDef => Some(x)
      case x: PackageDefinition => Some(x)
      case _ => None
  }

  def Definition_name(self: Definition)(using ctx: Context): String = self match {
    case self: tpd.MemberDef => self.name.toString
    case self: PackageDefinition => self.symbol.name.toString // TODO make PackageDefinition a MemberDef or NameTree
  }

  type PackageDef = PackageDefinition

  def isInstanceOfPackageDef(using ctx: Context): IsInstanceOf[PackageDef] = new {
    def runtimeClass: Class[?] = classOf[PackageDef]
    override def unapply(x: Any): Option[PackageDef] = x match
      case x: PackageDefinition => Some(x)
      case _ => None
  }

  def PackageDef_owner(self: PackageDef)(using ctx: Context): PackageDef = packageDefFromSym(self.symbol.owner)

  def PackageDef_members(self: PackageDef)(using ctx: Context): List[Statement] =
    if (self.symbol.is(core.Flags.JavaDefined)) Nil // FIXME should also support java packages
    else self.symbol.info.decls.iterator.map(definitionFromSym).toList

  type ClassDef = tpd.TypeDef

  def isInstanceOfClassDef(using ctx: Context): IsInstanceOf[ClassDef] = new {
    def runtimeClass: Class[?] = classOf[ClassDef]
    override def unapply(x: Any): Option[ClassDef] = x match
      case x: tpd.TypeDef if x.isClassDef => Some(x)
      case _ => None
  }

  def ClassDef_constructor(self: ClassDef)(using ctx: Context): DefDef = ClassDef_rhs(self).constr
  def ClassDef_parents(self: ClassDef)(using ctx: Context): List[Term | TypeTree] = ClassDef_rhs(self).parents
  def ClassDef_derived(self: ClassDef)(using ctx: Context): List[TypeTree] = ClassDef_rhs(self).derived.asInstanceOf[List[TypeTree]]
  def ClassDef_self(self: ClassDef)(using ctx: Context): Option[ValDef] = optional(ClassDef_rhs(self).self)
  def ClassDef_body(self: ClassDef)(using ctx: Context): List[Statement] = ClassDef_rhs(self).body
  private def ClassDef_rhs(self: ClassDef) = self.rhs.asInstanceOf[tpd.Template]

  def ClassDef_copy(original: Tree)(name: String, constr: DefDef, parents: List[Term | TypeTree], derived: List[TypeTree], selfOpt: Option[ValDef], body: List[Statement])(using ctx: Context): ClassDef = {
    val Trees.TypeDef(_, originalImpl: tpd.Template) = original
    tpd.cpy.TypeDef(original)(name.toTypeName, tpd.cpy.Template(originalImpl)(constr, parents, derived, selfOpt.getOrElse(tpd.EmptyValDef), body))
  }

  type TypeDef = tpd.TypeDef

  def isInstanceOfTypeDef(using ctx: Context): IsInstanceOf[TypeDef] = new {
    def runtimeClass: Class[?] = classOf[TypeDef]
    override def unapply(x: Any): Option[TypeDef] = x match
      case x: tpd.TypeDef if !x.isClassDef => Some(x)
      case _ => None
  }

  def TypeDef_rhs(self: TypeDef)(using ctx: Context): TypeTree | TypeBoundsTree = self.rhs

  def TypeDef_apply(symbol: Symbol)(using ctx: Context): TypeDef = withDefaultPos(tpd.TypeDef(symbol.asType))
  def TypeDef_copy(original: Tree)(name: String, rhs: TypeTree | TypeBoundsTree)(using ctx: Context): TypeDef =
    tpd.cpy.TypeDef(original)(name.toTypeName, rhs)

  type DefDef = tpd.DefDef

  def isInstanceOfDefDef(using ctx: Context): IsInstanceOf[DefDef] = new {
    def runtimeClass: Class[?] = classOf[DefDef]
    override def unapply(x: Any): Option[DefDef] = x match
      case x: tpd.DefDef => Some(x)
      case _ => None
  }

  def DefDef_typeParams(self: DefDef)(using ctx: Context): List[TypeDef] = self.tparams
  def DefDef_paramss(self: DefDef)(using ctx: Context): List[List[ValDef]] = self.vparamss
  def DefDef_returnTpt(self: DefDef)(using ctx: Context): TypeTree = self.tpt
  def DefDef_rhs(self: DefDef)(using ctx: Context): Option[Tree] = optional(self.rhs)

  def DefDef_apply(symbol: Symbol, rhsFn: List[Type] => List[List[Term]] => Option[Term])(using ctx: Context): DefDef =
    withDefaultPos(tpd.polyDefDef(symbol.asTerm, tparams => vparamss => rhsFn(tparams)(vparamss).getOrElse(tpd.EmptyTree)))

  def DefDef_copy(original: Tree)(name: String, typeParams: List[TypeDef], paramss: List[List[ValDef]], tpt: TypeTree, rhs: Option[Term])(using ctx: Context): DefDef =
    tpd.cpy.DefDef(original)(name.toTermName, typeParams, paramss, tpt, rhs.getOrElse(tpd.EmptyTree))

  type ValDef = tpd.ValDef

  def isInstanceOfValDef(using ctx: Context): IsInstanceOf[ValDef] = new {
    def runtimeClass: Class[?] = classOf[ValDef]
    override def unapply(x: Any): Option[ValDef] = x match
      case x: tpd.ValDef => Some(x)
      case _ => None
  }

  def ValDef_tpt(self: ValDef)(using ctx: Context): TypeTree = self.tpt
  def ValDef_rhs(self: ValDef)(using ctx: Context): Option[Tree] = optional(self.rhs)

  def ValDef_apply(symbol: Symbol, rhs: Option[Term])(using ctx: Context): ValDef =
    tpd.ValDef(symbol.asTerm, rhs.getOrElse(tpd.EmptyTree))

  def ValDef_copy(original: Tree)(name: String, tpt: TypeTree, rhs: Option[Term])(using ctx: Context): ValDef =
    tpd.cpy.ValDef(original)(name.toTermName, tpt, rhs.getOrElse(tpd.EmptyTree))

  type Term = tpd.Tree

  def isInstanceOfTerm(using ctx: Context): IsInstanceOf[Term] = new {
    def runtimeClass: Class[?] = classOf[Term]
    override def unapply(x: Any): Option[Term] = x match
      case _ if isInstanceOfUnapply.unapply(x).isDefined => None
      case _: tpd.PatternTree => None
      case x: tpd.SeqLiteral => Some(x)
      case x: tpd.Tree if x.isTerm => Some(x)
      case _ => None
  }

  def Term_tpe(self: Term)(using ctx: Context): Type = self.tpe
  def Term_underlyingArgument(self: Term)(using ctx: Context): Term = self.underlyingArgument
  def Term_underlying(self: Term)(using ctx: Context): Term = self.underlying

  def Term_etaExpand(term: Term)(using ctx: Context): Term = term.tpe.widen match {
    case mtpe: Types.MethodType if !mtpe.isParamDependent =>
      val closureResType = mtpe.resType match {
        case t: Types.MethodType => t.toFunctionType()
        case t => t
      }
      val closureTpe = Types.MethodType(mtpe.paramNames, mtpe.paramInfos, closureResType)
      val closureMethod = ctx.newSymbol(ctx.owner, nme.ANON_FUN, Synthetic | Method, closureTpe)
      tpd.Closure(closureMethod, tss => Term_etaExpand(new tpd.TreeOps(term).appliedToArgs(tss.head)))
    case _ => term
  }

  def TypeRef_apply(sym: Symbol)(using ctx: Context): TypeTree = {
    assert(sym.isType)
    withDefaultPos(tpd.ref(sym).asInstanceOf[tpd.TypeTree])
  }

  type Ref = tpd.RefTree

  def isInstanceOfRef(using ctx: Context): IsInstanceOf[Ref] = new {
    def runtimeClass: Class[?] = classOf[Ref]
    override def unapply(x: Any): Option[Ref] = x match
      case x: tpd.RefTree if x.isTerm => Some(x)
      case _ => None
  }

  def Ref_apply(sym: Symbol)(using ctx: Context): Ref = {
    assert(sym.isTerm)
    withDefaultPos(tpd.ref(sym).asInstanceOf[tpd.RefTree])
  }

  type Ident = tpd.Ident

  def isInstanceOfIdent(using ctx: Context): IsInstanceOf[Ident] = new {
    def runtimeClass: Class[?] = classOf[Ident]
    override def unapply(x: Any): Option[Ident] = x match
      case x: tpd.Ident if x.isTerm => Some(x)
      case _ => None
  }

  def Ident_name(self: Ident)(using ctx: Context): String = self.name.show

  def Ident_apply(tmref: TermRef)(using ctx: Context): Term =
    withDefaultPos(tpd.ref(tmref).asInstanceOf[Term])

  def Ident_copy(original: Tree)(name: String)(using ctx: Context): Ident =
    tpd.cpy.Ident(original)(name.toTermName)

  type Select = tpd.Select

  def isInstanceOfSelect(using ctx: Context): IsInstanceOf[Select] = new {
    def runtimeClass: Class[?] = classOf[Select]
    override def unapply(x: Any): Option[Select] = x match
      case x: tpd.Select if x.isTerm => Some(x)
      case _ => None
  }

  def Select_qualifier(self: Select)(using ctx: Context): Term = self.qualifier
  def Select_name(self: Select)(using ctx: Context): String = self.name.toString
  def Select_signature(self: Select)(using ctx: Context): Option[Signature] =
    if (self.symbol.signature == core.Signature.NotAMethod) None
    else Some(self.symbol.signature)

  def Select_apply(qualifier: Term, symbol: Symbol)(using ctx: Context): Select =
    withDefaultPos(tpd.Select(qualifier, Types.TermRef(qualifier.tpe, symbol)))

  def Select_unique(qualifier: Term, name: String)(using ctx: Context): Select = {
    val denot = qualifier.tpe.member(name.toTermName)
    assert(!denot.isOverloaded, s"The symbol `$name` is overloaded. The method Select.unique can only be used for non-overloaded symbols.")
    withDefaultPos(tpd.Select(qualifier, name.toTermName))
  }

  // TODO rename, this returns an Apply and not a Select
  def Select_overloaded(qualifier: Term, name: String, targs: List[Type], args: List[Term])(using ctx: Context): Apply =
    withDefaultPos(tpd.applyOverloaded(qualifier, name.toTermName, args, targs, Types.WildcardType).asInstanceOf[Apply])

  def Select_copy(original: Tree)(qualifier: Term, name: String)(using ctx: Context): Select =
    tpd.cpy.Select(original)(qualifier, name.toTermName)

  type Literal = tpd.Literal

  def isInstanceOfLiteral(using ctx: Context): IsInstanceOf[Literal] = new {
    def runtimeClass: Class[?] = classOf[Literal]
    override def unapply(x: Any): Option[Literal] = x match
      case x: tpd.Literal => Some(x)
      case _ => None
  }

  def Literal_constant(self: Literal)(using ctx: Context): Constant = self.const

  def Literal_apply(constant: Constant)(using ctx: Context): Literal =
    withDefaultPos(tpd.Literal(constant))

  def Literal_copy(original: Tree)(constant: Constant)(using ctx: Context): Literal =
    tpd.cpy.Literal(original)(constant)

  type This = tpd.This

  def isInstanceOfThis(using ctx: Context): IsInstanceOf[This] = new {
    def runtimeClass: Class[?] = classOf[This]
    override def unapply(x: Any): Option[This] = x match
      case x: tpd.This => Some(x)
      case _ => None
  }

  def This_id(self: This)(using ctx: Context): Option[Id] = optional(self.qual)

  def This_apply(cls: Symbol)(using ctx: Context): This =
    withDefaultPos(tpd.This(cls.asClass))

  def This_copy(original: Tree)(qual: Option[Id])(using ctx: Context): This =
    tpd.cpy.This(original)(qual.getOrElse(untpd.EmptyTypeIdent))

  type New = tpd.New

  def isInstanceOfNew(using ctx: Context): IsInstanceOf[New] = new {
    def runtimeClass: Class[?] = classOf[New]
    override def unapply(x: Any): Option[New] = x match
      case x: tpd.New => Some(x)
      case _ => None
  }

  def New_tpt(self: New)(using ctx: Context): TypeTree = self.tpt

  def New_apply(tpt: TypeTree)(using ctx: Context): New = withDefaultPos(tpd.New(tpt))

  def New_copy(original: Tree)(tpt: TypeTree)(using ctx: Context): New =
    tpd.cpy.New(original)(tpt)

  type NamedArg = tpd.NamedArg

  def isInstanceOfNamedArg(using ctx: Context): IsInstanceOf[NamedArg] = new {
    def runtimeClass: Class[?] = classOf[NamedArg]
    override def unapply(x: Any): Option[NamedArg] = x match
      case x: tpd.NamedArg if x.name.isInstanceOf[core.Names.TermName] => Some(x) // TODO: Now, the name should alwas be a term name
      case _ => None
  }

  def NamedArg_name(self: NamedArg)(using ctx: Context): String = self.name.toString
  def NamedArg_value(self: NamedArg)(using ctx: Context): Term = self.arg

  def NamedArg_apply(name: String, arg: Term)(using ctx: Context): NamedArg =
    withDefaultPos(tpd.NamedArg(name.toTermName, arg))

  def NamedArg_copy(original: Tree)(name: String, arg: Term)(using ctx: Context): NamedArg =
    tpd.cpy.NamedArg(original)(name.toTermName, arg)

  type Apply = tpd.Apply

  def isInstanceOfApply(using ctx: Context): IsInstanceOf[Apply] = new {
    def runtimeClass: Class[?] = classOf[Apply]
    override def unapply(x: Any): Option[Apply] = x match
      case x: tpd.Apply => Some(x)
      case _ => None
  }

  def Apply_fun(self: Apply)(using ctx: Context): Term = self.fun
  def Apply_args(self: Apply)(using ctx: Context): List[Term] = self.args


  def Apply_apply(fn: Term, args: List[Term])(using ctx: Context): Apply =
    withDefaultPos(tpd.Apply(fn, args))

  def Apply_copy(original: Tree)(fun: Term, args: List[Term])(using ctx: Context): Apply =
    tpd.cpy.Apply(original)(fun, args)

  type TypeApply = tpd.TypeApply

  def isInstanceOfTypeApply(using ctx: Context): IsInstanceOf[TypeApply] = new {
    def runtimeClass: Class[?] = classOf[TypeApply]
    override def unapply(x: Any): Option[TypeApply] = x match
      case x: tpd.TypeApply => Some(x)
      case _ => None
  }

  def TypeApply_fun(self: TypeApply)(using ctx: Context): Term = self.fun
  def TypeApply_args(self: TypeApply)(using ctx: Context): List[TypeTree] = self.args

  def TypeApply_apply(fn: Term, args: List[TypeTree])(using ctx: Context): TypeApply =
    withDefaultPos(tpd.TypeApply(fn, args))

  def TypeApply_copy(original: Tree)(fun: Term, args: List[TypeTree])(using ctx: Context): TypeApply =
    tpd.cpy.TypeApply(original)(fun, args)

  type Super = tpd.Super

  def isInstanceOfSuper(using ctx: Context): IsInstanceOf[Super] = new {
    def runtimeClass: Class[?] = classOf[Super]
    override def unapply(x: Any): Option[Super] = x match
      case x: tpd.Super => Some(x)
      case _ => None
  }

  def Super_qualifier(self: Super)(using ctx: Context): Term = self.qual
  def Super_id(self: Super)(using ctx: Context): Option[Id] = optional(self.mix)

  def Super_apply(qual: Term, mix: Option[Id])(using ctx: Context): Super =
    withDefaultPos(tpd.Super(qual, mix.getOrElse(untpd.EmptyTypeIdent), NoSymbol))

  def Super_copy(original: Tree)(qual: Term, mix: Option[Id])(using ctx: Context): Super =
    tpd.cpy.Super(original)(qual, mix.getOrElse(untpd.EmptyTypeIdent))

  type Typed = tpd.Typed

  def isInstanceOfTyped(using ctx: Context): IsInstanceOf[Typed] = new {
    def runtimeClass: Class[?] = classOf[Typed]
    override def unapply(x: Any): Option[Typed] = x match
      case x: tpd.Typed => Some(x)
      case _ => None
  }

  def Typed_expr(self: Typed)(using ctx: Context): Term = self.expr
  def Typed_tpt(self: Typed)(using ctx: Context): TypeTree = self.tpt

  def Typed_apply(expr: Term, tpt: TypeTree)(using ctx: Context): Typed =
    withDefaultPos(tpd.Typed(expr, tpt))

  def Typed_copy(original: Tree)(expr: Term, tpt: TypeTree)(using ctx: Context): Typed =
    tpd.cpy.Typed(original)(expr, tpt)

  type Assign = tpd.Assign

  def isInstanceOfAssign(using ctx: Context): IsInstanceOf[Assign] = new {
    def runtimeClass: Class[?] = classOf[Assign]
    override def unapply(x: Any): Option[Assign] = x match
      case x: tpd.Assign => Some(x)
      case _ => None
  }

  def Assign_lhs(self: Assign)(using ctx: Context): Term = self.lhs
  def Assign_rhs(self: Assign)(using ctx: Context): Term = self.rhs

  def Assign_apply(lhs: Term, rhs: Term)(using ctx: Context): Assign =
    withDefaultPos(tpd.Assign(lhs, rhs))

  def Assign_copy(original: Tree)(lhs: Term, rhs: Term)(using ctx: Context): Assign =
    tpd.cpy.Assign(original)(lhs, rhs)

  type Block = tpd.Block

  def isInstanceOfBlock(using ctx: Context): IsInstanceOf[Block] = new {
    def runtimeClass: Class[?] = classOf[Block]
    override def unapply(x: Any): Option[Block] =
      x match
        case x: tpd.Tree =>
          normalizedLoops(x) match
            case y: tpd.Block => Some(y)
            case _ => None
        case _ => None
  }

  /** Normalizes non Blocks.
   *  i) Put `while` loops in their own blocks: `{ def while$() = ...; while$() }`
   *  ii) Put closures in their own blocks: `{ def anon$() = ...; closure(anon$, ...) }`
   */
  private def normalizedLoops(tree: tpd.Tree)(using ctx: Context): tpd.Tree = tree match {
    case block: tpd.Block if block.stats.size > 1 =>
      def normalizeInnerLoops(stats: List[tpd.Tree]): List[tpd.Tree] = stats match {
        case (x: tpd.DefDef) :: y :: xs if needsNormalization(y) =>
          tpd.Block(x :: Nil, y) :: normalizeInnerLoops(xs)
        case x :: xs => x :: normalizeInnerLoops(xs)
        case Nil => Nil
      }
      if (needsNormalization(block.expr)) {
        val stats1 = normalizeInnerLoops(block.stats.init)
        val normalLoop = tpd.Block(block.stats.last :: Nil, block.expr)
        tpd.Block(stats1, normalLoop)
      }
      else {
        val stats1 = normalizeInnerLoops(block.stats)
        tpd.cpy.Block(block)(stats1, block.expr)
      }
    case _ => tree
  }

  /** If it is the second statement of a closure. See: `normalizedLoops` */
  private def needsNormalization(tree: tpd.Tree)(using ctx: Context): Boolean = tree match {
    case _: tpd.Closure => true
    case _ => false
  }

  def Block_statements(self: Block)(using ctx: Context): List[Statement] = self.stats
  def Block_expr(self: Block)(using ctx: Context): Term = self.expr

  def Block_apply(stats: List[Statement], expr: Term)(using ctx: Context): Block =
    withDefaultPos(tpd.Block(stats, expr))

  def Block_copy(original: Tree)(stats: List[Statement], expr: Term)(using ctx: Context): Block =
    tpd.cpy.Block(original)(stats, expr)

  type Inlined = tpd.Inlined

  def isInstanceOfInlined(using ctx: Context): IsInstanceOf[Inlined] = new {
    def runtimeClass: Class[?] = classOf[Inlined]
    override def unapply(x: Any): Option[Inlined] = x match
      case x: tpd.Inlined => Some(x)
      case _ => None
  }

  def Inlined_call(self: Inlined)(using ctx: Context): Option[Term | TypeTree] = optional(self.call)
  def Inlined_bindings(self: Inlined)(using ctx: Context): List[Definition] = self.bindings
  def Inlined_body(self: Inlined)(using ctx: Context): Term = self.expansion

  def Inlined_apply(call: Option[Term | TypeTree], bindings: List[Definition], expansion: Term)(using ctx: Context): Inlined =
    withDefaultPos(tpd.Inlined(call.getOrElse(tpd.EmptyTree), bindings.map { case b: tpd.MemberDef => b }, expansion))

  def Inlined_copy(original: Tree)(call: Option[Term | TypeTree], bindings: List[Definition], expansion: Term)(using ctx: Context): Inlined =
    tpd.cpy.Inlined(original)(call.getOrElse(tpd.EmptyTree), bindings.asInstanceOf[List[tpd.MemberDef]], expansion)

  type Closure = tpd.Closure

  def isInstanceOfClosure(using ctx: Context): IsInstanceOf[Closure] = new {
    def runtimeClass: Class[?] = classOf[Closure]
    override def unapply(x: Any): Option[Closure] = x match
      case x: tpd.Closure => Some(x)
      case _ => None
  }

  def Closure_meth(self: Closure)(using ctx: Context): Term = self.meth
  def Closure_tpeOpt(self: Closure)(using ctx: Context): Option[Type] = optional(self.tpt).map(_.tpe)

  def Closure_apply(meth: Term, tpe: Option[Type])(using ctx: Context): Closure =
    withDefaultPos(tpd.Closure(Nil, meth, tpe.map(tpd.TypeTree(_)).getOrElse(tpd.EmptyTree)))

  def Closure_copy(original: Tree)(meth: Tree, tpe: Option[Type])(using ctx: Context): Closure =
    tpd.cpy.Closure(original)(Nil, meth, tpe.map(tpd.TypeTree(_)).getOrElse(tpd.EmptyTree))

  def Lambda_apply(tpe: MethodType, rhsFn: List[Tree] => Tree)(using ctx: Context): Block =
    tpd.Lambda(tpe, rhsFn)

  type If = tpd.If

  def isInstanceOfIf(using ctx: Context): IsInstanceOf[If] = new {
    def runtimeClass: Class[?] = classOf[If]
    override def unapply(x: Any): Option[If] = x match
      case x: tpd.If => Some(x)
      case _ => None
  }

  def If_cond(self: If)(using ctx: Context): Term = self.cond
  def If_thenp(self: If)(using ctx: Context): Term = self.thenp
  def If_elsep(self: If)(using ctx: Context): Term = self.elsep

  def If_apply(cond: Term, thenp: Term, elsep: Term)(using ctx: Context): If =
    withDefaultPos(tpd.If(cond, thenp, elsep))

  def If_copy(original: Tree)(cond: Term, thenp: Term, elsep: Term)(using ctx: Context): If =
    tpd.cpy.If(original)(cond, thenp, elsep)

  type Match = tpd.Match

  def isInstanceOfMatch(using ctx: Context): IsInstanceOf[Match] = new {
    def runtimeClass: Class[?] = classOf[Match]
    override def unapply(x: Any): Option[Match] = x match
      case x: tpd.Match if !x.selector.isEmpty => Some(x)
      case _ => None
  }

  def Match_scrutinee(self: Match)(using ctx: Context): Term = self.selector
  def Match_cases(self: Match)(using ctx: Context): List[CaseDef] = self.cases

  def Match_apply(selector: Term, cases: List[CaseDef])(using ctx: Context): Match =
    withDefaultPos(tpd.Match(selector, cases))

  def Match_copy(original: Tree)(selector: Term, cases: List[CaseDef])(using ctx: Context): Match =
    tpd.cpy.Match(original)(selector, cases)

  type GivenMatch = tpd.Match

  def isInstanceOfGivenMatch(using ctx: Context): IsInstanceOf[GivenMatch] = new {
    def runtimeClass: Class[?] = classOf[GivenMatch]
    override def unapply(x: Any): Option[GivenMatch] = x match
      case x: tpd.Match if x.selector.isEmpty => Some(x)
      case _ => None
  }

  def GivenMatch_cases(self: Match)(using ctx: Context): List[CaseDef] = self.cases

  def GivenMatch_apply(cases: List[CaseDef])(using ctx: Context): GivenMatch =
    withDefaultPos(tpd.Match(tpd.EmptyTree, cases))

  def GivenMatch_copy(original: Tree)(cases: List[CaseDef])(using ctx: Context): GivenMatch =
    tpd.cpy.Match(original)(tpd.EmptyTree, cases)

  type Try = tpd.Try

  def isInstanceOfTry(using ctx: Context): IsInstanceOf[Try] = new {
    def runtimeClass: Class[?] = classOf[Try]
    override def unapply(x: Any): Option[Try] = x match
      case x: tpd.Try => Some(x)
      case _ => None
  }

  def Try_body(self: Try)(using ctx: Context): Term = self.expr
  def Try_cases(self: Try)(using ctx: Context): List[CaseDef] = self.cases
  def Try_finalizer(self: Try)(using ctx: Context): Option[Term] = optional(self.finalizer)

  def Try_apply(expr: Term, cases: List[CaseDef], finalizer: Option[Term])(using ctx: Context): Try =
    withDefaultPos(tpd.Try(expr, cases, finalizer.getOrElse(tpd.EmptyTree)))

  def Try_copy(original: Tree)(expr: Term, cases: List[CaseDef], finalizer: Option[Term])(using ctx: Context): Try =
    tpd.cpy.Try(original)(expr, cases, finalizer.getOrElse(tpd.EmptyTree))

  type Return = tpd.Return

  def isInstanceOfReturn(using ctx: Context): IsInstanceOf[Return] = new {
    def runtimeClass: Class[?] = classOf[Return]
    override def unapply(x: Any): Option[Return] = x match
      case x: tpd.Return => Some(x)
      case _ => None
  }

  def Return_expr(self: Return)(using ctx: Context): Term = self.expr

  def Return_apply(expr: Term)(using ctx: Context): Return =
    withDefaultPos(tpd.Return(expr, ctx.owner))

  def Return_copy(original: Tree)(expr: Term)(using ctx: Context): Return =
    tpd.cpy.Return(original)(expr, tpd.ref(ctx.owner))

  type Repeated = tpd.SeqLiteral

  def isInstanceOfRepeated(using ctx: Context): IsInstanceOf[Repeated] = new {
    def runtimeClass: Class[?] = classOf[Repeated]
    override def unapply(x: Any): Option[Repeated] = x match
      case x: tpd.SeqLiteral => Some(x)
      case _ => None
  }

  def Repeated_elems(self: Repeated)(using ctx: Context): List[Term] = self.elems
  def Repeated_elemtpt(self: Repeated)(using ctx: Context): TypeTree = self.elemtpt

  def Repeated_apply(elems: List[Term], elemtpt: TypeTree)(using ctx: Context): Repeated =
    withDefaultPos(tpd.SeqLiteral(elems, elemtpt))

  def Repeated_copy(original: Tree)(elems: List[Term], elemtpt: TypeTree)(using ctx: Context): Repeated =
    tpd.cpy.SeqLiteral(original)(elems, elemtpt)

  type SelectOuter = tpd.Select

  def isInstanceOfSelectOuter(using ctx: Context): IsInstanceOf[SelectOuter] = new {
    def runtimeClass: Class[?] = classOf[SelectOuter]
    override def unapply(x: Any): Option[SelectOuter] = x match
    case x: tpd.Select =>
      x.name match
        case NameKinds.OuterSelectName(_, _) => Some(x)
        case _ => None
    case _ => None
  }

  def SelectOuter_qualifier(self: SelectOuter)(using ctx: Context): Term = self.qualifier
  def SelectOuter_level(self: SelectOuter)(using ctx: Context): Int = {
    val NameKinds.OuterSelectName(_, levels) = self.name
    levels
  }

  def SelectOuter_apply(qualifier: Term, name: String, levels: Int)(using ctx: Context): SelectOuter =
    withDefaultPos(tpd.Select(qualifier, NameKinds.OuterSelectName(name.toTermName, levels)))

  def SelectOuter_copy(original: Tree)(qualifier: Term, name: String, levels: Int)(using ctx: Context): SelectOuter =
    tpd.cpy.Select(original)(qualifier, NameKinds.OuterSelectName(name.toTermName, levels))

  type While = tpd.WhileDo

  def isInstanceOfWhile(using ctx: Context): IsInstanceOf[While] = new {
    def runtimeClass: Class[?] = classOf[While]
    override def unapply(x: Any): Option[While] = x match
      case x: tpd.WhileDo => Some(x)
      case _ => None
  }

  def While_cond(self: While)(using ctx: Context): Term = self.cond
  def While_body(self: While)(using ctx: Context): Term = self.body

  def While_apply(cond: Term, body: Term)(using ctx: Context): While =
    withDefaultPos(tpd.WhileDo(cond, body))

  def While_copy(original: Tree)(cond: Term, body: Term)(using ctx: Context): While =
    tpd.cpy.WhileDo(original)(cond, body)

  type TypeTree = tpd.Tree

  def isInstanceOfTypeTree(using ctx: Context): IsInstanceOf[TypeTree] = new {
    def runtimeClass: Class[?] = classOf[TypeTree]
    override def unapply(x: Any): Option[TypeTree] = x match
      case x: tpd.TypeBoundsTree => None
      case x: tpd.Tree if x.isType => Some(x)
      case _ => None
  }

  def TypeTree_tpe(self: TypeTree)(using ctx: Context): Type = self.tpe.stripTypeVar

  type Inferred = tpd.TypeTree

  def isInstanceOfInferred(using ctx: Context): IsInstanceOf[Inferred] = new {
    def runtimeClass: Class[?] = classOf[Inferred]
    override def unapply(x: Any): Option[Inferred] = x match
      case tpt: tpd.TypeTree if !tpt.tpe.isInstanceOf[Types.TypeBounds] => Some(tpt)
      case _ => None
  }

  def Inferred_apply(tpe: Type)(using ctx: Context): Inferred = withDefaultPos(tpd.TypeTree(tpe))

  type TypeIdent = tpd.Ident

  def isInstanceOfTypeIdent(using ctx: Context): IsInstanceOf[TypeIdent] = new {
    def runtimeClass: Class[?] = classOf[TypeIdent]
    override def unapply(x: Any): Option[TypeIdent] = x match
      case tpt: tpd.Ident if tpt.isType => Some(tpt)
      case _ => None
  }

  def TypeIdent_name(self: TypeIdent)(using ctx: Context): String = self.name.toString

  def TypeIdent_copy(original: Tree)(name: String)(using ctx: Context): TypeIdent =
    tpd.cpy.Ident(original)(name.toTypeName)

  type TypeSelect = tpd.Select

  def isInstanceOfTypeSelect(using ctx: Context): IsInstanceOf[TypeSelect] = new {
    def runtimeClass: Class[?] = classOf[TypeSelect]
    override def unapply(x: Any): Option[TypeSelect] = x match
      case tpt: tpd.Select if tpt.isType && tpt.qualifier.isTerm  => Some(tpt)
      case _ => None
  }

  def TypeSelect_qualifier(self: TypeSelect)(using ctx: Context): Term = self.qualifier
  def TypeSelect_name(self: TypeSelect)(using ctx: Context): String = self.name.toString

  def TypeSelect_apply(qualifier: Term, name: String)(using ctx: Context): TypeSelect =
    withDefaultPos(tpd.Select(qualifier, name.toTypeName))

  def TypeSelect_copy(original: Tree)(qualifier: Term, name: String)(using ctx: Context): TypeSelect =
    tpd.cpy.Select(original)(qualifier, name.toTypeName)


  type Projection = tpd.Select

  def isInstanceOfProjection(using ctx: Context): IsInstanceOf[Projection] = new {
    def runtimeClass: Class[?] = classOf[Projection]
    override def unapply(x: Any): Option[Projection] = x match
      case tpt: tpd.Select if tpt.isType && tpt.qualifier.isType => Some(tpt)
      case _ => None
  }

  def Projection_qualifier(self: Projection)(using ctx: Context): TypeTree = self.qualifier
  def Projection_name(self: Projection)(using ctx: Context): String = self.name.toString

  def Projection_copy(original: Tree)(qualifier: TypeTree, name: String)(using ctx: Context): Projection =
    tpd.cpy.Select(original)(qualifier, name.toTypeName)

  type Singleton = tpd.SingletonTypeTree

  def isInstanceOfSingleton(using ctx: Context): IsInstanceOf[Singleton] = new {
    def runtimeClass: Class[?] = classOf[Singleton]
    override def unapply(x: Any): Option[Singleton] = x match
      case tpt: tpd.SingletonTypeTree => Some(tpt)
      case _ => None
  }

  def Singleton_ref(self: Singleton)(using ctx: Context): Term = self.ref

  def Singleton_apply(ref: Term)(using ctx: Context): Singleton =
    withDefaultPos(tpd.SingletonTypeTree(ref))

  def Singleton_copy(original: Tree)(ref: Term)(using ctx: Context): Singleton =
    tpd.cpy.SingletonTypeTree(original)(ref)

  type Refined = tpd.RefinedTypeTree

  def isInstanceOfRefined(using ctx: Context): IsInstanceOf[Refined] = new {
    def runtimeClass: Class[?] = classOf[Refined]
    override def unapply(x: Any): Option[Refined] = x match
      case tpt: tpd.RefinedTypeTree => Some(tpt)
      case _ => None
  }

  def Refined_tpt(self: Refined)(using ctx: Context): TypeTree = self.tpt
  def Refined_refinements(self: Refined)(using ctx: Context): List[Definition] = self.refinements

  def Refined_copy(original: Tree)(tpt: TypeTree, refinements: List[Definition])(using ctx: Context): Refined =
    tpd.cpy.RefinedTypeTree(original)(tpt, refinements)

  type Applied = tpd.AppliedTypeTree

  def isInstanceOfApplied(using ctx: Context): IsInstanceOf[Applied] = new {
    def runtimeClass: Class[?] = classOf[Applied]
    override def unapply(x: Any): Option[Applied] = x match
      case tpt: tpd.AppliedTypeTree => Some(tpt)
      case _ => None
  }

  def Applied_tpt(self: Applied)(using ctx: Context): TypeTree = self.tpt
  def Applied_args(self: Applied)(using ctx: Context): List[TypeTree | TypeBoundsTree] = self.args

  def Applied_apply(tpt: TypeTree, args: List[TypeTree | TypeBoundsTree])(using ctx: Context): Applied =
    withDefaultPos(tpd.AppliedTypeTree(tpt, args))

  def Applied_copy(original: Tree)(tpt: TypeTree, args: List[TypeTree | TypeBoundsTree])(using ctx: Context): Applied =
    tpd.cpy.AppliedTypeTree(original)(tpt, args)

  type Annotated = tpd.Annotated

  def isInstanceOfAnnotated(using ctx: Context): IsInstanceOf[Annotated] = new {
    def runtimeClass: Class[?] = classOf[Annotated]
    override def unapply(x: Any): Option[Annotated] = x match
      case tpt: tpd.Annotated => Some(tpt)
      case _ => None
  }

  def Annotated_arg(self: Annotated)(using ctx: Context): TypeTree = self.arg
  def Annotated_annotation(self: Annotated)(using ctx: Context): Term = self.annot

  def Annotated_apply(arg: TypeTree, annotation: Term)(using ctx: Context): Annotated =
    withDefaultPos(tpd.Annotated(arg, annotation))

  def Annotated_copy(original: Tree)(arg: TypeTree, annotation: Term)(using ctx: Context): Annotated =
    tpd.cpy.Annotated(original)(arg, annotation)

  type MatchTypeTree = tpd.MatchTypeTree

  def isInstanceOfMatchTypeTree(using ctx: Context): IsInstanceOf[MatchTypeTree] = new {
    def runtimeClass: Class[?] = classOf[MatchTypeTree]
    override def unapply(x: Any): Option[MatchTypeTree] = x match
      case tpt: tpd.MatchTypeTree => Some(tpt)
      case _ => None
  }

  def MatchTypeTree_bound(self: MatchTypeTree)(using ctx: Context): Option[TypeTree] = if (self.bound == tpd.EmptyTree) None else Some(self.bound)
  def MatchTypeTree_selector(self: MatchTypeTree)(using ctx: Context): TypeTree = self.selector
  def MatchTypeTree_cases(self: MatchTypeTree)(using ctx: Context): List[CaseDef] = self.cases

  def MatchTypeTree_apply(bound: Option[TypeTree], selector: TypeTree, cases: List[TypeCaseDef])(using ctx: Context): MatchTypeTree =
    withDefaultPos(tpd.MatchTypeTree(bound.getOrElse(tpd.EmptyTree), selector, cases))

  def MatchTypeTree_copy(original: Tree)(bound: Option[TypeTree], selector: TypeTree, cases: List[TypeCaseDef])(using ctx: Context): MatchTypeTree =
    tpd.cpy.MatchTypeTree(original)(bound.getOrElse(tpd.EmptyTree), selector, cases)

  type ByName = tpd.ByNameTypeTree

  def isInstanceOfByName(using ctx: Context): IsInstanceOf[ByName] = new {
    def runtimeClass: Class[?] = classOf[ByName]
    override def unapply(x: Any): Option[ByName] = x match
      case tpt: tpd.ByNameTypeTree => Some(tpt)
      case _ => None
  }

  def ByName_result(self: ByName)(using ctx: Context): TypeTree = self.result

  def ByName_apply(result: TypeTree)(using ctx: Context): ByName =
    withDefaultPos(tpd.ByNameTypeTree(result))

  def ByName_copy(original: Tree)(result: TypeTree)(using ctx: Context): ByName =
    tpd.cpy.ByNameTypeTree(original)(result)

  type LambdaTypeTree = tpd.LambdaTypeTree

  def isInstanceOfLambdaTypeTree(using ctx: Context): IsInstanceOf[LambdaTypeTree] = new {
    def runtimeClass: Class[?] = classOf[LambdaTypeTree]
    override def unapply(x: Any): Option[LambdaTypeTree] = x match
      case tpt: tpd.LambdaTypeTree => Some(tpt)
      case _ => None
  }

  def Lambdatparams(self: LambdaTypeTree)(using ctx: Context): List[TypeDef] = self.tparams
  def Lambdabody(self: LambdaTypeTree)(using ctx: Context): TypeTree | TypeBoundsTree = self.body

  def Lambdaapply(tparams: List[TypeDef], body: TypeTree | TypeBoundsTree)(using ctx: Context): LambdaTypeTree =
    withDefaultPos(tpd.LambdaTypeTree(tparams, body))

  def Lambdacopy(original: Tree)(tparams: List[TypeDef], body: TypeTree | TypeBoundsTree)(using ctx: Context): LambdaTypeTree =
    tpd.cpy.LambdaTypeTree(original)(tparams, body)

  type TypeBind = tpd.Bind

  def isInstanceOfTypeBind(using ctx: Context): IsInstanceOf[TypeBind] = new {
    def runtimeClass: Class[?] = classOf[TypeBind]
    override def unapply(x: Any): Option[TypeBind] = x match
      case tpt: tpd.Bind if tpt.name.isTypeName => Some(tpt)
      case _ => None
  }

  def TypeBind_name(self: TypeBind)(using ctx: Context): String = self.name.toString
  def TypeBind_body(self: TypeBind)(using ctx: Context): TypeTree | TypeBoundsTree = self.body

  def TypeBind_copy(original: Tree)(name: String, tpt: TypeTree | TypeBoundsTree)(using ctx: Context): TypeBind =
    tpd.cpy.Bind(original)(name.toTypeName, tpt)

  type TypeBlock = tpd.Block

  def isInstanceOfTypeBlock(using ctx: Context): IsInstanceOf[TypeBlock] = new {
    def runtimeClass: Class[?] = classOf[TypeBlock]
    override def unapply(x: Any): Option[TypeBlock] = x match
      case tpt: tpd.Block => Some(tpt)
      case _ => None
  }

  def TypeBlock_aliases(self: TypeBlock)(using ctx: Context): List[TypeDef] = self.stats.map { case alias: TypeDef => alias }
  def TypeBlock_tpt(self: TypeBlock)(using ctx: Context): TypeTree = self.expr

  def TypeBlock_apply(aliases: List[TypeDef], tpt: TypeTree)(using ctx: Context): TypeBlock =
    withDefaultPos(tpd.Block(aliases, tpt))

  def TypeBlock_copy(original: Tree)(aliases: List[TypeDef], tpt: TypeTree)(using ctx: Context): TypeBlock =
    tpd.cpy.Block(original)(aliases, tpt)

  type TypeBoundsTree = tpd.TypeBoundsTree

  def isInstanceOfTypeBoundsTree(using ctx: Context): IsInstanceOf[TypeBoundsTree] = new {
    def runtimeClass: Class[?] = classOf[TypeBoundsTree]
    override def unapply(x: Any): Option[TypeBoundsTree] = x match
      case x: tpd.TypeBoundsTree => Some(x)
      case x @ tpd.TypeTree() =>
        // TODO only enums generate this kind of type bounds. Is this possible without enums? If not generate tpd.TypeBoundsTree for enums instead
        (x.tpe: Any) match {
          case tpe: Types.TypeBounds =>
            Some(tpd.TypeBoundsTree(tpd.TypeTree(tpe.lo).withSpan(x.span), tpd.TypeTree(tpe.hi).withSpan(x.span)))
          case _ => None
        }
      case _ => None
  }

  def TypeBoundsTree_tpe(self: TypeBoundsTree)(using ctx: Context): TypeBounds = self.tpe.asInstanceOf[Types.TypeBounds]
  def TypeBoundsTree_low(self: TypeBoundsTree)(using ctx: Context): TypeTree = self.lo
  def TypeBoundsTree_hi(self: TypeBoundsTree)(using ctx: Context): TypeTree = self.hi

  type WildcardTypeTree = tpd.Ident

  def isInstanceOfWildcardTypeTree(using ctx: Context): IsInstanceOf[WildcardTypeTree] = new {
    def runtimeClass: Class[?] = classOf[WildcardTypeTree]
    override def unapply(x: Any): Option[WildcardTypeTree] = x match
      case x: tpd.Ident if x.name == nme.WILDCARD => Some(x)
      case _ => None
  }

  def WildcardTypeTree_tpe(self: WildcardTypeTree)(using ctx: Context): TypeOrBounds = self.tpe.stripTypeVar

  type CaseDef = tpd.CaseDef

  def isInstanceOfCaseDef(using ctx: Context): IsInstanceOf[CaseDef] = new {
    def runtimeClass: Class[?] = classOf[CaseDef]
    override def unapply(x: Any): Option[CaseDef] = x match
      case tree: tpd.CaseDef if tree.body.isTerm => Some(tree)
      case _ => None
  }

  def CaseDef_pattern(self: CaseDef)(using ctx: Context): Tree = self.pat
  def CaseDef_guard(self: CaseDef)(using ctx: Context): Option[Term] = optional(self.guard)
  def CaseDef_rhs(self: CaseDef)(using ctx: Context): Term = self.body

  def CaseDef_module_apply(pattern: Tree, guard: Option[Term], body: Term)(using ctx: Context): CaseDef =
    tpd.CaseDef(pattern, guard.getOrElse(tpd.EmptyTree), body)

  def CaseDef_module_copy(original: Tree)(pattern: Tree, guard: Option[Term], body: Term)(using ctx: Context): CaseDef =
    tpd.cpy.CaseDef(original)(pattern, guard.getOrElse(tpd.EmptyTree), body)

  type TypeCaseDef = tpd.CaseDef

  def isInstanceOfTypeCaseDef(using ctx: Context): IsInstanceOf[TypeCaseDef] = new {
    def runtimeClass: Class[?] = classOf[TypeCaseDef]
    override def unapply(x: Any): Option[TypeCaseDef] = x match
      case tree: tpd.CaseDef if tree.body.isType => Some(tree)
      case _ => None
  }

  def TypeCaseDef_pattern(self: TypeCaseDef)(using ctx: Context): TypeTree = self.pat
  def TypeCaseDef_rhs(self: TypeCaseDef)(using ctx: Context): TypeTree = self.body

  def TypeCaseDef_module_apply(pattern: TypeTree, body: TypeTree)(using ctx: Context): TypeCaseDef =
    tpd.CaseDef(pattern, tpd.EmptyTree, body)

  def TypeCaseDef_module_copy(original: Tree)(pattern: TypeTree, body: TypeTree)(using ctx: Context): TypeCaseDef =
    tpd.cpy.CaseDef(original)(pattern, tpd.EmptyTree, body)

  type Bind = tpd.Bind

  def isInstanceOfBind(using ctx: Context): IsInstanceOf[Bind] = new {
    def runtimeClass: Class[?] = classOf[Bind]
    override def unapply(x: Any): Option[Bind] = x match
      case x: tpd.Bind if x.name.isTermName => Some(x)
      case _ => None
  }

  def Tree_Bind_name(self: Bind)(using ctx: Context): String = self.name.toString

  def Tree_Bind_pattern(self: Bind)(using ctx: Context): Tree = self.body

  def Tree_Bind_module_copy(original: Tree)(name: String, pattern: Tree)(using ctx: Context): Bind =
    withDefaultPos(tpd.cpy.Bind(original)(name.toTermName, pattern))

  type Unapply = tpd.UnApply

  def isInstanceOfUnapply(using ctx: Context): IsInstanceOf[Unapply] = new {
    def runtimeClass: Class[?] = classOf[Unapply]
    override def unapply(x: Any): Option[Unapply] = x match
      case pattern: tpd.UnApply => Some(pattern)
      case Trees.Typed(pattern: tpd.UnApply, _) => Some(pattern)
      case _ => None
  }

  def Tree_Unapply_fun(self: Unapply)(using ctx: Context): Term = self.fun
  def Tree_Unapply_implicits(self: Unapply)(using ctx: Context): List[Term] = self.implicits
  def Tree_Unapply_patterns(self: Unapply)(using ctx: Context): List[Tree] = effectivePatterns(self.patterns)

  def Tree_Unapply_module_copy(original: Tree)(fun: Term, implicits: List[Term], patterns: List[Tree])(using ctx: Context): Unapply =
    withDefaultPos(tpd.cpy.UnApply(original)(fun, implicits, patterns))

  private def effectivePatterns(patterns: List[Tree]): List[Tree] = patterns match {
    case patterns0 :+ Trees.SeqLiteral(elems, _) => patterns0 ::: elems
    case _ => patterns
  }

  type Alternatives = tpd.Alternative

  def isInstanceOfAlternatives(using ctx: Context): IsInstanceOf[Alternatives] = new {
    def runtimeClass: Class[?] = classOf[Alternatives]
    override def unapply(x: Any): Option[Alternatives] = x match
      case x: tpd.Alternative => Some(x)
      case _ => None
  }

  def Tree_Alternatives_patterns(self: Alternatives)(using ctx: Context): List[Tree] = self.trees

  def Tree_Alternatives_module_apply(patterns: List[Tree])(using ctx: Context): Alternatives =
    withDefaultPos(tpd.Alternative(patterns))

  def Tree_Alternatives_module_copy(original: Tree)(patterns: List[Tree])(using ctx: Context): Alternatives =
    tpd.cpy.Alternative(original)(patterns)


  /////////////
  //  TYPES  //
  /////////////

  type TypeOrBounds = Types.Type

  type NoPrefix = Types.NoPrefix.type

  def isInstanceOfNoPrefix(using ctx: Context): IsInstanceOf[NoPrefix] = new {
    def runtimeClass: Class[?] = classOf[Types.NoPrefix.type]
    override def unapply(x: Any): Option[NoPrefix] =
      if (x == Types.NoPrefix) Some(Types.NoPrefix) else None
  }

  type TypeBounds = Types.TypeBounds

  def isInstanceOfTypeBounds(using ctx: Context): IsInstanceOf[TypeBounds] = new {
    def runtimeClass: Class[?] = classOf[TypeBounds]
    override def unapply(x: Any): Option[TypeBounds] = x match
      case x: Types.TypeBounds => Some(x)
      case _ => None
  }

  def TypeBounds_apply(low: Type, hi: Type)(using ctx: Context): TypeBounds =
    Types.TypeBounds(low, hi)

  def TypeBounds_low(self: TypeBounds)(using ctx: Context): Type = self.lo
  def TypeBounds_hi(self: TypeBounds)(using ctx: Context): Type = self.hi

  type Type = Types.Type

  def isInstanceOfType(using ctx: Context): IsInstanceOf[Type] = new {
    def runtimeClass: Class[?] = classOf[Type]
    override def unapply(x: Any): Option[Type] = x match
      case x: TypeBounds => None
      case x: Types.Type if x != Types.NoPrefix => Some(x)
      case _ => None
  }

  def Type_apply(clazz: Class[?])(using ctx: Context): Type =
    if (clazz.isPrimitive)
      if (clazz == classOf[Boolean]) defn.BooleanType
      else if (clazz == classOf[Byte]) defn.ByteType
      else if (clazz == classOf[Char]) defn.CharType
      else if (clazz == classOf[Short]) defn.ShortType
      else if (clazz == classOf[Int]) defn.IntType
      else if (clazz == classOf[Long]) defn.LongType
      else if (clazz == classOf[Float]) defn.FloatType
      else if (clazz == classOf[Double]) defn.DoubleType
      else defn.UnitType
    else if (clazz.isArray)
      defn.ArrayType.appliedTo(Type_apply(clazz.getComponentType))
    else if (clazz.isMemberClass) {
      val name = clazz.getSimpleName.toTypeName
      val enclosing = Type_apply(clazz.getEnclosingClass)
      if (enclosing.member(name).exists) enclosing.select(name)
      else
        enclosing.classSymbol.companionModule.termRef.select(name)
    }
    else ctx.getClassIfDefined(clazz.getCanonicalName).typeRef

  def Type_isTypeEq(self: Type)(that: Type)(using ctx: Context): Boolean = self =:= that

  def Type_isSubType(self: Type)(that: Type)(using ctx: Context): Boolean = self <:< that

  def Type_widen(self: Type)(using ctx: Context): Type = self.widen

  def Type_widenTermRefExpr(self: Type)(using ctx: Context): Type = self.widenTermRefExpr

  def Type_dealias(self: Type)(using ctx: Context): Type = self.dealias

  def Type_simplified(self: Type)(using ctx: Context): Type = self.simplified

  def Type_classSymbol(self: Type)(using ctx: Context): Option[Symbol] =
    if (self.classSymbol.exists) Some(self.classSymbol.asClass) else None

  def Type_typeSymbol(self: Type)(using ctx: Context): Symbol = self.typeSymbol

  def Type_termSymbol(self: Type)(using ctx: Context): Symbol = self.termSymbol

  def Type_isSingleton(self: Type)(using ctx: Context): Boolean = self.isSingleton

  def Type_memberType(self: Type)(member: Symbol)(using ctx: Context): Type =
    member.info.asSeenFrom(self, member.owner)

  def Type_derivesFrom(self: Type)(cls: Symbol)(using ctx: Context): Boolean =
    self.derivesFrom(cls)

  def Type_isFunctionType(self: Type)(using ctx: Context): Boolean =
    defn.isFunctionType(self)

  def Type_isContextFunctionType(self: Type)(using ctx: Context): Boolean =
    defn.isContextFunctionType(self)

  def Type_isErasedFunctionType(self: Type)(using ctx: Context): Boolean =
    defn.isErasedFunctionType(self)

  def Type_isDependentFunctionType(self: Type)(using ctx: Context): Boolean = {
    val tpNoRefinement = self.dropDependentRefinement
    tpNoRefinement != self && defn.isNonRefinedFunction(tpNoRefinement)
  }

  type ConstantType = Types.ConstantType

  def isInstanceOfConstantType(using ctx: Context): IsInstanceOf[ConstantType] = new {
    def runtimeClass: Class[?] = classOf[ConstantType]
    override def unapply(x: Any): Option[ConstantType] = x match
      case tpe: Types.ConstantType => Some(tpe)
      case _ => None
  }

  def ConstantType_apply(const: Constant)(using ctx: Context): ConstantType =
    Types.ConstantType(const)

  def ConstantType_constant(self: ConstantType)(using ctx: Context): Constant = self.value

  type TermRef = Types.NamedType

  def isInstanceOfTermRef(using ctx: Context): IsInstanceOf[TermRef] = new {
    def runtimeClass: Class[?] = classOf[TermRef]
    override def unapply(x: Any): Option[TermRef] = x match
      case tp: Types.TermRef => Some(tp)
      case _ => None
  }

  def TermRef_apply(qual: TypeOrBounds, name: String)(using ctx: Context): TermRef =
    Types.TermRef(qual, name.toTermName)

  def TermRef_qualifier(self: TermRef)(using ctx: Context): TypeOrBounds = self.prefix

  def TermRef_name(self: TermRef)(using ctx: Context): String = self.name.toString

  type TypeRef = Types.NamedType

  def isInstanceOfTypeRef(using ctx: Context): IsInstanceOf[TypeRef] = new {
    def runtimeClass: Class[?] = classOf[TypeRef]
    override def unapply(x: Any): Option[TypeRef] = x match
      case tp: Types.TypeRef => Some(tp)
      case _ => None
  }

  def TypeRef_qualifier(self: TypeRef)(using ctx: Context): TypeOrBounds = self.prefix

  def TypeRef_name(self: TypeRef)(using ctx: Context): String = self.name.toString

  def TypeRef_isOpaqueAlias(self: TypeRef)(using ctx: Context): Boolean = self.symbol.isOpaqueAlias

  def TypeRef_translucentSuperType(self: TypeRef)(using ctx: Context): Type = self.translucentSuperType

  type NamedTermRef = Types.NamedType

  def isInstanceOfNamedTermRef(using ctx: Context): IsInstanceOf[NamedTermRef] = new {
    def runtimeClass: Class[?] = classOf[NamedTermRef]
    override def unapply(x: Any): Option[NamedTermRef] = x match
      case tpe: Types.NamedType =>
        tpe.designator match {
          case name: Names.TermName => Some(tpe)
          case _ => None
        }
      case _ => None
  }

  def NamedTermRef_name(self: NamedTermRef)(using ctx: Context): String = self.name.toString
  def NamedTermRef_qualifier(self: NamedTermRef)(using ctx: Context): TypeOrBounds = self.prefix

  type SuperType = Types.SuperType

  def isInstanceOfSuperType(using ctx: Context): IsInstanceOf[SuperType] = new {
    def runtimeClass: Class[?] = classOf[SuperType]
    override def unapply(x: Any): Option[SuperType] = x match
      case tpe: Types.SuperType => Some(tpe)
      case _ => None
  }

  def SuperType_apply(thistpe: Type, supertpe: Type)(using ctx: Context): SuperType =
    Types.SuperType(thistpe, supertpe)

  def SuperType_thistpe(self: SuperType)(using ctx: Context): Type = self.thistpe
  def SuperType_supertpe(self: SuperType)(using ctx: Context): Type = self.supertpe

  type Refinement = Types.RefinedType

  def isInstanceOfRefinement(using ctx: Context): IsInstanceOf[Refinement] = new {
    def runtimeClass: Class[?] = classOf[Refinement]
    override def unapply(x: Any): Option[Refinement] = x match
      case tpe: Types.RefinedType => Some(tpe)
      case _ => None
  }

  def Refinement_apply(parent: Type, name: String, info: TypeOrBounds /* Type | TypeBounds */)(using ctx: Context): Refinement = {
    val name1 =
      info match
        case _: TypeBounds => name.toTypeName
        case _ => name.toTermName
    Types.RefinedType(parent, name1, info)
  }

  def Refinement_parent(self: Refinement)(using ctx: Context): Type = self.parent
  def Refinement_name(self: Refinement)(using ctx: Context): String = self.refinedName.toString
  def Refinement_info(self: Refinement)(using ctx: Context): TypeOrBounds = self.refinedInfo

  type AppliedType = Types.AppliedType

  def isInstanceOfAppliedType(using ctx: Context): IsInstanceOf[AppliedType] = new {
    def runtimeClass: Class[?] = classOf[AppliedType]
    override def unapply(x: Any): Option[AppliedType] = x match
      case tpe: Types.AppliedType => Some(tpe)
      case _ => None
  }

  def AppliedType_tycon(self: AppliedType)(using ctx: Context): Type = self.tycon
  def AppliedType_args(self: AppliedType)(using ctx: Context): List[TypeOrBounds] = self.args

  def AppliedType_apply(tycon: Type, args: List[TypeOrBounds])(using ctx: Context): AppliedType = Types.AppliedType(tycon, args)

  type AnnotatedType = Types.AnnotatedType

  def isInstanceOfAnnotatedType(using ctx: Context): IsInstanceOf[AnnotatedType] = new {
    def runtimeClass: Class[?] = classOf[AnnotatedType]
    override def unapply(x: Any): Option[AnnotatedType] = x match
      case tpe: Types.AnnotatedType => Some(tpe)
      case _ => None
  }

  def AnnotatedType_apply(underlying: Type, annot: Term)(using ctx: Context): AnnotatedType =
    Types.AnnotatedType(underlying, Annotations.Annotation(annot))

  def AnnotatedType_underlying(self: AnnotatedType)(using ctx: Context): Type = self.underlying.stripTypeVar
  def AnnotatedType_annot(self: AnnotatedType)(using ctx: Context): Term = self.annot.tree

  type AndType = Types.AndType

  def isInstanceOfAndType(using ctx: Context): IsInstanceOf[AndType] = new {
    def runtimeClass: Class[?] = classOf[AndType]
    override def unapply(x: Any): Option[AndType] = x match
      case tpe: Types.AndType => Some(tpe)
      case _ => None
  }

  def AndType_apply(lhs: Type, rhs: Type)(using ctx: Context): AndType =
    Types.AndType(lhs, rhs)

  def AndType_left(self: AndType)(using ctx: Context): Type = self.tp1.stripTypeVar
  def AndType_right(self: AndType)(using ctx: Context): Type = self.tp2.stripTypeVar

  type OrType = Types.OrType

  def isInstanceOfOrType(using ctx: Context): IsInstanceOf[OrType] = new {
    def runtimeClass: Class[?] = classOf[OrType]
    override def unapply(x: Any): Option[OrType] = x match
      case tpe: Types.OrType => Some(tpe)
      case _ => None
  }

  def OrType_apply(lhs: Type, rhs: Type)(using ctx: Context): OrType =
    Types.OrType(lhs, rhs)

  def OrType_left(self: OrType)(using ctx: Context): Type = self.tp1.stripTypeVar
  def OrType_right(self: OrType)(using ctx: Context): Type = self.tp2.stripTypeVar

  type MatchType = Types.MatchType

  def isInstanceOfMatchType(using ctx: Context): IsInstanceOf[MatchType] = new {
    def runtimeClass: Class[?] = classOf[MatchType]
    override def unapply(x: Any): Option[MatchType] = x match
      case tpe: Types.MatchType => Some(tpe)
      case _ => None
  }

  def MatchType_apply(bound: Type, scrutinee: Type, cases: List[Type])(using ctx: Context): MatchType =
    Types.MatchType(bound, scrutinee, cases)

  def MatchType_bound(self: MatchType)(using ctx: Context): Type = self.bound
  def MatchType_scrutinee(self: MatchType)(using ctx: Context): Type = self.scrutinee
  def MatchType_cases(self: MatchType)(using ctx: Context): List[Type] = self.cases

  type ByNameType = Types.ExprType

  def isInstanceOfByNameType(using ctx: Context): IsInstanceOf[ByNameType] = new {
    def runtimeClass: Class[?] = classOf[ByNameType]
    override def unapply(x: Any): Option[ByNameType] = x match
      case tpe: Types.ExprType => Some(tpe)
      case _ => None
  }

  def ByNameType_apply(underlying: Type)(using ctx: Context): Type = Types.ExprType(underlying)

  def ByNameType_underlying(self: ByNameType)(using ctx: Context): Type = self.resType.stripTypeVar

  type ParamRef = Types.ParamRef

  def isInstanceOfParamRef(using ctx: Context): IsInstanceOf[ParamRef] = new {
    def runtimeClass: Class[?] = classOf[ParamRef]
    override def unapply(x: Any): Option[ParamRef] = x match
      case tpe: Types.TypeParamRef => Some(tpe)
      case tpe: Types.TermParamRef => Some(tpe)
      case _ => None
  }

  def ParamRef_binder(self: ParamRef)(using ctx: Context): LambdaType[TypeOrBounds] =
    self.binder.asInstanceOf[LambdaType[TypeOrBounds]] // Cast to tpd
  def ParamRef_paramNum(self: ParamRef)(using ctx: Context): Int = self.paramNum

  type ThisType = Types.ThisType

  def isInstanceOfThisType(using ctx: Context): IsInstanceOf[ThisType] = new {
    def runtimeClass: Class[?] = classOf[ThisType]
    override def unapply(x: Any): Option[ThisType] = x match
      case tpe: Types.ThisType => Some(tpe)
      case _ => None
  }

  def ThisType_tref(self: ThisType)(using ctx: Context): Type = self.tref

  type RecursiveThis = Types.RecThis

  def isInstanceOfRecursiveThis(using ctx: Context): IsInstanceOf[RecursiveThis] = new {
    def runtimeClass: Class[?] = classOf[RecursiveThis]
    override def unapply(x: Any): Option[RecursiveThis] = x match
      case tpe: Types.RecThis => Some(tpe)
      case _ => None
  }

  def RecursiveThis_binder(self: RecursiveThis)(using ctx: Context): RecursiveType = self.binder

  type RecursiveType = Types.RecType

  def isInstanceOfRecursiveType(using ctx: Context): IsInstanceOf[RecursiveType] = new {
    def runtimeClass: Class[?] = classOf[RecursiveType]
    override def unapply(x: Any): Option[RecursiveType] = x match
      case tpe: Types.RecType => Some(tpe)
      case _ => None
  }

  def RecursiveType_apply(parentExp: RecursiveType => Type)(using ctx: Context): RecursiveType =
    Types.RecType(parentExp)

  def RecursiveType_underlying(self: RecursiveType)(using ctx: Context): Type = self.underlying.stripTypeVar

  def RecursiveThis_recThis(self: RecursiveType)(using ctx: Context): RecursiveThis = self.recThis

  type LambdaType[ParamInfo] = Types.LambdaType { type PInfo = ParamInfo }

  type MethodType = Types.MethodType

  def isInstanceOfMethodType(using ctx: Context): IsInstanceOf[MethodType] = new {
    def runtimeClass: Class[?] = classOf[MethodType]
    override def unapply(x: Any): Option[MethodType] = x match
      case tpe: Types.MethodType => Some(tpe)
      case _ => None
  }

  def MethodType_apply(paramNames: List[String])(paramInfosExp: MethodType => List[Type], resultTypeExp: MethodType => Type): MethodType =
    Types.MethodType(paramNames.map(_.toTermName))(paramInfosExp, resultTypeExp)

  def MethodType_isErased(self: MethodType): Boolean = self.isErasedMethod
  def MethodType_isImplicit(self: MethodType): Boolean = self.isImplicitMethod
  def MethodType_param(self: MethodType, idx: Int)(using ctx: Context): Type = self.newParamRef(idx)
  def MethodType_paramNames(self: MethodType)(using ctx: Context): List[String] = self.paramNames.map(_.toString)
  def MethodType_paramTypes(self: MethodType)(using ctx: Context): List[Type] = self.paramInfos
  def MethodType_resType(self: MethodType)(using ctx: Context): Type = self.resType

  type PolyType = Types.PolyType

  def isInstanceOfPolyType(using ctx: Context): IsInstanceOf[PolyType] = new {
    def runtimeClass: Class[?] = classOf[PolyType]
    override def unapply(x: Any): Option[PolyType] = x match
      case tpe: Types.PolyType => Some(tpe)
      case _ => None
  }

  def PolyType_apply(paramNames: List[String])(paramBoundsExp: PolyType => List[TypeBounds], resultTypeExp: PolyType => Type)(using ctx: Context): PolyType =
    Types.PolyType(paramNames.map(_.toTypeName))(paramBoundsExp, resultTypeExp)

  def PolyType_param(self: PolyType, idx: Int)(using ctx: Context): Type = self.newParamRef(idx)
  def PolyType_paramNames(self: PolyType)(using ctx: Context): List[String] = self.paramNames.map(_.toString)
  def PolyType_paramBounds(self: PolyType)(using ctx: Context): List[TypeBounds] = self.paramInfos
  def PolyType_resType(self: PolyType)(using ctx: Context): Type = self.resType

  type TypeLambda = Types.TypeLambda

  def isInstanceOfTypeLambda(using ctx: Context): IsInstanceOf[TypeLambda] = new {
    def runtimeClass: Class[?] = classOf[TypeLambda]
    override def unapply(x: Any): Option[TypeLambda] = x match
      case tpe: Types.TypeLambda => Some(tpe)
      case _ => None
  }

  def TypeLambda_apply(paramNames: List[String], boundsFn: TypeLambda => List[TypeBounds], bodyFn: TypeLambda => Type): TypeLambda =
    Types.HKTypeLambda(paramNames.map(_.toTypeName))(boundsFn, bodyFn)

  def TypeLambda_paramNames(self: TypeLambda)(using ctx: Context): List[String] = self.paramNames.map(_.toString)
  def TypeLambda_paramBounds(self: TypeLambda)(using ctx: Context): List[TypeBounds] = self.paramInfos
  def TypeLambda_param(self: TypeLambda, idx: Int)(using ctx: Context): Type =
    self.newParamRef(idx)
  def TypeLambda_resType(self: TypeLambda)(using ctx: Context): Type = self.resType


  //////////////////////
  // IMPORT SELECTORS //
  //////////////////////

  type ImportSelector = untpd.ImportSelector

  type SimpleSelector = untpd.ImportSelector

  def isInstanceOfSimpleSelector(using ctx: Context): IsInstanceOf[SimpleSelector] = new {
    def runtimeClass: Class[?] = classOf[SimpleSelector]
    override def unapply(x: Any): Option[SimpleSelector] = x match
      case x: untpd.ImportSelector if x.renamed.isEmpty => Some(x)
      case _ => None // TODO: handle import bounds
  }

  def SimpleSelector_selection(self: SimpleSelector)(using ctx: Context): Id = self.imported

  type RenameSelector = untpd.ImportSelector

  def isInstanceOfRenameSelector(using ctx: Context): IsInstanceOf[RenameSelector] = new {
    def runtimeClass: Class[?] = classOf[RenameSelector]
    override def unapply(x: Any): Option[RenameSelector] = x match
      case x: untpd.ImportSelector if !x.renamed.isEmpty => Some(x)
      case _ => None
  }

  def RenameSelector_from(self: RenameSelector)(using ctx: Context): Id =
    self.imported
  def RenameSelector_to(self: RenameSelector)(using ctx: Context): Id =
    self.renamed.asInstanceOf[untpd.Ident]

  type OmitSelector = untpd.ImportSelector

  def isInstanceOfOmitSelector(using ctx: Context): IsInstanceOf[OmitSelector] = new {
    def runtimeClass: Class[?] = classOf[OmitSelector]
    override def unapply(x: Any): Option[OmitSelector] = x match {
      case self: untpd.ImportSelector =>
        self.renamed match
          case Trees.Ident(nme.WILDCARD) => Some(self)
          case _ => None
      case _ => None
    }

  }

  def SimpleSelector_omitted(self: OmitSelector)(using ctx: Context): Id =
    self.imported


  /////////////////
  // IDENTIFIERS //
  /////////////////

  type Id = untpd.Ident

  def Id_pos(self: Id)(using ctx: Context): Position = self.sourcePos

  def Id_name(self: Id)(using ctx: Context): String = self.name.toString


  ////////////////
  // SIGNATURES //
  ////////////////

  type Signature = core.Signature

  def Signature_paramSigs(self: Signature): List[String | Int] =
    self.paramsSig.map {
      case paramSig: core.Names.TypeName =>
        paramSig.toString
      case paramSig: Int =>
        paramSig
    }

  def Signature_resultSig(self: Signature): String =
    self.resSig.toString


  ///////////////
  // POSITIONS //
  ///////////////

  type Position = util.SourcePosition

  def Position_start(self: Position): Int = self.start

  def Position_end(self: Position): Int = self.end

  def Position_exists(self: Position): Boolean = self.exists

  def Position_sourceFile(self: Position): SourceFile = self.source

  def Position_startLine(self: Position): Int = self.startLine

  def Position_endLine(self: Position): Int = self.endLine

  def Position_startColumn(self: Position): Int = self.startColumn

  def Position_endColumn(self: Position): Int = self.endColumn

  def Position_sourceCode(self: Position): String =
    new String(self.source.content(), self.start, self.end - self.start)


  //////////////////
  // SOURCE FILES //
  //////////////////

  type SourceFile = util.SourceFile

  def SourceFile_jpath(self: SourceFile): java.nio.file.Path = self.file.jpath

  def SourceFile_content(self: SourceFile): String = new String(self.content())


  //////////////
  // COMMENTS //
  //////////////

  type Comment = core.Comments.Comment

  def Comment_raw(self: Comment): String = self.raw
  def Comment_expanded(self: Comment): Option[String] = self.expanded
  def Comment_usecases(self: Comment): List[(String, Option[DefDef])] = self.usecases.map { uc => (uc.code, uc.tpdCode) }


  ///////////////
  // CONSTANTS //
  ///////////////

  type Constant = Constants.Constant

  def Constant_value(const: Constant): Any = const.value

  def matchConstant(constant: Constant): Option[Unit | Null | Int | Boolean | Byte | Short | Int | Long | Float | Double | Char | String | Type] =
    Some(constant.value.asInstanceOf[Unit | Null | Int | Boolean | Byte | Short | Int | Long | Float | Double | Char | String | Type])

  def matchConstant_ClassTag(x: Constant): Option[Type] =
    if (x.tag == Constants.ClazzTag) Some(x.typeValue) else None

  def Constant_apply(x: Unit | Null | Int | Boolean | Byte | Short | Int | Long | Float | Double | Char | String | Type): Constant =
    Constants.Constant(x)

  def Constant_ClassTag_apply(x: Type): Constant = Constants.Constant(x)


  /////////////
  // SYMBOLS //
  /////////////

  type Symbol = core.Symbols.Symbol

  def Symbol_owner(self: Symbol)(using ctx: Context): Symbol = self.owner
  def Symbol_maybeOwner(self: Symbol)(using ctx: Context): Symbol = self.maybeOwner

  def Symbol_flags(self: Symbol)(using ctx: Context): Flags = self.flags

  def Symbol_tree(self: Symbol)(using ctx: Context): Tree =
    FromSymbol.definitionFromSym(self)

  def Symbol_privateWithin(self: Symbol)(using ctx: Context): Option[Type] = {
    val within = self.privateWithin
    if (within.exists && !self.is(core.Flags.Protected)) Some(within.typeRef)
    else None
  }

  def Symbol_protectedWithin(self: Symbol)(using ctx: Context): Option[Type] = {
    val within = self.privateWithin
    if (within.exists && self.is(core.Flags.Protected)) Some(within.typeRef)
    else None
  }

  def Symbol_name(self: Symbol)(using ctx: Context): String = self.name.toString

  def Symbol_fullName(self: Symbol)(using ctx: Context): String = self.fullName.toString

  def Symbol_pos(self: Symbol)(using ctx: Context): Position = self.sourcePos

  def Symbol_localContext(self: Symbol)(using ctx: Context): Context =
    if (self.exists) ctx.withOwner(self)
    else ctx

  def Symbol_comment(self: Symbol)(using ctx: Context): Option[Comment] = {
    import dotty.tools.dotc.core.Comments.CommentsContext
    val docCtx = ctx.docCtx.getOrElse {
      throw new RuntimeException(
        "DocCtx could not be found and comments are unavailable. This is a compiler-internal error."
      )
    }
    docCtx.docstring(self)
  }
  def Symbol_annots(self: Symbol)(using ctx: Context): List[Term] =
    self.annotations.flatMap {
      case _: core.Annotations.BodyAnnotation => Nil
      case annot => annot.tree :: Nil
    }

  def Symbol_isDefinedInCurrentRun(self: Symbol)(using ctx: Context): Boolean =
    self.topLevelClass.asClass.isDefinedInCurrentRun

  def Symbol_isLocalDummy(self: Symbol)(using ctx: Context): Boolean = self.isLocalDummy
  def Symbol_isRefinementClass(self: Symbol)(using ctx: Context): Boolean = self.isRefinementClass
  def Symbol_isAliasType(self: Symbol)(using ctx: Context): Boolean = self.isAliasType
  def Symbol_isAnonymousClass(self: Symbol)(using ctx: Context): Boolean = self.isAnonymousClass
  def Symbol_isAnonymousFunction(self: Symbol)(using ctx: Context): Boolean = self.isAnonymousFunction
  def Symbol_isAbstractType(self: Symbol)(using ctx: Context): Boolean = self.isAbstractType
  def Symbol_isClassConstructor(self: Symbol)(using ctx: Context): Boolean = self.isClassConstructor

  def Symbol_fields(self: Symbol)(using ctx: Context): List[Symbol] =
    self.unforcedDecls.filter(isField)

  def Symbol_field(self: Symbol)(name: String)(using ctx: Context): Symbol = {
    val sym = self.unforcedDecls.find(sym => sym.name == name.toTermName)
    if (isField(sym)) sym else core.Symbols.NoSymbol
  }

  def Symbol_classMethod(self: Symbol)(name: String)(using ctx: Context): List[Symbol] =
    self.typeRef.decls.iterator.collect {
      case sym if isMethod(sym) && sym.name.toString == name => sym.asTerm
    }.toList

  def Symbol_classMethods(self: Symbol)(using ctx: Context): List[Symbol] =
    self.typeRef.decls.iterator.collect {
      case sym if isMethod(sym) => sym.asTerm
    }.toList

  private def appliedTypeRef(sym: Symbol): Type = sym.typeRef.appliedTo(sym.typeParams.map(_.typeRef))

  def Symbol_method(self: Symbol)(name: String)(using ctx: Context): List[Symbol] =
    appliedTypeRef(self).allMembers.iterator.map(_.symbol).collect {
      case sym if isMethod(sym) && sym.name.toString == name => sym.asTerm
    }.toList

  def Symbol_methods(self: Symbol)(using ctx: Context): List[Symbol] =
    appliedTypeRef(self).allMembers.iterator.map(_.symbol).collect {
      case sym if isMethod(sym) => sym.asTerm
    }.toList

  private def isMethod(sym: Symbol)(using ctx: Context): Boolean =
    sym.isTerm && sym.is(Flags.Method) && !sym.isConstructor

  def Symbol_paramSymss(self: Symbol)(using ctx: Context): List[List[Symbol]] =
    self.paramSymss

  def Symbol_primaryConstructor(self: Symbol)(using Context): Symbol =
    self.primaryConstructor

  def Symbol_caseFields(self: Symbol)(using ctx: Context): List[Symbol] =
    if (!self.isClass) Nil
    else self.asClass.paramAccessors.collect {
      case sym if sym.is(Flags.CaseAccessor) => sym.asTerm
    }

  def Symbol_children(self: Symbol)(using ctx: Context): List[Symbol] =
    dotty.tools.dotc.transform.SymUtils(self).children

  private def isField(sym: Symbol)(using ctx: Context): Boolean = sym.isTerm && !sym.is(Flags.Method)

  def Symbol_of(fullName: String)(using ctx: Context): Symbol =
    ctx.requiredClass(fullName)

  def Symbol_newMethod(parent: Symbol, name: String, flags: Flags, tpe: Type, privateWithin: Symbol)(using ctx: Context): Symbol =
    ctx.newSymbol(parent, name.toTermName, flags | Flags.Method, tpe, privateWithin)

  def Symbol_newVal(parent: Symbol, name: String, flags: Flags, tpe: Type, privateWithin: Symbol)(using ctx: Context): Symbol =
    ctx.newSymbol(parent, name.toTermName, flags, tpe, privateWithin)

  def Symbol_isTypeParam(self: Symbol)(using ctx: Context): Boolean =
    self.isTypeParam

  def Symbol_isType(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.isType

  def Symbol_isTerm(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.isTerm

  def Symbol_isPackageDef(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.is(Flags.Package)

  def Symbol_isClassDef(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.isClass

  def Symbol_isTypeDef(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.isType && !symbol.isClass && !symbol.is(Flags.Case)

  def Symbol_isValDef(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.isTerm && !symbol.is(core.Flags.Method) && !symbol.is(core.Flags.Case/*, FIXME add this check and fix sourcecode butNot = Enum | Module*/)

  def Symbol_isDefDef(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.is(core.Flags.Method)

  def Symbol_isBind(symbol: Symbol)(using ctx: Context): Boolean =
    symbol.is(core.Flags.Case, butNot = Enum | Module) && !symbol.isClass

  def Symbol_signature(self: Symbol)(using ctx: Context): Signature =
    self.signature


  def Symbol_moduleClass(self: Symbol)(using ctx: Context): Symbol = self.moduleClass

  def Symbol_companionClass(self: Symbol)(using ctx: Context): Symbol = self.companionClass

  def Symbol_companionModule(self: Symbol)(using ctx: Context): Symbol = self.companionModule

  def Symbol_noSymbol(using ctx: Context): Symbol = core.Symbols.NoSymbol


  ///////////
  // FLAGS //
  ///////////

  type Flags = core.Flags.FlagSet

  /** Is the given flag set a subset of this flag sets */
  def Flags_is(self: Flags)(that: Flags): Boolean = self.isAllOf(that)

  /** Union of the two flag sets */
  def Flags_or(self: Flags)(that: Flags): Flags = self | that

  /** Intersection of the two flag sets */
  def Flags_and(self: Flags)(that: Flags): Flags = self & that

  def Flags_EmptyFlags: Flags = core.Flags.EmptyFlags
  def Flags_Private: Flags = core.Flags.Private
  def Flags_Protected: Flags = core.Flags.Protected
  def Flags_Abstract: Flags = core.Flags.Abstract
  def Flags_Open: Flags = core.Flags.Open
  def Flags_Final: Flags = core.Flags.Final
  def Flags_Sealed: Flags = core.Flags.Sealed
  def Flags_Case: Flags = core.Flags.Case
  def Flags_Implicit: Flags = core.Flags.Implicit
  def Flags_Given: Flags = core.Flags.Given
  def Flags_Erased: Flags = core.Flags.Erased
  def Flags_Lazy: Flags = core.Flags.Lazy
  def Flags_Override: Flags = core.Flags.Override
  def Flags_Inline: Flags = core.Flags.Inline
  def Flags_Macro: Flags = core.Flags.Macro
  def Flags_Static: Flags = core.Flags.JavaStatic
  def Flags_JavaDefined: Flags = core.Flags.JavaDefined
  def Flags_Object: Flags = core.Flags.Module
  def Flags_Trait: Flags = core.Flags.Trait
  def Flags_Local: Flags = core.Flags.Local
  def Flags_Synthetic: Flags = core.Flags.Synthetic
  def Flags_Artifact: Flags = core.Flags.Artifact
  def Flags_Mutable: Flags = core.Flags.Mutable
  def Flags_FieldAccessor: Flags = core.Flags.Accessor
  def Flags_CaseAcessor: Flags = core.Flags.CaseAccessor
  def Flags_Covariant: Flags = core.Flags.Covariant
  def Flags_Contravariant: Flags = core.Flags.Contravariant
  def Flags_Scala2X: Flags = core.Flags.Scala2x
  def Flags_HasDefault: Flags = core.Flags.HasDefault
  def Flags_StableRealizable: Flags = core.Flags.StableRealizable
  def Flags_Param: Flags = core.Flags.Param
  def Flags_ParamAccessor: Flags = core.Flags.ParamAccessor
  def Flags_Enum: Flags = core.Flags.Enum
  def Flags_ModuleClass: Flags = core.Flags.ModuleClass
  def Flags_PrivateLocal: Flags = core.Flags.PrivateLocal
  def Flags_Package: Flags = core.Flags.Package


  ////////////////////////
  // QUOTED SEAL/UNSEAL //
  ////////////////////////

  /** View this expression `quoted.Expr[?]` as a `Term` */
  def QuotedExpr_unseal(self: scala.quoted.Expr[?])(using ctx: Context): Term =
    PickledQuotes.quotedExprToTree(self)

  /** View this expression `quoted.Type[?]` as a `TypeTree` */
  def QuotedType_unseal(self: scala.quoted.Type[?])(using ctx: Context): TypeTree =
    PickledQuotes.quotedTypeToTree(self)

  /** Convert `Term` to an `quoted.Expr[Any]`  */
  def QuotedExpr_seal(self: Term)(using ctx: Context): scala.quoted.Expr[Any] = self.tpe.widen match {
    case _: Types.MethodType | _: Types.PolyType => throw new Exception("Cannot seal a partially applied Term. Try eta-expanding the term first.")
    case _ => new TastyTreeExpr(self, compilerId)
  }

  /** Checked cast to a `quoted.Expr[U]` */
  def QuotedExpr_cast[U](self: scala.quoted.Expr[?])(using tp: scala.quoted.Type[U], ctx: Context): scala.quoted.Expr[U] = {
    val tree = QuotedExpr_unseal(self)
    val expectedType = QuotedType_unseal(tp).tpe
    if (tree.tpe <:< expectedType)
      self.asInstanceOf[scala.quoted.Expr[U]]
    else
      throw new scala.tasty.reflect.ExprCastError(
        s"""Expr: ${tree.show}
           |did not conform to type: ${expectedType.show}
           |""".stripMargin
      )
  }

  /** Convert `Type` to an `quoted.Type[?]` */
  def QuotedType_seal(self: Type)(using ctx: Context): scala.quoted.Type[?] = {
    val dummySpan = ctx.owner.span // FIXME
    new TreeType(tpd.TypeTree(self).withSpan(dummySpan), compilerId)
  }

  /////////////////
  // DEFINITIONS //
  /////////////////

  // Symbols

  def Definitions_RootPackage: Symbol = defn.RootPackage
  def Definitions_RootClass: Symbol = defn.RootClass

  def Definitions_EmptyPackageClass: Symbol = defn.EmptyPackageClass

  def Definitions_ScalaPackage: Symbol = defn.ScalaPackageVal
  def Definitions_ScalaPackageClass: Symbol = defn.ScalaPackageClass

  def Definitions_AnyClass: Symbol = defn.AnyClass
  def Definitions_AnyValClass: Symbol = defn.AnyValClass
  def Definitions_ObjectClass: Symbol = defn.ObjectClass
  def Definitions_AnyRefClass: Symbol = defn.AnyRefAlias
  def Definitions_NullClass: Symbol = defn.AnyClass
  def Definitions_NothingClass: Symbol = defn.NothingClass
  def Definitions_UnitClass: Symbol = defn.UnitClass
  def Definitions_ByteClass: Symbol = defn.ByteClass
  def Definitions_ShortClass: Symbol = defn.ShortClass
  def Definitions_CharClass: Symbol = defn.CharClass
  def Definitions_IntClass: Symbol = defn.IntClass
  def Definitions_LongClass: Symbol = defn.LongClass
  def Definitions_FloatClass: Symbol = defn.FloatClass
  def Definitions_DoubleClass: Symbol = defn.DoubleClass
  def Definitions_BooleanClass: Symbol = defn.BooleanClass
  def Definitions_StringClass: Symbol = defn.StringClass
  def Definitions_ClassClass: Symbol = defn.ClassClass
  def Definitions_ArrayClass: Symbol = defn.ArrayClass
  def Definitions_PredefModule: Symbol = defn.ScalaPredefModule.asTerm
  def Definitions_Predef_classOf: Symbol = defn.Predef_classOf.asTerm

  def Definitions_JavaLangPackage: Symbol = defn.JavaLangPackageVal

  def Definitions_ArrayModule: Symbol = defn.ArrayClass.companionModule.asTerm

  def Definitions_Array_apply: Symbol = defn.Array_apply.asTerm
  def Definitions_Array_clone: Symbol = defn.Array_clone.asTerm
  def Definitions_Array_length: Symbol = defn.Array_length.asTerm
  def Definitions_Array_update: Symbol = defn.Array_update.asTerm

  def Definitions_RepeatedParamClass: Symbol = defn.RepeatedParamClass

  def Definitions_OptionClass: Symbol = defn.OptionClass
  def Definitions_NoneModule: Symbol = defn.NoneModule
  def Definitions_SomeModule: Symbol = defn.SomeClass.companionModule.asTerm

  def Definitions_ProductClass: Symbol = defn.ProductClass
  def Definitions_FunctionClass(arity: Int, isImplicit: Boolean, isErased: Boolean): Symbol =
    defn.FunctionClass(arity, isImplicit, isErased).asClass
  def Definitions_TupleClass(arity: Int): Symbol = defn.TupleType(arity).classSymbol.asClass
  def Definitions_isTupleClass(sym: Symbol): Boolean = defn.isTupleClass(sym)

  def Definitions_InternalQuoted_patternHole: Symbol = defn.InternalQuoted_patternHole
  def Definitions_InternalQuoted_patternTypeAnnot: Symbol = defn.InternalQuoted_patternTypeAnnot
  def Definitions_InternalQuoted_fromAboveAnnot: Symbol = defn.InternalQuoted_fromAboveAnnot

  // Types

  def Definitions_UnitType: Type = defn.UnitType
  def Definitions_ByteType: Type = defn.ByteType
  def Definitions_ShortType: Type = defn.ShortType
  def Definitions_CharType: Type = defn.CharType
  def Definitions_IntType: Type = defn.IntType
  def Definitions_LongType: Type = defn.LongType
  def Definitions_FloatType: Type = defn.FloatType
  def Definitions_DoubleType: Type = defn.DoubleType
  def Definitions_BooleanType: Type = defn.BooleanType
  def Definitions_AnyType: Type = defn.AnyType
  def Definitions_AnyValType: Type = defn.AnyValType
  def Definitions_AnyRefType: Type = defn.AnyRefType
  def Definitions_ObjectType: Type = defn.ObjectType
  def Definitions_NothingType: Type = defn.NothingType
  def Definitions_NullType: Type = defn.NullType
  def Definitions_StringType: Type = defn.StringType


  ///////////////
  // IMPLICITS //
  ///////////////

  type ImplicitSearchResult = Tree

  def searchImplicit(tpe: Type)(using ctx: Context): ImplicitSearchResult =
    ctx.typer.inferImplicitArg(tpe, rootPosition.span)

  type ImplicitSearchSuccess = Tree
  def isInstanceOfImplicitSearchSuccess(using ctx: Context): IsInstanceOf[ImplicitSearchSuccess] = new {
    def runtimeClass: Class[?] = classOf[ImplicitSearchSuccess]
    override def unapply(x: Any): Option[ImplicitSearchSuccess] = x match
      case x: Tree =>
        x.tpe match
          case _: SearchFailureType => None
          case _ => Some(x)
      case _ => None
  }
  def ImplicitSearchSuccess_tree(self: ImplicitSearchSuccess)(using ctx: Context): Term = self

  type ImplicitSearchFailure = Tree
  def isInstanceOfImplicitSearchFailure(using ctx: Context): IsInstanceOf[ImplicitSearchFailure] = new {
    def runtimeClass: Class[?] = classOf[ImplicitSearchFailure]
    override def unapply(x: Any): Option[ImplicitSearchFailure] = x match
      case x: Tree =>
        x.tpe match
          case _: SearchFailureType => Some(x)
          case _ => None
      case _ => None
  }
  def ImplicitSearchFailure_explanation(self: ImplicitSearchFailure)(using ctx: Context): String =
    self.tpe.asInstanceOf[SearchFailureType].explanation

  type DivergingImplicit = Tree
  def isInstanceOfDivergingImplicit(using ctx: Context): IsInstanceOf[DivergingImplicit] = new {
    def runtimeClass: Class[?] = classOf[DivergingImplicit]
    override def unapply(x: Any): Option[DivergingImplicit] = x match
      case x: Tree =>
        x.tpe match
          case _: Implicits.DivergingImplicit => Some(x)
          case _ => None
      case _ => None
  }

  type NoMatchingImplicits = Tree
  def isInstanceOfNoMatchingImplicits(using ctx: Context): IsInstanceOf[NoMatchingImplicits] = new {
    def runtimeClass: Class[?] = classOf[NoMatchingImplicits]
    override def unapply(x: Any): Option[NoMatchingImplicits] = x match
      case x: Tree =>
        x.tpe match
          case _: Implicits.NoMatchingImplicits => Some(x)
          case _ => None
      case _ => None
  }

  type AmbiguousImplicits = Tree
  def isInstanceOfAmbiguousImplicits(using ctx: Context): IsInstanceOf[AmbiguousImplicits] = new {
    def runtimeClass: Class[?] = classOf[AmbiguousImplicits]
    override def unapply(x: Any): Option[AmbiguousImplicits] = x match
      case x: Tree =>
        x.tpe match
          case _: Implicits.AmbiguousImplicits => Some(x)
          case _ => None
      case _ => None
  }

  def betaReduce(fn: Term, args: List[Term])(using ctx: Context): Term = {
    val (argVals0, argRefs0) = args.foldLeft((List.empty[ValDef], List.empty[Tree])) { case ((acc1, acc2), arg) => arg.tpe match {
      case tpe: SingletonType if isIdempotentExpr(arg) => (acc1, arg :: acc2)
      case _ =>
        val argVal = SyntheticValDef(NameKinds.UniqueName.fresh("x".toTermName), arg).withSpan(arg.span)
        (argVal :: acc1, ref(argVal.symbol) :: acc2)
    }}
    val argVals = argVals0.reverse
    val argRefs = argRefs0.reverse
    val reducedBody = lambdaExtractor(fn, argRefs.map(_.tpe)) match {
      case Some(body) => body(argRefs)
      case None => fn.select(nme.apply).appliedToArgs(argRefs)
    }
    seq(argVals, reducedBody).withSpan(fn.span)
  }

  def lambdaExtractor(fn: Term, paramTypes: List[Type])(using ctx: Context): Option[List[Term] => Term] = {
    def rec(fn: Term, transformBody: Term => Term): Option[List[Term] => Term] = {
      fn match {
        case Inlined(call, bindings, expansion) =>
          // this case must go before closureDef to avoid dropping the inline node
          rec(expansion, cpy.Inlined(fn)(call, bindings, _))
        case Typed(expr, tpt) =>
          val tpe = tpt.tpe.dropDependentRefinement
          // we checked that this is a plain Function closure, so there will be an apply method with a MethodType
          // and the expected signature based on param types
          val expectedSig = Signature.NotAMethod.prependTermParams(paramTypes, false)
          val method = tpt.tpe.member(nme.apply).atSignature(expectedSig)
          if method.symbol.is(Deferred) then
            val methodType = method.info.asInstanceOf[MethodType]
            // result might contain paramrefs, so we substitute them with arg termrefs
            val resultTypeWithSubst = methodType.resultType.substParams(methodType, paramTypes)
            rec(expr, Typed(_, TypeTree(resultTypeWithSubst).withSpan(tpt.span)))
          else
            None
        case cl @ closureDef(ddef) =>
          def replace(body: Term, argRefs: List[Term]): Term = {
            val paramSyms = ddef.vparamss.head.map(param => param.symbol)
            val paramToVals = paramSyms.zip(argRefs).toMap
            new TreeTypeMap(
              oldOwners = ddef.symbol :: Nil,
              newOwners = ctx.owner :: Nil,
              treeMap = tree => paramToVals.get(tree.symbol).map(_.withSpan(tree.span)).getOrElse(tree)
            ).transform(body)
          }
          Some(argRefs => replace(transformBody(ddef.rhs), argRefs))
        case Block(stats, expr) =>
          // this case must go after closureDef to avoid matching the closure
          rec(expr, cpy.Block(fn)(stats, _))
        case _ =>
          None
      }
    }
    rec(fn, identity)
  }

  /////////////
  // HELPERS //
  /////////////

  private def optional[T <: Trees.Tree[?]](tree: T): Option[tree.type] =
    if (tree.isEmpty) None else Some(tree)

  private def withDefaultPos[T <: Tree](fn: Context ?=> T)(using ctx: Context): T =
    fn(using ctx.withSource(rootPosition.source)).withSpan(rootPosition.span)

  private def compilerId: Int = rootContext.outersIterator.toList.last.hashCode()
}
