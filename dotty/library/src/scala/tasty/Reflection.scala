package scala.tasty

import scala.quoted.QuoteContext
import scala.quoted.show.SyntaxHighlight
import scala.tasty.reflect._

/** TASTy Reflect
 *
 *
 *  Type hierarchy
 *  ```none
 *
 *  +- Tree -+- PackageClause
 *           +- Import
 *           +- Statement -+- Definition --+- PackageDef
 *           |             |               +- ClassDef
 *           |             |               +- TypeDef
 *           |             |               +- DefDef
 *           |             |               +- ValDef
 *           |             |
 *           |             +- Term --------+- Ref -+- Ident
 *           |                             |       +- Select
 *           |                             |
 *           |                             +- Literal
 *           |                             +- This
 *           |                             +- New
 *           |                             +- NamedArg
 *           |                             +- Apply
 *           |                             +- TypeApply
 *           |                             +- Super
 *           |                             +- Typed
 *           |                             +- Assign
 *           |                             +- Block
 *           |                             +- Closure
 *           |                             +- If
 *           |                             +- Match
 *           |                             +- GivenMatch
 *           |                             +- Try
 *           |                             +- Return
 *           |                             +- Repeated
 *           |                             +- Inlined
 *           |                             +- SelectOuter
 *           |                             +- While
 *           |
 *           |
 *           +- TypeTree ----+- Inferred
 *           |               +- TypeIdent
 *           |               +- TypeSelect
 *           |               +- Projection
 *           |               +- Singleton
 *           |               +- Refined
 *           |               +- Applied
 *           |               +- Annotated
 *           |               +- MatchTypeTree
 *           |               +- ByName
 *           |               +- LambdaTypeTree
 *           |               +- TypeBind
 *           |               +- TypeBlock
 *           |
 *           +- TypeBoundsTree
 *           +- WildcardTypeTree
 *           |
 *           +- CaseDef
 *           |
 *           +- TypeCaseDef
 *           +- Bind
 *           +- Unapply
 *           +- Alternatives
 *
 *
 *                   +- NoPrefix
 *  +- TypeOrBounds -+- TypeBounds
 *                   |
 *                   +- Type -------+- ConstantType
 *                                  +- TermRef
 *                                  +- TypeRef
 *                                  +- SuperType
 *                                  +- Refinement
 *                                  +- AppliedType
 *                                  +- AnnotatedType
 *                                  +- AndType
 *                                  +- OrType
 *                                  +- MatchType
 *                                  +- ByNameType
 *                                  +- ParamRef
 *                                  +- ThisType
 *                                  +- RecursiveThis
 *                                  +- RecursiveType
 *                                  +- LambdaType[ParamInfo <: TypeOrBounds] -+- MethodType
 *                                                                            +- PolyType
 *                                                                            +- TypeLambda
 *
 *  +- ImportSelector -+- SimpleSelector
 *                     +- RenameSelector
 *                     +- OmitSelector
 *
 *  +- Id
 *
 *  +- Signature
 *
 *  +- Position
 *
 *  +- Comment
 *
 *  +- Constant
 *
 *  +- Symbol
 *
 *  +- Flags
 *
 *  ```
 */
class Reflection(private[scala] val internal: CompilerInterface) { self =>

  /** Compilation context */
  type Context = internal.Context

  /** Settings */
  type Settings = internal.Settings

  /** Tree representing code written in the source */
  type Tree = internal.Tree

  /** Tree representing a pacakage clause in the source code */
  type PackageClause = internal.PackageClause

  /** Tree representing a statement in the source code */
  type Statement = internal.Statement

  /** Tree representing an import in the source code */
  type Import = internal.Import

  /** Tree representing a definition in the source code. It can be `PackageDef`, `ClassDef`, `TypeDef`, `DefDef` or `ValDef` */
  type Definition = internal.Definition

  /** Tree representing a package definition. This includes definitions in all source files */
  type PackageDef = internal.PackageDef

  /** Tree representing a class definition. This includes annonymus class definitions and the class of a module object */
  type ClassDef = internal.ClassDef

  /** Tree representing a type (parameter or member) definition in the source code */
  type TypeDef = internal.TypeDef

  /** Tree representing a method definition in the source code */
  type DefDef = internal.DefDef

  /** Tree representing a value definition in the source code This inclues `val`, `lazy val`, `var`, `object` and parameter defintions. */
  type ValDef = internal.ValDef

  /** Tree representing an expression in the source code */
  type Term = internal.Term

  /** Tree representing a reference to definition */
  type Ref = internal.Ref

  /** Tree representing a reference to definition with a given name */
  type Ident = internal.Ident

  /** Tree representing a selection of definition with a given name on a given prefix */
  type Select = internal.Select

  /** Tree representing a literal value in the source code */
  type Literal = internal.Literal

  /** Tree representing `this` in the source code */
  type This = internal.This

  /** Tree representing `new` in the source code */
  type New = internal.New

  /** Tree representing an argument passed with an explicit name. Such as `arg1 = x` in `foo(arg1 = x)` */
  type NamedArg = internal.NamedArg

  /** Tree an application of arguments. It represents a single list of arguments, multiple argument lists will have nested `Apply`s  */
  type Apply = internal.Apply

  /** Tree an application of type arguments */
  type TypeApply = internal.TypeApply

  /** Tree representing `super` in the source code */
  type Super = internal.Super

  /** Tree representing a type ascription `x: T` in the source code */
  type Typed = internal.Typed

  /** Tree representing an assignment `x = y` in the source code */
  type Assign = internal.Assign

  /** Tree representing a block `{ ... }` in the source code */
  type Block = internal.Block

  /** A lambda `(...) => ...` in the source code is represented as
   *  a local method and a closure:
   *
   *  {
   *    def m(...) = ...
   *    closure(m)
   *  }
   *
   */
  type Closure = internal.Closure

  /** Tree representing an if/then/else `if (...) ... else ...` in the source code */
  type If = internal.If

  /** Tree representing a pattern match `x match  { ... }` in the source code */
  type Match = internal.Match

  /** Tree representing a pattern match `given match { ... }` in the source code */  // TODO: drop
  type GivenMatch = internal.GivenMatch

  /** Tree representing a try catch `try x catch { ... } finally { ... }` in the source code */
  type Try = internal.Try

  /** Tree representing a `return` in the source code */
  type Return = internal.Return

  /** Tree representing a variable argument list in the source code */
  type Repeated = internal.Repeated

  /** Tree representing the scope of an inlined tree */
  type Inlined = internal.Inlined

  /** Tree representing a selection of definition with a given name on a given prefix and number of nested scopes of inlined trees */
  type SelectOuter = internal.SelectOuter

  /** Tree representing a while loop */
  type While = internal.While

  /** Type tree representing a type written in the source */
  type TypeTree = internal.TypeTree

  /** Type tree representing an inferred type */
  type Inferred = internal.Inferred

  /** Type tree representing a reference to definition with a given name */
  type TypeIdent = internal.TypeIdent

  /** Type tree representing a selection of definition with a given name on a given term prefix */
  type TypeSelect = internal.TypeSelect

  /** Type tree representing a selection of definition with a given name on a given type prefix */
  type Projection = internal.Projection

  /** Type tree representing a singleton type */
  type Singleton = internal.Singleton

  /** Type tree representing a type refinement */
  type Refined = internal.Refined

  /** Type tree representing a type application */
  type Applied = internal.Applied

  /** Type tree representing an annotated type */
  type Annotated = internal.Annotated

  /** Type tree representing a type match */
  type MatchTypeTree = internal.MatchTypeTree

  /** Type tree representing a by name parameter */
  type ByName = internal.ByName

  /** Type tree representing a lambda abstraction type */
  type LambdaTypeTree = internal.LambdaTypeTree

  /** Type tree representing a type binding */
  type TypeBind = internal.TypeBind

  /** Type tree within a block with aliases `{ type U1 = ... ; T[U1, U2] }` */
  type TypeBlock = internal.TypeBlock

  /** Type tree representing a type bound written in the source */
  type TypeBoundsTree = internal.TypeBoundsTree

  /** Type tree representing wildcard type bounds written in the source.
   *  The wildcard type `_` (for example in in `List[_]`) will be a type tree that
   *  represents a type but has `TypeBound`a inside.
   */
  type WildcardTypeTree = internal.WildcardTypeTree

  /** Branch of a pattern match or catch clause */
  type CaseDef = internal.CaseDef

  /** Branch of a type pattern match */
  type TypeCaseDef = internal.TypeCaseDef

  /** Pattern representing a `_ @ _` binding. */
  type Bind = internal.Bind

  /** Pattern representing a `Xyz(...)` unapply. */
  type Unapply = internal.Unapply

  /** Pattern representing `X | Y | ...` alternatives. */
  type Alternatives = internal.Alternatives

  /** Type or bounds */
  type TypeOrBounds = internal.TypeOrBounds

  /** NoPrefix for a type selection */
  type NoPrefix = internal.NoPrefix

  /** Type bounds */
  type TypeBounds = internal.TypeBounds

  /** A type */
  type Type = internal.Type

  /** A singleton type representing a known constant value */
  type ConstantType = internal.ConstantType

  /** Type of a reference to a term symbol */
  type TermRef = internal.TermRef

  /** Type of a reference to a type symbol */
  type TypeRef = internal.TypeRef

  /** Type of a `super` reference */
  type SuperType = internal.SuperType

  /** A type with a type refinement `T { type U }` */
  type Refinement = internal.Refinement

  /** A higher kinded type applied to some types `T[U]` */
  type AppliedType = internal.AppliedType

  /** A type with an anottation `T @foo` */
  type AnnotatedType = internal.AnnotatedType

  /** Intersection type `T & U` */
  type AndType = internal.AndType

  /** Union type `T | U` */
  type OrType = internal.OrType

  /** Type match `T match { case U => ... }` */
  type MatchType = internal.MatchType

  /** Type of a by by name parameter */
  type ByNameType = internal.ByNameType

  /** Type of a parameter reference */
  type ParamRef = internal.ParamRef

  /** Type of `this` */
  type ThisType = internal.ThisType

  /** A type that is recursively defined `this` */
  type RecursiveThis = internal.RecursiveThis

  /** A type that is recursively defined */
  type RecursiveType = internal.RecursiveType

  // TODO can we add the bound back without an cake?
  // TODO is LambdaType really needed? ParamRefExtractor could be split into more precise extractors
  /** Common abstraction for lambda types (MethodType, PolyType and TypeLambda). */
  type LambdaType[ParamInfo /*<: TypeOrBounds*/] = internal.LambdaType[ParamInfo]

  /** Type of the definition of a method taking a single list of parameters. It's return type may be a MethodType. */
  type MethodType = internal.MethodType

  /** Type of the definition of a method taking a list of type parameters. It's return type may be a MethodType. */
  type PolyType = internal.PolyType

  /** Type of the definition of a type lambda taking a list of type parameters. It's return type may be a TypeLambda. */
  type TypeLambda = internal.TypeLambda


  /** Import selectors:
   *   * SimpleSelector: `.bar` in `import foo.bar`
   *   * RenameSelector: `.{bar => baz}` in `import foo.{bar => baz}`
   *   * OmitSelector: `.{bar => _}` in `import foo.{bar => _}`
   */
  type ImportSelector = internal.ImportSelector
  type SimpleSelector = internal.SimpleSelector
  type RenameSelector = internal.RenameSelector
  type OmitSelector = internal.OmitSelector

  /** Untyped identifier */
  type Id = internal.Id

  /** Signature of a method */
  type Signature = internal.Signature

  /** Position in a source file */
  type Position = internal.Position

  /** Scala source file */
  type SourceFile = internal.SourceFile

  /** Comment */
  type Comment = internal.Comment

  /** Constant value represented as the constant itself */
  type Constant = internal.Constant

  /** Symbol of a definition.
   *  Then can be compared with == to know if the definition is the same.
   */
  type Symbol = internal.Symbol

  /** FlagSet of a Symbol */
  type Flags = internal.Flags

  type ImplicitSearchResult = internal.ImplicitSearchResult

  type ImplicitSearchSuccess = internal.ImplicitSearchSuccess

  type ImplicitSearchFailure = internal.ImplicitSearchFailure

  type DivergingImplicit = internal.DivergingImplicit

  type NoMatchingImplicits = internal.NoMatchingImplicits

  type AmbiguousImplicits = internal.AmbiguousImplicits


  //////////////
  // CONTEXTS //
  //////////////

  /** Context of the macro expansion */
  def rootContext: Context = internal.rootContext // TODO: Should this be moved to QuoteContext?
  given Context = rootContext // TODO: Should be an implicit converion from QuoteContext to Context

  extension ContextOps on (self: Context) {
    /** Returns the owner of the context */
    def owner: Symbol = internal.Context_owner(self)

    /** Returns the source file being compiled. The path is relative to the current working directory. */
    def source: java.nio.file.Path = internal.Context_source(self)

    /** Get package symbol if package is either defined in current compilation run or present on classpath. */
    def requiredPackage(path: String): Symbol = internal.Context_requiredPackage(self)(path)

    /** Get class symbol if class is either defined in current compilation run or present on classpath. */
    def requiredClass(path: String): Symbol = internal.Context_requiredClass(self)(path)

    /** Get module symbol if module is either defined in current compilation run or present on classpath. */
    def requiredModule(path: String): Symbol = internal.Context_requiredModule(self)(path)

    /** Get method symbol if method is either defined in current compilation run or present on classpath. Throws if the method has an overload. */
    def requiredMethod(path: String): Symbol = internal.Context_requiredMethod(self)(path)

    /** Returns true if we've tried to reflect on a Java class. */
    def isJavaCompilationUnit(): Boolean = internal Context_isJavaCompilationUnit(self)

    /** Returns true if we've tried to reflect on a Scala2 (non-Tasty) class. */
    def isScala2CompilationUnit(): Boolean = internal Context_isScala2CompilationUnit(self)

    /** Returns true if we've tried to reflect on a class that's already loaded (e.g. Option). */
    def isAlreadyLoadedCompilationUnit(): Boolean = internal.Context_isAlreadyLoadedCompilationUnit(self)

    /** Class name of the current CompilationUnit */
    def compilationUnitClassname(): String = internal.Context_compilationUnitClassname(self)
  }


  ///////////////
  //   TREES   //
  ///////////////

  // ----- Tree -----------------------------------------------------

  /** Members of Tree */
  extension TreeOps on (tree: Tree) {
    /** Position in the source code */
    def pos(using ctx: Context): Position = internal.Tree_pos(tree)

    /** Symbol of defined or referred by this tree */
    def symbol(using ctx: Context): Symbol = internal.Tree_symbol(tree)

    /** Shows the tree as extractors */
    def showExtractors(using ctx: Context): String =
      new ExtractorsPrinter[self.type](self).showTree(tree)

    /** Shows the tree as fully typed source code */
    def show(using ctx: Context): String =
      tree.showWith(SyntaxHighlight.plain)

    /** Shows the tree as fully typed source code */
    def showWith(syntaxHighlight: SyntaxHighlight)(using ctx: Context): String =
      new SourceCodePrinter[self.type](self)(syntaxHighlight).showTree(tree)
  }

  given (using ctx: Context) as IsInstanceOf[PackageClause] = internal.isInstanceOfPackageClause

  object PackageClause {
    def apply(pid: Ref, stats: List[Tree])(using ctx: Context): PackageClause =
      internal.PackageClause_apply(pid, stats)
    def copy(original: Tree)(pid: Ref, stats: List[Tree])(using ctx: Context): PackageClause =
      internal.PackageClause_copy(original)(pid, stats)
    def unapply(tree: PackageClause)(using ctx: Context): Some[(Ref, List[Tree])] =
      Some((tree.pid, tree.stats))
  }

  extension PackageClauseOps on (self: PackageClause) {
    def pid(using ctx: Context): Ref = internal.PackageClause_pid(self)
    def stats(using ctx: Context): List[Tree] = internal.PackageClause_stats(self)
  }

  given (using ctx: Context) as IsInstanceOf[Import] = internal.isInstanceOfImport

  object Import {
    def apply(expr: Term, selectors: List[ImportSelector])(using ctx: Context): Import =
      internal.Import_apply(expr, selectors)
    def copy(original: Tree)(expr: Term, selectors: List[ImportSelector])(using ctx: Context): Import =
      internal.Import_copy(original)(expr, selectors)
    def unapply(tree: Import)(using ctx: Context): Option[(Term, List[ImportSelector])] =
      Some((tree.expr, tree.selectors))
  }

  extension ImportOps on (self: Import)  {
    def expr(using ctx: Context): Term = internal.Import_expr(self)
    def selectors(using ctx: Context): List[ImportSelector] =
      internal.Import_selectors(self)
  }

  given (using ctx: Context) as IsInstanceOf[Statement] = internal.isInstanceOfStatement

  // ----- Definitions ----------------------------------------------

  given (using ctx: Context) as IsInstanceOf[Definition] = internal.isInstanceOfDefinition

  extension DefinitionOps on (self: Definition) {
    def name(using ctx: Context): String = internal.Definition_name(self)
  }

  // ClassDef

  given (using ctx: Context) as IsInstanceOf[ClassDef] = internal.isInstanceOfClassDef

  object ClassDef {
    // TODO def apply(name: String, constr: DefDef, parents: List[TermOrTypeTree], selfOpt: Option[ValDef], body: List[Statement])(using ctx: Context): ClassDef
    def copy(original: Tree)(name: String, constr: DefDef, parents: List[Tree /* Term | TypeTree */], derived: List[TypeTree], selfOpt: Option[ValDef], body: List[Statement])(using ctx: Context): ClassDef =
      internal.ClassDef_copy(original)(name, constr, parents, derived, selfOpt, body)
    def unapply(cdef: ClassDef)(using ctx: Context): Option[(String, DefDef, List[Tree /* Term | TypeTree */], List[TypeTree], Option[ValDef], List[Statement])] =
      Some((cdef.name, cdef.constructor, cdef.parents, cdef.derived, cdef.self, cdef.body))
  }

  extension ClassDefOps on (self: ClassDef) {
    def constructor(using ctx: Context): DefDef = internal.ClassDef_constructor(self)
    def parents(using ctx: Context): List[Tree /* Term | TypeTree */] = internal.ClassDef_parents(self)
    def derived(using ctx: Context): List[TypeTree] = internal.ClassDef_derived(self)
    def self(using ctx: Context): Option[ValDef] = internal.ClassDef_self(self)
    def body(using ctx: Context): List[Statement] = internal.ClassDef_body(self)
  }

  // DefDef

  given (using ctx: Context) as IsInstanceOf[DefDef] = internal.isInstanceOfDefDef

  object DefDef {
    def apply(symbol: Symbol, rhsFn: List[Type] => List[List[Term]] => Option[Term])(using ctx: Context): DefDef =
      internal.DefDef_apply(symbol, rhsFn)
    def copy(original: Tree)(name: String, typeParams: List[TypeDef], paramss: List[List[ValDef]], tpt: TypeTree, rhs: Option[Term])(using ctx: Context): DefDef =
      internal.DefDef_copy(original)(name, typeParams, paramss, tpt, rhs)
    def unapply(ddef: DefDef)(using ctx: Context): Option[(String, List[TypeDef], List[List[ValDef]], TypeTree, Option[Term])] =
      Some((ddef.name, ddef.typeParams, ddef.paramss, ddef.returnTpt, ddef.rhs))
  }

  extension DefDefOps on (self: DefDef) {
    def typeParams(using ctx: Context): List[TypeDef] = internal.DefDef_typeParams(self)
    def paramss(using ctx: Context): List[List[ValDef]] = internal.DefDef_paramss(self)
    def returnTpt(using ctx: Context): TypeTree = internal.DefDef_returnTpt(self) // TODO rename to tpt
    def rhs(using ctx: Context): Option[Term] = internal.DefDef_rhs(self)
  }

  // ValDef

  given (using ctx: Context) as IsInstanceOf[ValDef] = internal.isInstanceOfValDef

  object ValDef {
    def apply(symbol: Symbol, rhs: Option[Term])(using ctx: Context): ValDef =
      internal.ValDef_apply(symbol, rhs)
    def copy(original: Tree)(name: String, tpt: TypeTree, rhs: Option[Term])(using ctx: Context): ValDef =
      internal.ValDef_copy(original)(name, tpt, rhs)
    def unapply(vdef: ValDef)(using ctx: Context): Option[(String, TypeTree, Option[Term])] =
      Some((vdef.name, vdef.tpt, vdef.rhs))
  }

  extension ValDefOps on (self: ValDef) {
    def tpt(using ctx: Context): TypeTree = internal.ValDef_tpt(self)
    def rhs(using ctx: Context): Option[Term] = internal.ValDef_rhs(self)
  }

  // TypeDef

  given (using ctx: Context) as IsInstanceOf[TypeDef] = internal.isInstanceOfTypeDef

  object TypeDef {
    def apply(symbol: Symbol)(using ctx: Context): TypeDef =
      internal.TypeDef_apply(symbol)
    def copy(original: Tree)(name: String, rhs: Tree /*TypeTree | TypeBoundsTree*/)(using ctx: Context): TypeDef =
      internal.TypeDef_copy(original)(name, rhs)
    def unapply(tdef: TypeDef)(using ctx: Context): Option[(String, Tree /*TypeTree | TypeBoundsTree*/ /* TypeTree | TypeBoundsTree */)] =
      Some((tdef.name, tdef.rhs))
  }

  extension TypeDefOps on (self: TypeDef) {
    def rhs(using ctx: Context): Tree /*TypeTree | TypeBoundsTree*/ = internal.TypeDef_rhs(self)
  }

  // PackageDef

  given (using ctx: Context) as IsInstanceOf[PackageDef] = internal.isInstanceOfPackageDef

  extension PackageDefOps on (self: PackageDef) {
    def owner(using ctx: Context): PackageDef = internal.PackageDef_owner(self)
    def members(using ctx: Context): List[Statement] = internal.PackageDef_members(self)
  }

  object PackageDef {
    def unapply(tree: PackageDef)(using ctx: Context): Option[(String, PackageDef)] =
      Some((tree.name, tree.owner))
  }

  // ----- Terms ----------------------------------------------------

  extension TermOps on (self: Term) {

    /** Convert `Term` to an `quoted.Expr[Any]` */
    def seal(using ctx: Context): scala.quoted.Expr[Any] =
      internal.QuotedExpr_seal(self)

    def tpe(using ctx: Context): Type = internal.Term_tpe(self)
    def underlyingArgument(using ctx: Context): Term = internal.Term_underlyingArgument(self)
    def underlying(using ctx: Context): Term = internal.Term_underlying(self)
    def etaExpand(using ctx: Context): Term = internal.Term_etaExpand(self)

    /** A unary apply node with given argument: `tree(arg)` */
    def appliedTo(arg: Term)(using ctx: Context): Term =
      self.appliedToArgs(arg :: Nil)

    /** An apply node with given arguments: `tree(arg, args0, ..., argsN)` */
    def appliedTo(arg: Term, args: Term*)(using ctx: Context): Term =
      self.appliedToArgs(arg :: args.toList)

    /** An apply node with given argument list `tree(args(0), ..., args(args.length - 1))` */
    def appliedToArgs(args: List[Term])(using ctx: Context): Apply =
      Apply(self, args)

    /** The current tree applied to given argument lists:
     *  `tree (argss(0)) ... (argss(argss.length -1))`
     */
    def appliedToArgss(argss: List[List[Term]])(using ctx: Context): Term =
      argss.foldLeft(self: Term)(Apply(_, _))

    /** The current tree applied to (): `tree()` */
    def appliedToNone(using ctx: Context): Apply =
      self.appliedToArgs(Nil)

    /** The current tree applied to given type argument: `tree[targ]` */
    def appliedToType(targ: Type)(using ctx: Context): Term =
      self.appliedToTypes(targ :: Nil)

    /** The current tree applied to given type arguments: `tree[targ0, ..., targN]` */
    def appliedToTypes(targs: List[Type])(using ctx: Context): Term =
      self.appliedToTypeTrees(targs map (Inferred(_)))

    /** The current tree applied to given type argument list: `tree[targs(0), ..., targs(targs.length - 1)]` */
    def appliedToTypeTrees(targs: List[TypeTree])(using ctx: Context): Term =
      if (targs.isEmpty) self else TypeApply(self, targs)

    /** A select node that selects the given symbol.
     */
    def select(sym: Symbol)(using ctx: Context): Select = Select(self, sym)
  }

  given (using ctx: Context) as IsInstanceOf[Term] = internal.isInstanceOfTerm

  given (using ctx: Context) as IsInstanceOf[Ref] = internal.isInstanceOfRef

  object Ref {

    /** Create a reference tree from a symbol
     *
     *  If `sym` refers to a class member `foo` in class `C`,
     *  returns a tree representing `C.this.foo`.
     *
     *  If `sym` refers to a local definition `foo`, returns
     *  a tree representing `foo`.
     *
     *  @note In both cases, the constructed tree should only
     *  be spliced into the places where such accesses make sense.
     *  For example, it is incorrect to have `C.this.foo` outside
     *  the class body of `C`, or have `foo` outside the lexical
     *  scope for the definition of `foo`.
     */
    def apply(sym: Symbol)(using ctx: Context): Ref =
      internal.Ref_apply(sym)
  }

  given (using ctx: Context) as IsInstanceOf[Ident] = internal.isInstanceOfIdent

  extension IdentOps on (self: Ident) {
    def name(using ctx: Context): String = internal.Ident_name(self)
  }

  /** Scala term identifier */
  object Ident {
    def apply(tmref: TermRef)(using ctx: Context): Term =
      internal.Ident_apply(tmref)

    def copy(original: Tree)(name: String)(using ctx: Context): Ident =
      internal.Ident_copy(original)(name)

    /** Matches a term identifier and returns its name */
    def unapply(tree: Ident)(using ctx: Context): Option[String] =
      Some(tree.name)
  }

  given (using ctx: Context) as IsInstanceOf[Select] = internal.isInstanceOfSelect

  /** Scala term selection */
  object Select {
    /** Select a term member by symbol */
    def apply(qualifier: Term, symbol: Symbol)(using ctx: Context): Select =
      internal.Select_apply(qualifier, symbol)

    /** Select a field or a non-overloaded method by name
     *
     *  @note The method will produce an assertion error if the selected
     *        method is overloaded. The method `overloaded` should be used
     *        in that case.
     */
    def unique(qualifier: Term, name: String)(using ctx: Context): Select =
      internal.Select_unique(qualifier, name)

    // TODO rename, this returns an Apply and not a Select
    /** Call an overloaded method with the given type and term parameters */
    def overloaded(qualifier: Term, name: String, targs: List[Type], args: List[Term])(using ctx: Context): Apply =
      internal.Select_overloaded(qualifier, name, targs, args)

    def copy(original: Tree)(qualifier: Term, name: String)(using ctx: Context): Select =
      internal.Select_copy(original)(qualifier, name)

    /** Matches `<qualifier: Term>.<name: String>` */
    def unapply(x: Select)(using ctx: Context): Option[(Term, String)] =
      Some((x.qualifier, x.name))
  }

  extension SelectOps on (self: Select) {
    def qualifier(using ctx: Context): Term = internal.Select_qualifier(self)
    def name(using ctx: Context): String = internal.Select_name(self)
    def signature(using ctx: Context): Option[Signature] = internal.Select_signature(self)
  }

  given (using ctx: Context) as IsInstanceOf[Literal] =
    internal.isInstanceOfLiteral

  /** Scala literal constant */
  object Literal {

    /** Create a literal constant */
    def apply(constant: Constant)(using ctx: Context): Literal =
      internal.Literal_apply(constant)

    def copy(original: Tree)(constant: Constant)(using ctx: Context): Literal =
      internal.Literal_copy(original)(constant)

    /** Matches a literal constant */
    def unapply(x: Literal)(using ctx: Context): Option[Constant] =
      Some(x.constant)
  }

  extension LiteralOps on (self: Literal) {
    def constant(using ctx: Context): Constant = internal.Literal_constant(self)
  }

  given (using ctx: Context) as IsInstanceOf[This] = internal.isInstanceOfThis

  /** Scala `this` or `this[id]` */
  object This {

    /** Create a `this[<id: Id]>` */
    def apply(cls: Symbol)(using ctx: Context): This =
      internal.This_apply(cls)

    def copy(original: Tree)(qual: Option[Id])(using ctx: Context): This =
      internal.This_copy(original)(qual)

    /** Matches `this[<id: Option[Id]>` */
    def unapply(x: This)(using ctx: Context): Option[Option[Id]] = Some(x.id)

  }

  extension ThisOps on (self: This) {
    def id(using ctx: Context): Option[Id] = internal.This_id(self)
  }

  given (using ctx: Context) as IsInstanceOf[New] = internal.isInstanceOfNew

  /** Scala `new` */
  object New {

    /** Create a `new <tpt: TypeTree>` */
    def apply(tpt: TypeTree)(using ctx: Context): New =
      internal.New_apply(tpt)

    def copy(original: Tree)(tpt: TypeTree)(using ctx: Context): New =
      internal.New_copy(original)(tpt)

    /** Matches a `new <tpt: TypeTree>` */
    def unapply(x: New)(using ctx: Context): Option[TypeTree] = Some(x.tpt)
  }

  extension NewOps on (self: New) {
    def tpt(using ctx: Context): TypeTree = internal.New_tpt(self)
  }

  given (using ctx: Context) as IsInstanceOf[NamedArg] = internal.isInstanceOfNamedArg

  /** Scala named argument `x = y` in argument position */
  object NamedArg {

    /** Create a named argument `<name: String> = <value: Term>` */
    def apply(name: String, arg: Term)(using ctx: Context): NamedArg =
      internal.NamedArg_apply(name, arg)

    def copy(original: Tree)(name: String, arg: Term)(using ctx: Context): NamedArg =
      internal.NamedArg_copy(original)(name, arg)

    /** Matches a named argument `<name: String> = <value: Term>` */
    def unapply(x: NamedArg)(using ctx: Context): Option[(String, Term)] =
      Some((x.name, x.value))

  }

  extension NamedArgOps on (self: NamedArg) {
    def name(using ctx: Context): String = internal.NamedArg_name(self)
    def value(using ctx: Context): Term = internal.NamedArg_value(self)
  }

  given (using ctx: Context) as IsInstanceOf[Apply] = internal.isInstanceOfApply

  /** Scala parameter application */
  object Apply {

    /** Create a function application `<fun: Term>(<args: List[Term]>)` */
    def apply(fun: Term, args: List[Term])(using ctx: Context): Apply =
      internal.Apply_apply(fun, args)

    def copy(original: Tree)(fun: Term, args: List[Term])(using ctx: Context): Apply =
      internal.Apply_copy(original)(fun, args)

    /** Matches a function application `<fun: Term>(<args: List[Term]>)` */
    def unapply(x: Apply)(using ctx: Context): Option[(Term, List[Term])] =
      Some((x.fun, x.args))
  }

  extension ApplyOps on (self: Apply) {
    def fun(using ctx: Context): Term = internal.Apply_fun(self)
    def args(using ctx: Context): List[Term] = internal.Apply_args(self)
  }

  given (using ctx: Context) as IsInstanceOf[TypeApply] = internal.isInstanceOfTypeApply

  /** Scala type parameter application */
  object TypeApply {

    /** Create a function type application `<fun: Term>[<args: List[TypeTree]>]` */
    def apply(fun: Term, args: List[TypeTree])(using ctx: Context): TypeApply =
      internal.TypeApply_apply(fun, args)

    def copy(original: Tree)(fun: Term, args: List[TypeTree])(using ctx: Context): TypeApply =
      internal.TypeApply_copy(original)(fun, args)

    /** Matches a function type application `<fun: Term>[<args: List[TypeTree]>]` */
    def unapply(x: TypeApply)(using ctx: Context): Option[(Term, List[TypeTree])] =
      Some((x.fun, x.args))

  }

  extension TypeApplyOps on (self: TypeApply) {
    def fun(using ctx: Context): Term = internal.TypeApply_fun(self)
    def args(using ctx: Context): List[TypeTree] = internal.TypeApply_args(self)
  }

  given (using ctx: Context) as IsInstanceOf[Super] = internal.isInstanceOfSuper

  /** Scala `x.super` or `x.super[id]` */

  object Super {

    /** Creates a `<qualifier: Term>.super[<id: Option[Id]>` */
    def apply(qual: Term, mix: Option[Id])(using ctx: Context): Super =
      internal.Super_apply(qual, mix)

    def copy(original: Tree)(qual: Term, mix: Option[Id])(using ctx: Context): Super =
      internal.Super_copy(original)(qual, mix)

    /** Matches a `<qualifier: Term>.super[<id: Option[Id]>` */
    def unapply(x: Super)(using ctx: Context): Option[(Term, Option[Id])] =
      Some((x.qualifier, x.id))
  }

  extension SuperOps on (self: Super) {
    def qualifier(using ctx: Context): Term = internal.Super_qualifier(self)
    def id(using ctx: Context): Option[Id] = internal.Super_id(self)
  }

  given (using ctx: Context) as IsInstanceOf[Typed] = internal.isInstanceOfTyped

  /** Scala ascription `x: T` */
  object Typed {

    /** Create a type ascription `<x: Term>: <tpt: TypeTree>` */
    def apply(expr: Term, tpt: TypeTree)(using ctx: Context): Typed =
      internal.Typed_apply(expr, tpt)

    def copy(original: Tree)(expr: Term, tpt: TypeTree)(using ctx: Context): Typed =
      internal.Typed_copy(original)(expr, tpt)

    /** Matches `<expr: Term>: <tpt: TypeTree>` */
    def unapply(x: Typed)(using ctx: Context): Option[(Term, TypeTree)] =
      Some((x.expr, x.tpt))

  }

  extension TypedOps on (self: Typed) {
    def expr(using ctx: Context): Term = internal.Typed_expr(self)
    def tpt(using ctx: Context): TypeTree = internal.Typed_tpt(self)
  }

  given (using ctx: Context) as IsInstanceOf[Assign] = internal.isInstanceOfAssign

  /** Scala assign `x = y` */
  object Assign {

    /** Create an assignment `<lhs: Term> = <rhs: Term>` */
    def apply(lhs: Term, rhs: Term)(using ctx: Context): Assign =
      internal.Assign_apply(lhs, rhs)

    def copy(original: Tree)(lhs: Term, rhs: Term)(using ctx: Context): Assign =
      internal.Assign_copy(original)(lhs, rhs)

    /** Matches an assignment `<lhs: Term> = <rhs: Term>` */
    def unapply(x: Assign)(using ctx: Context): Option[(Term, Term)] =
      Some((x.lhs, x.rhs))
  }

  extension AssignOps on (self: Assign) {
    def lhs(using ctx: Context): Term = internal.Assign_lhs(self)
    def rhs(using ctx: Context): Term = internal.Assign_rhs(self)
  }

  given (using ctx: Context) as IsInstanceOf[Block] = internal.isInstanceOfBlock

  /** Scala code block `{ stat0; ...; statN; expr }` term */

  object Block {

    /** Creates a block `{ <statements: List[Statement]>; <expr: Term> }` */
    def apply(stats: List[Statement], expr: Term)(using ctx: Context): Block =
      internal.Block_apply(stats, expr)

    def copy(original: Tree)(stats: List[Statement], expr: Term)(using ctx: Context): Block =
      internal.Block_copy(original)(stats, expr)

    /** Matches a block `{ <statements: List[Statement]>; <expr: Term> }` */
    def unapply(x: Block)(using ctx: Context): Option[(List[Statement], Term)] =
      Some((x.statements, x.expr))
  }

  extension BlockOps on (self: Block) {
    def statements(using ctx: Context): List[Statement] = internal.Block_statements(self)
    def expr(using ctx: Context): Term = internal.Block_expr(self)
  }

  given (using ctx: Context) as IsInstanceOf[Closure] = internal.isInstanceOfClosure

  object Closure {

    def apply(meth: Term, tpt: Option[Type])(using ctx: Context): Closure =
      internal.Closure_apply(meth, tpt)

    def copy(original: Tree)(meth: Tree, tpt: Option[Type])(using ctx: Context): Closure =
      internal.Closure_copy(original)(meth, tpt)

    def unapply(x: Closure)(using ctx: Context): Option[(Term, Option[Type])] =
      Some((x.meth, x.tpeOpt))
  }

  extension ClosureOps on (self: Closure) {
    def meth(using ctx: Context): Term = internal.Closure_meth(self)
    def tpeOpt(using ctx: Context): Option[Type] = internal.Closure_tpeOpt(self)
  }

  /** A lambda `(...) => ...` in the source code is represented as
   *  a local method and a closure:
   *
   *  {
   *    def m(...) = ...
   *    closure(m)
   *  }
   *
   *  @note Due to the encoding, in pattern matches the case for `Lambda`
   *        should come before the case for `Block` to avoid mishandling
   *        of `Lambda`.
   */
  object Lambda {
    def unapply(tree: Block)(using ctx: Context): Option[(List[ValDef], Term)] = tree match {
      case Block((ddef @ DefDef(_, _, params :: Nil, _, Some(body))) :: Nil, Closure(meth, _))
      if ddef.symbol == meth.symbol =>
        Some(params, body)

      case _ => None
    }

    def apply(tpe: MethodType, rhsFn: List[Tree] => Tree)(using ctx: Context): Block =
      internal.Lambda_apply(tpe, rhsFn)

  }

  given (using ctx: Context) as IsInstanceOf[If] = internal.isInstanceOfIf

  /** Scala `if`/`else` term */
  object If {

    /** Create an if/then/else `if (<cond: Term>) <thenp: Term> else <elsep: Term>` */
    def apply(cond: Term, thenp: Term, elsep: Term)(using ctx: Context): If =
      internal.If_apply(cond, thenp, elsep)

    def copy(original: Tree)(cond: Term, thenp: Term, elsep: Term)(using ctx: Context): If =
      internal.If_copy(original)(cond, thenp, elsep)

    /** Matches an if/then/else `if (<cond: Term>) <thenp: Term> else <elsep: Term>` */
    def unapply(tree: If)(using ctx: Context): Option[(Term, Term, Term)] =
      Some((tree.cond, tree.thenp, tree.elsep))

  }

  extension IfOps on (self: If) {
    def cond(using ctx: Context): Term = internal.If_cond(self)
    def thenp(using ctx: Context): Term = internal.If_thenp(self)
    def elsep(using ctx: Context): Term = internal.If_elsep(self)
  }

  given (using ctx: Context) as IsInstanceOf[Match] = internal.isInstanceOfMatch

  /** Scala `match` term */
  object Match {

    /** Creates a pattern match `<scrutinee: Term> match { <cases: List[CaseDef]> }` */
    def apply(selector: Term, cases: List[CaseDef])(using ctx: Context): Match =
      internal.Match_apply(selector, cases)

    def copy(original: Tree)(selector: Term, cases: List[CaseDef])(using ctx: Context): Match =
      internal.Match_copy(original)(selector, cases)

    /** Matches a pattern match `<scrutinee: Term> match { <cases: List[CaseDef]> }` */
    def unapply(x: Match)(using ctx: Context): Option[(Term, List[CaseDef])] =
      Some((x.scrutinee, x.cases))

  }

  extension MatchOps on (self: Match) {
    def scrutinee(using ctx: Context): Term = internal.Match_scrutinee(self)
    def cases(using ctx: Context): List[CaseDef] = internal.Match_cases(self)
  }

  given (using ctx: Context) as IsInstanceOf[GivenMatch] = internal.isInstanceOfGivenMatch

  /** Scala implicit `match` term */

  object GivenMatch {

    /** Creates a pattern match `given match { <cases: List[CaseDef]> }` */
    def apply(cases: List[CaseDef])(using ctx: Context): GivenMatch =
      internal.GivenMatch_apply(cases)

    def copy(original: Tree)(cases: List[CaseDef])(using ctx: Context): GivenMatch =
      internal.GivenMatch_copy(original)(cases)

    /** Matches a pattern match `given match { <cases: List[CaseDef]> }` */
    def unapply(x: GivenMatch)(using ctx: Context): Option[List[CaseDef]] = Some(x.cases)

  }

  extension GivenMatchOps on (self: GivenMatch) {
    def cases(using ctx: Context): List[CaseDef] = internal.GivenMatch_cases(self)
  }

  given (using ctx: Context) as IsInstanceOf[Try] = internal.isInstanceOfTry

  /** Scala `try`/`catch`/`finally` term */
  object Try {

    /** Create a try/catch `try <body: Term> catch { <cases: List[CaseDef]> } finally <finalizer: Option[Term]>` */
    def apply(expr: Term, cases: List[CaseDef], finalizer: Option[Term])(using ctx: Context): Try =
      internal.Try_apply(expr, cases, finalizer)

    def copy(original: Tree)(expr: Term, cases: List[CaseDef], finalizer: Option[Term])(using ctx: Context): Try =
      internal.Try_copy(original)(expr, cases, finalizer)

    /** Matches a try/catch `try <body: Term> catch { <cases: List[CaseDef]> } finally <finalizer: Option[Term]>` */
    def unapply(x: Try)(using ctx: Context): Option[(Term, List[CaseDef], Option[Term])] =
      Some((x.body, x.cases, x.finalizer))

  }

  extension TryOps on (self: Try) {
    def body(using ctx: Context): Term = internal.Try_body(self)
    def cases(using ctx: Context): List[CaseDef] = internal.Try_cases(self)
    def finalizer(using ctx: Context): Option[Term] = internal.Try_finalizer(self)
  }

  given (using ctx: Context) as IsInstanceOf[Return] = internal.isInstanceOfReturn

  /** Scala local `return` */
  object Return {

    /** Creates `return <expr: Term>` */
    def apply(expr: Term)(using ctx: Context): Return =
      internal.Return_apply(expr)

    def copy(original: Tree)(expr: Term)(using ctx: Context): Return =
      internal.Return_copy(original)(expr)

    /** Matches `return <expr: Term>` */
    def unapply(x: Return)(using ctx: Context): Option[Term] = Some(x.expr)

  }

  extension ReturnOps on (self: Return) {
    def expr(using ctx: Context): Term = internal.Return_expr(self)
  }

  given (using ctx: Context) as IsInstanceOf[Repeated] = internal.isInstanceOfRepeated

  object Repeated {

    def apply(elems: List[Term], tpt: TypeTree)(using ctx: Context): Repeated =
      internal.Repeated_apply(elems, tpt)

    def copy(original: Tree)(elems: List[Term], tpt: TypeTree)(using ctx: Context): Repeated =
      internal.Repeated_copy(original)(elems, tpt)

    def unapply(x: Repeated)(using ctx: Context): Option[(List[Term], TypeTree)] =
      Some((x.elems, x.elemtpt))

  }

  extension RepeatedOps on (self: Repeated) {
    def elems(using ctx: Context): List[Term] = internal.Repeated_elems(self)
    def elemtpt(using ctx: Context): TypeTree = internal.Repeated_elemtpt(self)
  }

  given (using ctx: Context) as IsInstanceOf[Inlined] = internal.isInstanceOfInlined

  object Inlined {

    def apply(call: Option[Tree /* Term | TypeTree */], bindings: List[Definition], expansion: Term)(using ctx: Context): Inlined =
      internal.Inlined_apply(call, bindings, expansion)

    def copy(original: Tree)(call: Option[Tree /* Term | TypeTree */], bindings: List[Definition], expansion: Term)(using ctx: Context): Inlined =
      internal.Inlined_copy(original)(call, bindings, expansion)

    def unapply(x: Inlined)(using ctx: Context): Option[(Option[Tree /* Term | TypeTree */], List[Definition], Term)] =
      Some((x.call, x.bindings, x.body))

  }

  extension InlinedOps on (self: Inlined) {
    def call(using ctx: Context): Option[Tree /* Term | TypeTree */] = internal.Inlined_call(self)
    def bindings(using ctx: Context): List[Definition] = internal.Inlined_bindings(self)
    def body(using ctx: Context): Term = internal.Inlined_body(self)
  }

  given (using ctx: Context) as IsInstanceOf[SelectOuter] = internal.isInstanceOfSelectOuter

  object SelectOuter {

    def apply(qualifier: Term, name: String, levels: Int)(using ctx: Context): SelectOuter =
      internal.SelectOuter_apply(qualifier, name, levels)

    def copy(original: Tree)(qualifier: Term, name: String, levels: Int)(using ctx: Context): SelectOuter =
      internal.SelectOuter_copy(original)(qualifier, name, levels)

    def unapply(x: SelectOuter)(using ctx: Context): Option[(Term, Int, Type)] = // TODO homogenize order of parameters
      Some((x.qualifier, x.level, x.tpe))

  }

  extension SelectOuterOps on (self: SelectOuter) {
    def qualifier(using ctx: Context): Term = internal.SelectOuter_qualifier(self)
    def level(using ctx: Context): Int = internal.SelectOuter_level(self)
  }

  given (using ctx: Context) as IsInstanceOf[While] = internal.isInstanceOfWhile

  object While {

    /** Creates a while loop `while (<cond>) <body>` and returns (<cond>, <body>) */
    def apply(cond: Term, body: Term)(using ctx: Context): While =
      internal.While_apply(cond, body)

    def copy(original: Tree)(cond: Term, body: Term)(using ctx: Context): While =
      internal.While_copy(original)(cond, body)

    /** Extractor for while loops. Matches `while (<cond>) <body>` and returns (<cond>, <body>) */
    def unapply(x: While)(using ctx: Context): Option[(Term, Term)] =
      Some((x.cond, x.body))

  }

  extension WhileOps on (self: While) {
    def cond(using ctx: Context): Term = internal.While_cond(self)
    def body(using ctx: Context): Term = internal.While_body(self)
  }

  // ----- TypeTrees ------------------------------------------------

  extension TypeTreeOps on (self: TypeTree) {
    /** Type of this type tree */
    def tpe(using ctx: Context): Type = internal.TypeTree_tpe(self)
  }

  given (using ctx: Context) as IsInstanceOf[TypeTree] =
    internal.isInstanceOfTypeTree

  given (using ctx: Context) as IsInstanceOf[Inferred] = internal.isInstanceOfInferred

  /** TypeTree containing an inferred type */
  object Inferred {
    def apply(tpe: Type)(using ctx: Context): Inferred =
      internal.Inferred_apply(tpe)
    /** Matches a TypeTree containing an inferred type */
    def unapply(x: Inferred)(using ctx: Context): Boolean = true
  }

  given (using ctx: Context) as IsInstanceOf[TypeIdent] = internal.isInstanceOfTypeIdent

  extension TypeIdentOps on (self: TypeIdent) {
    def name(using ctx: Context): String = internal.TypeIdent_name(self)
  }

  object TypeIdent {
    def apply(sym: Symbol)(using ctx: Context): TypeTree =
      internal.TypeRef_apply(sym)
    def copy(original: Tree)(name: String)(using ctx: Context): TypeIdent =
      internal.TypeIdent_copy(original)(name)
    def unapply(x: TypeIdent)(using ctx: Context): Option[String] = Some(x.name)
  }

  given (using ctx: Context) as IsInstanceOf[TypeSelect] = internal.isInstanceOfTypeSelect

  object TypeSelect {
    def apply(qualifier: Term, name: String)(using ctx: Context): TypeSelect =
      internal.TypeSelect_apply(qualifier, name)
    def copy(original: Tree)(qualifier: Term, name: String)(using ctx: Context): TypeSelect =
      internal.TypeSelect_copy(original)(qualifier, name)
    def unapply(x: TypeSelect)(using ctx: Context): Option[(Term, String)] =
      Some((x.qualifier, x.name))
  }

  extension TypeSelectOps on (self: TypeSelect) {
    def qualifier(using ctx: Context): Term = internal.TypeSelect_qualifier(self)
    def name(using ctx: Context): String = internal.TypeSelect_name(self)
  }

  given (using ctx: Context) as IsInstanceOf[Projection] = internal.isInstanceOfProjection

  object Projection {
    // TODO def apply(qualifier: TypeTree, name: String)(using ctx: Context): Project
    def copy(original: Tree)(qualifier: TypeTree, name: String)(using ctx: Context): Projection =
      internal.Projection_copy(original)(qualifier, name)
    def unapply(x: Projection)(using ctx: Context): Option[(TypeTree, String)] =
      Some((x.qualifier, x.name))
  }

  extension ProjectionOps on (self: Projection) {
    def qualifier(using ctx: Context): TypeTree = internal.Projection_qualifier(self)
    def name(using ctx: Context): String = internal.Projection_name(self)
  }

  given (using ctx: Context) as IsInstanceOf[Singleton] = internal.isInstanceOfSingleton

  object Singleton {
    def apply(ref: Term)(using ctx: Context): Singleton =
      internal.Singleton_apply(ref)
    def copy(original: Tree)(ref: Term)(using ctx: Context): Singleton =
      internal.Singleton_copy(original)(ref)
    def unapply(x: Singleton)(using ctx: Context): Option[Term] =
      Some(x.ref)
  }

  extension SingletonOps on (self: Singleton) {
    def ref(using ctx: Context): Term = internal.Singleton_ref(self)
  }

  given (using ctx: Context) as IsInstanceOf[Refined] = internal.isInstanceOfRefined

  object Refined {
    // TODO def apply(tpt: TypeTree, refinements: List[Definition])(using ctx: Context): Refined
    def copy(original: Tree)(tpt: TypeTree, refinements: List[Definition])(using ctx: Context): Refined =
      internal.Refined_copy(original)(tpt, refinements)
    def unapply(x: Refined)(using ctx: Context): Option[(TypeTree, List[Definition])] =
      Some((x.tpt, x.refinements))
  }

  extension RefinedOps on (self: Refined) {
    def tpt(using ctx: Context): TypeTree = internal.Refined_tpt(self)
    def refinements(using ctx: Context): List[Definition] = internal.Refined_refinements(self)
  }

  given (using ctx: Context) as IsInstanceOf[Applied] = internal.isInstanceOfApplied

  object Applied {
    def apply(tpt: TypeTree, args: List[Tree /*TypeTree | TypeBoundsTree*/])(using ctx: Context): Applied =
      internal.Applied_apply(tpt, args)
    def copy(original: Tree)(tpt: TypeTree, args: List[Tree /*TypeTree | TypeBoundsTree*/])(using ctx: Context): Applied =
      internal.Applied_copy(original)(tpt, args)
    def unapply(x: Applied)(using ctx: Context): Option[(TypeTree, List[Tree /*TypeTree | TypeBoundsTree*/])] =
      Some((x.tpt, x.args))
  }

  extension AppliedOps on (self: Applied) {
    def tpt(using ctx: Context): TypeTree = internal.Applied_tpt(self)
    def args(using ctx: Context): List[Tree /*TypeTree | TypeBoundsTree*/] = internal.Applied_args(self)
  }

  given (using ctx: Context) as IsInstanceOf[Annotated] =
    internal.isInstanceOfAnnotated

  object Annotated {
    def apply(arg: TypeTree, annotation: Term)(using ctx: Context): Annotated =
      internal.Annotated_apply(arg, annotation)
    def copy(original: Tree)(arg: TypeTree, annotation: Term)(using ctx: Context): Annotated =
      internal.Annotated_copy(original)(arg, annotation)
    def unapply(x: Annotated)(using ctx: Context): Option[(TypeTree, Term)] =
      Some((x.arg, x.annotation))
  }

  extension AnnotatedOps on (self: Annotated) {
    def arg(using ctx: Context): TypeTree = internal.Annotated_arg(self)
    def annotation(using ctx: Context): Term = internal.Annotated_annotation(self)
  }

  given (using ctx: Context) as IsInstanceOf[MatchTypeTree] =
    internal.isInstanceOfMatchTypeTree

  object MatchTypeTree {
    def apply(bound: Option[TypeTree], selector: TypeTree, cases: List[TypeCaseDef])(using ctx: Context): MatchTypeTree =
      internal.MatchTypeTree_apply(bound, selector, cases)
    def copy(original: Tree)(bound: Option[TypeTree], selector: TypeTree, cases: List[TypeCaseDef])(using ctx: Context): MatchTypeTree =
      internal.MatchTypeTree_copy(original)(bound, selector, cases)
    def unapply(x: MatchTypeTree)(using ctx: Context): Option[(Option[TypeTree], TypeTree, List[TypeCaseDef])] =
      Some((x.bound, x.selector, x.cases))
  }

  extension MatchTypeTreeOps on (self: MatchTypeTree) {
    def bound(using ctx: Context): Option[TypeTree] = internal.MatchTypeTree_bound(self)
    def selector(using ctx: Context): TypeTree = internal.MatchTypeTree_selector(self)
    def cases(using ctx: Context): List[TypeCaseDef] = internal.MatchTypeTree_cases(self)
  }

  given (using ctx: Context) as IsInstanceOf[ByName] =
    internal.isInstanceOfByName

  object ByName {
    def apply(result: TypeTree)(using ctx: Context): ByName =
      internal.ByName_apply(result)
    def copy(original: Tree)(result: TypeTree)(using ctx: Context): ByName =
      internal.ByName_copy(original)(result)
    def unapply(x: ByName)(using ctx: Context): Option[TypeTree] =
      Some(x.result)
  }

  extension ByNameOps on (self: ByName) {
    def result(using ctx: Context): TypeTree = internal.ByName_result(self)
  }

  given (using ctx: Context) as IsInstanceOf[LambdaTypeTree] = internal.isInstanceOfLambdaTypeTree

  object LambdaTypeTree {
    def apply(tparams: List[TypeDef], body: Tree /*TypeTree | TypeBoundsTree*/)(using ctx: Context): LambdaTypeTree =
      internal.Lambdaapply(tparams, body)
    def copy(original: Tree)(tparams: List[TypeDef], body: Tree /*TypeTree | TypeBoundsTree*/)(using ctx: Context): LambdaTypeTree =
      internal.Lambdacopy(original)(tparams, body)
    def unapply(tree: LambdaTypeTree)(using ctx: Context): Option[(List[TypeDef], Tree /*TypeTree | TypeBoundsTree*/)] =
      Some((tree.tparams, tree.body))
  }

  extension LambdaTypeTreeOps on (self: LambdaTypeTree) {
    def tparams(using ctx: Context): List[TypeDef] = internal.Lambdatparams(self)
    def body(using ctx: Context): Tree /*TypeTree | TypeBoundsTree*/ = internal.Lambdabody(self)
  }

  given (using ctx: Context) as IsInstanceOf[TypeBind] = internal.isInstanceOfTypeBind

  object TypeBind {
    // TODO def apply(name: String, tree: Tree)(using ctx: Context): TypeBind
    def copy(original: Tree)(name: String, tpt: Tree /*TypeTree | TypeBoundsTree*/)(using ctx: Context): TypeBind =
      internal.TypeBind_copy(original)(name, tpt)
    def unapply(x: TypeBind)(using ctx: Context): Option[(String, Tree /*TypeTree | TypeBoundsTree*/)] =
      Some((x.name, x.body))
  }

  extension TypeBindOps on (self: TypeBind) {
    def name(using ctx: Context): String = internal.TypeBind_name(self)
    def body(using ctx: Context): Tree /*TypeTree | TypeBoundsTree*/ = internal.TypeBind_body(self)
  }

  given (using ctx: Context) as IsInstanceOf[TypeBlock] = internal.isInstanceOfTypeBlock

  object TypeBlock {
    def apply(aliases: List[TypeDef], tpt: TypeTree)(using ctx: Context): TypeBlock =
      internal.TypeBlock_apply(aliases, tpt)
    def copy(original: Tree)(aliases: List[TypeDef], tpt: TypeTree)(using ctx: Context): TypeBlock =
      internal.TypeBlock_copy(original)(aliases, tpt)
    def unapply(x: TypeBlock)(using ctx: Context): Option[(List[TypeDef], TypeTree)] =
      Some((x.aliases, x.tpt))
  }

  extension TypeBlockOps on (self: TypeBlock) {
    def aliases(using ctx: Context): List[TypeDef] = internal.TypeBlock_aliases(self)
    def tpt(using ctx: Context): TypeTree = internal.TypeBlock_tpt(self)
  }

  // ----- TypeBoundsTrees ------------------------------------------------

  extension TypeBoundsTreeOps on (self: TypeBoundsTree) {
    def tpe(using ctx: Context): TypeBounds = internal.TypeBoundsTree_tpe(self)
    def low(using ctx: Context): TypeTree = internal.TypeBoundsTree_low(self)
    def hi(using ctx: Context): TypeTree = internal.TypeBoundsTree_hi(self)
  }

  given (using ctx: Context) as IsInstanceOf[TypeBoundsTree] = internal.isInstanceOfTypeBoundsTree

  object TypeBoundsTree {
    def unapply(x: TypeBoundsTree)(using ctx: Context): Option[(TypeTree, TypeTree)] =
      Some((x.low, x.hi))
  }

  extension WildcardTypeTreeOps on (self: WildcardTypeTree) {
    def tpe(using ctx: Context): TypeOrBounds = internal.WildcardTypeTree_tpe(self)
  }

  given (using ctx: Context) as IsInstanceOf[WildcardTypeTree] = internal.isInstanceOfWildcardTypeTree

  object WildcardTypeTree {
    /** Matches a TypeBoundsTree containing wildcard type bounds */
    def unapply(x: WildcardTypeTree)(using ctx: Context): Boolean = true
  }

  // ----- CaseDefs ------------------------------------------------

  extension CaseDefOps on (caseDef: CaseDef) {
    def pattern(using ctx: Context): Tree = internal.CaseDef_pattern(caseDef)
    def guard(using ctx: Context): Option[Term] = internal.CaseDef_guard(caseDef)
    def rhs(using ctx: Context): Term = internal.CaseDef_rhs(caseDef)
  }

  given (using ctx: Context) as IsInstanceOf[CaseDef] = internal.isInstanceOfCaseDef

  object CaseDef {
    def apply(pattern: Tree, guard: Option[Term], rhs: Term)(using ctx: Context): CaseDef =
      internal.CaseDef_module_apply(pattern, guard, rhs)

    def copy(original: Tree)(pattern: Tree, guard: Option[Term], rhs: Term)(using ctx: Context): CaseDef =
      internal.CaseDef_module_copy(original)(pattern, guard, rhs)

    def unapply(x: CaseDef)(using ctx: Context): Option[(Tree, Option[Term], Term)] =
      Some((x.pattern, x.guard, x.rhs))
  }

  extension TypeCaseDefOps on (caseDef: TypeCaseDef) {
    def pattern(using ctx: Context): TypeTree = internal.TypeCaseDef_pattern(caseDef)
    def rhs(using ctx: Context): TypeTree = internal.TypeCaseDef_rhs(caseDef)
  }

  given (using ctx: Context) as IsInstanceOf[TypeCaseDef] =
    internal.isInstanceOfTypeCaseDef

  object TypeCaseDef {
    def apply(pattern: TypeTree, rhs: TypeTree)(using ctx: Context): TypeCaseDef =
      internal.TypeCaseDef_module_apply(pattern, rhs)

    def copy(original: Tree)(pattern: TypeTree, rhs: TypeTree)(using ctx: Context): TypeCaseDef =
      internal.TypeCaseDef_module_copy(original)(pattern, rhs)

    def unapply(tree: TypeCaseDef)(using ctx: Context): Option[(TypeTree, TypeTree)] =
      Some((tree.pattern, tree.rhs))
  }

  // ----- Patterns ------------------------------------------------

  given (using ctx: Context) as IsInstanceOf[Bind] = internal.isInstanceOfBind

  object Bind {
    // TODO def apply(name: String, pattern: Tree)(using ctx: Context): Bind
    def copy(original: Tree)(name: String, pattern: Tree)(using ctx: Context): Bind =
      internal.Tree_Bind_module_copy(original)(name, pattern)
    def unapply(pattern: Bind)(using ctx: Context): Option[(String, Tree)] =
      Some((pattern.name, pattern.pattern))
  }

  extension BindOps on (bind: Bind) {
    def name(using ctx: Context): String = internal.Tree_Bind_name(bind)
    def pattern(using ctx: Context): Tree = internal.Tree_Bind_pattern(bind)
  }

  given (using ctx: Context) as IsInstanceOf[Unapply] = internal.isInstanceOfUnapply

  object Unapply {
    // TODO def apply(fun: Term, implicits: List[Term], patterns: List[Tree])(using ctx: Context): Unapply
    def copy(original: Tree)(fun: Term, implicits: List[Term], patterns: List[Tree])(using ctx: Context): Unapply =
      internal.Tree_Unapply_module_copy(original)(fun, implicits, patterns)
    def unapply(x: Unapply)(using ctx: Context): Option[(Term, List[Term], List[Tree])] =
      Some((x.fun, x.implicits, x.patterns))
  }

  extension UnapplyOps on (unapply: Unapply) {
    def fun(using ctx: Context): Term = internal.Tree_Unapply_fun(unapply)
    def implicits(using ctx: Context): List[Term] = internal.Tree_Unapply_implicits(unapply)
    def patterns(using ctx: Context): List[Tree] = internal.Tree_Unapply_patterns(unapply)
  }

  given (using ctx: Context) as IsInstanceOf[Alternatives] = internal.isInstanceOfAlternatives

  object Alternatives {
    def apply(patterns: List[Tree])(using ctx: Context): Alternatives =
      internal.Tree_Alternatives_module_apply(patterns)
    def copy(original: Tree)(patterns: List[Tree])(using ctx: Context): Alternatives =
      internal.Tree_Alternatives_module_copy(original)(patterns)
    def unapply(x: Alternatives)(using ctx: Context): Option[List[Tree]] =
      Some(x.patterns)
  }

  extension AlternativesOps on (alternatives: Alternatives) {
    def patterns(using ctx: Context): List[Tree] = internal.Tree_Alternatives_patterns(alternatives)
  }


  //////////////////////
  // IMPORT SELECTORS //
  /////////////////////

  extension simpleSelectorOps on (self: SimpleSelector) {
    def selection(using ctx: Context): Id =
      internal.SimpleSelector_selection(self)
  }

  given (using ctx: Context) as IsInstanceOf[SimpleSelector] = internal.isInstanceOfSimpleSelector

  object SimpleSelector:
    def unapply(x: SimpleSelector)(using ctx: Context): Option[Id] = Some(x.selection)

  extension renameSelectorOps on (self: RenameSelector):
    def from(using ctx: Context): Id =
      internal.RenameSelector_from(self)

    def to(using ctx: Context): Id =
      internal.RenameSelector_to(self)

  given (using ctx: Context) as IsInstanceOf[RenameSelector] = internal.isInstanceOfRenameSelector

  object RenameSelector:
    def unapply(x: RenameSelector)(using ctx: Context): Option[(Id, Id)] = Some((x.from, x.to))

  extension omitSelectorOps on (self: OmitSelector):
    def omitted(using ctx: Context): Id =
      internal.SimpleSelector_omitted(self)

  given (using ctx: Context) as IsInstanceOf[OmitSelector] = internal.isInstanceOfOmitSelector

  object OmitSelector:
    def unapply(x: OmitSelector)(using ctx: Context): Option[Id] = Some(x.omitted)


  ///////////////
  //   TYPES   //
  ///////////////

  /** Returns the type (Type) of T */
  def typeOf[T](using qtype: scala.quoted.Type[T], ctx: Context): Type =
    internal.QuotedType_unseal(qtype).tpe

  /** Members of `TypeOrBounds` */
  extension TypeOrBoundsOps on (tpe: TypeOrBounds) {
    /** Shows the tree as extractors */
    def showExtractors(using ctx: Context): String =
      new ExtractorsPrinter[self.type](self).showTypeOrBounds(tpe)

    /** Shows the tree as fully typed source code */
    def show(using ctx: Context): String =
      tpe.showWith(SyntaxHighlight.plain)

    /** Shows the tree as fully typed source code */
    def showWith(syntaxHighlight: SyntaxHighlight)(using ctx: Context): String =
      new SourceCodePrinter[self.type](self)(syntaxHighlight).showTypeOrBounds(tpe)
  }

  // ----- Types ----------------------------------------------------

  extension TypeOps on (self: Type) {

    /** Convert `Type` to an `quoted.Type[_]` */
    def seal(using ctx: Context): scala.quoted.Type[_] =
      internal.QuotedType_seal(self)

    /** Is `self` type the same as `that` type?
     *  This is the case iff `self <:< that` and `that <:< self`.
     */
    def =:=(that: Type)(using ctx: Context): Boolean = internal.Type_isTypeEq(self)(that)

    /** Is this type a subtype of that type? */
    def <:<(that: Type)(using ctx: Context): Boolean = internal.Type_isSubType(self)(that)

    /** Widen from singleton type to its underlying non-singleton
     *  base type by applying one or more `underlying` dereferences,
     *  Also go from => T to T.
     *  Identity for all other types. Example:
     *
     *  class Outer { class C ; val x: C }
     *  def o: Outer
     *  <o.x.type>.widen = o.C
     */
    def widen(using ctx: Context): Type = internal.Type_widen(self)

    /** Widen from TermRef to its underlying non-termref
     *  base type, while also skipping `=>T` types.
     */
    def widenTermRefExpr(using ctx: Context): Type = internal.Type_widenTermRefExpr(self)

    /** Follow aliases and dereferences LazyRefs, annotated types and instantiated
     *  TypeVars until type is no longer alias type, annotated type, LazyRef,
     *  or instantiated type variable.
     */
    def dealias(using ctx: Context): Type = internal.Type_dealias(self)

    /** A simplified version of this type which is equivalent wrt =:= to this type.
     *  Reduces typerefs, applied match types, and and or types.
     */
    def simplified(using ctx: Context): Type = internal.Type_simplified(self)

    def classSymbol(using ctx: Context): Option[Symbol] = internal.Type_classSymbol(self)
    def typeSymbol(using ctx: Context): Symbol = internal.Type_typeSymbol(self)
    def termSymbol(using ctx: Context): Symbol = internal.Type_termSymbol(self)
    def isSingleton(using ctx: Context): Boolean = internal.Type_isSingleton(self)
    def memberType(member: Symbol)(using ctx: Context): Type = internal.Type_memberType(self)(member)

    /** Is this type an instance of a non-bottom subclass of the given class `cls`? */
    def derivesFrom(cls: Symbol)(using ctx: Context): Boolean =
      internal.Type_derivesFrom(self)(cls)

    /** Is this type a function type?
     *
     *  @return true if the dealised type of `self` without refinement is `FunctionN[T1, T2, ..., Tn]`
     *
     *  @note The function
     *
     *     - returns true for `given Int => Int` and `erased Int => Int`
     *     - returns false for `List[Int]`, despite that `List[Int] <:< Int => Int`.
     */
    def isFunctionType(using ctx: Context): Boolean = internal.Type_isFunctionType(self)

    /** Is this type an context function type?
     *
     *  @see `isFunctionType`
     */
    def isContextFunctionType(using ctx: Context): Boolean = internal.Type_isContextFunctionType(self)

    /** Is this type an erased function type?
     *
     *  @see `isFunctionType`
     */
    def isErasedFunctionType(using ctx: Context): Boolean = internal.Type_isErasedFunctionType(self)

    /** Is this type a dependent function type?
     *
     *  @see `isFunctionType`
     */
    def isDependentFunctionType(using ctx: Context): Boolean = internal.Type_isDependentFunctionType(self)
  }

  given (using ctx: Context) as IsInstanceOf[Type] = internal.isInstanceOfType

  object Type {
    def apply(clazz: Class[_])(using ctx: Context): Type =
      internal.Type_apply(clazz)
  }

  given (using ctx: Context) as IsInstanceOf[ConstantType] = internal.isInstanceOfConstantType

  object ConstantType {
    def apply(x : Constant)(using ctx: Context): ConstantType = internal.ConstantType_apply(x)
    def unapply(x: ConstantType)(using ctx: Context): Option[Constant] = Some(x.constant)
  }

  extension ConstantTypeOps on (self: ConstantType) {
    def constant(using ctx: Context): Constant = internal.ConstantType_constant(self)
  }

  given (using ctx: Context) as IsInstanceOf[TermRef] = internal.isInstanceOfTermRef

  object TermRef {
    def apply(qual: TypeOrBounds, name: String)(using ctx: Context): TermRef =
      internal.TermRef_apply(qual, name)
    def unapply(x: TermRef)(using ctx: Context): Option[(TypeOrBounds /* Type | NoPrefix */, String)] =
      Some((x.qualifier, x.name))
  }

  extension TermRefOps on (self: TermRef) {
    def qualifier(using ctx: Context): TypeOrBounds /* Type | NoPrefix */ = internal.TermRef_qualifier(self)
    def name(using ctx: Context): String = internal.TermRef_name(self)
  }

  given (using ctx: Context) as IsInstanceOf[TypeRef] = internal.isInstanceOfTypeRef

  object TypeRef {
    def unapply(x: TypeRef)(using ctx: Context): Option[(TypeOrBounds /* Type | NoPrefix */, String)] =
      Some((x.qualifier, x.name))
  }

  extension TypeRefOps on (self: TypeRef) {
    def qualifier(using ctx: Context): TypeOrBounds /* Type | NoPrefix */ = internal.TypeRef_qualifier(self)
    def name(using ctx: Context): String = internal.TypeRef_name(self)
    def isOpaqueAlias(using ctx: Context): Boolean = internal.TypeRef_isOpaqueAlias(self)
    def translucentSuperType(using ctx: Context): Type = internal.TypeRef_translucentSuperType(self)
  }

  given (using ctx: Context) as IsInstanceOf[SuperType] = internal.isInstanceOfSuperType

  object SuperType {
    def apply(thistpe: Type, supertpe: Type)(using ctx: Context): SuperType =
      internal.SuperType_apply(thistpe, supertpe)

    def unapply(x: SuperType)(using ctx: Context): Option[(Type, Type)] =
      Some((x.thistpe, x.supertpe))
  }

  extension SuperTypeOps on (self: SuperType) {
    def thistpe(using ctx: Context): Type = internal.SuperType_thistpe(self)
    def supertpe(using ctx: Context): Type = internal.SuperType_supertpe(self)
  }

  given (using ctx: Context) as IsInstanceOf[Refinement] = internal.isInstanceOfRefinement

  object Refinement {
    def apply(parent: Type, name: String, info: TypeOrBounds /* Type | TypeBounds */)(using ctx: Context): Refinement =
      internal.Refinement_apply(parent, name, info)

    def unapply(x: Refinement)(using ctx: Context): Option[(Type, String, TypeOrBounds /* Type | TypeBounds */)] =
      Some((x.parent, x.name, x.info))
  }

  extension RefinementOps on (self: Refinement) {
    def parent(using ctx: Context): Type = internal.Refinement_parent(self)
    def name(using ctx: Context): String = internal.Refinement_name(self)
    def info(using ctx: Context): TypeOrBounds = internal.Refinement_info(self)
  }

  given (using ctx: Context) as IsInstanceOf[AppliedType] = internal.isInstanceOfAppliedType

  object AppliedType {
    def apply(tycon: Type, args: List[TypeOrBounds])(using ctx: Context): AppliedType =
      internal.AppliedType_apply(tycon, args)
    def unapply(x: AppliedType)(using ctx: Context): Option[(Type, List[TypeOrBounds /* Type | TypeBounds */])] =
      Some((x.tycon, x.args))
  }

  extension AppliedTypeOps on (self: AppliedType) {
    def tycon(using ctx: Context): Type = internal.AppliedType_tycon(self)
    def args(using ctx: Context): List[TypeOrBounds /* Type | TypeBounds */] = internal.AppliedType_args(self)
  }

  given (using ctx: Context) as IsInstanceOf[AnnotatedType] = internal.isInstanceOfAnnotatedType

  object AnnotatedType {
    def apply(underlying: Type, annot: Term)(using ctx: Context): AnnotatedType =
      internal.AnnotatedType_apply(underlying, annot)
    def unapply(x: AnnotatedType)(using ctx: Context): Option[(Type, Term)] =
      Some((x.underlying, x.annot))
  }

  extension AnnotatedTypeOps on (self: AnnotatedType) {
    def underlying(using ctx: Context): Type = internal.AnnotatedType_underlying(self)
    def annot(using ctx: Context): Term = internal.AnnotatedType_annot(self)
  }

  given (using ctx: Context) as IsInstanceOf[AndType] = internal.isInstanceOfAndType

  object AndType {
    def apply(lhs: Type, rhs: Type)(using ctx: Context): AndType =
      internal.AndType_apply(lhs, rhs)
    def unapply(x: AndType)(using ctx: Context): Option[(Type, Type)] =
      Some((x.left, x.right))
  }

  extension AndTypeOps on (self: AndType) {
    def left(using ctx: Context): Type = internal.AndType_left(self)
    def right(using ctx: Context): Type = internal.AndType_right(self)
  }

  given (using ctx: Context) as IsInstanceOf[OrType] = internal.isInstanceOfOrType

  object OrType {
    def apply(lhs: Type, rhs: Type)(using ctx: Context): OrType = internal.OrType_apply(lhs, rhs)
    def unapply(x: OrType)(using ctx: Context): Option[(Type, Type)] =
      Some((x.left, x.right))
  }

  extension OrTypeOps on (self: OrType) {
    def left(using ctx: Context): Type = internal.OrType_left(self)
    def right(using ctx: Context): Type = internal.OrType_right(self)
  }

  given (using ctx: Context) as IsInstanceOf[MatchType] = internal.isInstanceOfMatchType

  object MatchType {
    def apply(bound: Type, scrutinee: Type, cases: List[Type])(using ctx: Context): MatchType =
      internal.MatchType_apply(bound, scrutinee, cases)
    def unapply(x: MatchType)(using ctx: Context): Option[(Type, Type, List[Type])] =
      Some((x.bound, x.scrutinee, x.cases))
  }

  extension MatchTypeOps on (self: MatchType) {
    def bound(using ctx: Context): Type = internal.MatchType_bound(self)
    def scrutinee(using ctx: Context): Type = internal.MatchType_scrutinee(self)
    def cases(using ctx: Context): List[Type] = internal.MatchType_cases(self)
  }

  /**
   * An accessor for `scala.internal.MatchCase[_,_]`, the representation of a `MatchType` case.
   */
  def MatchCaseType(using ctx: Context): Type = {
    import scala.internal.MatchCase
    Type(classOf[MatchCase[_,_]])
  }

  given (using ctx: Context) as IsInstanceOf[ByNameType] = internal.isInstanceOfByNameType

  object ByNameType {
    def apply(underlying: Type)(using ctx: Context): Type = internal.ByNameType_apply(underlying)
    def unapply(x: ByNameType)(using ctx: Context): Option[Type] = Some(x.underlying)
  }

  extension ByNameTypeOps on (self: ByNameType) {
    def underlying(using ctx: Context): Type = internal.ByNameType_underlying(self)
  }

  given (using ctx: Context) as IsInstanceOf[ParamRef] = internal.isInstanceOfParamRef

  object ParamRef {
    def unapply(x: ParamRef)(using ctx: Context): Option[(LambdaType[TypeOrBounds], Int)] =
      Some((x.binder, x.paramNum))
  }

  extension ParamRefOps on (self: ParamRef) {
    def binder(using ctx: Context): LambdaType[TypeOrBounds] = internal.ParamRef_binder(self)
    def paramNum(using ctx: Context): Int = internal.ParamRef_paramNum(self)
  }

  given (using ctx: Context) as IsInstanceOf[ThisType] = internal.isInstanceOfThisType

  object ThisType {
    def unapply(x: ThisType)(using ctx: Context): Option[Type] = Some(x.tref)
  }

  extension ThisTypeOps on (self: ThisType) {
    def tref(using ctx: Context): Type = internal.ThisType_tref(self)
  }

  given (using ctx: Context) as IsInstanceOf[RecursiveThis] = internal.isInstanceOfRecursiveThis

  object RecursiveThis {
    def unapply(x: RecursiveThis)(using ctx: Context): Option[RecursiveType] = Some(x.binder)
  }

  extension RecursiveThisOps on (self: RecursiveThis) {
    def binder(using ctx: Context): RecursiveType = internal.RecursiveThis_binder(self)
  }

  given (using ctx: Context) as IsInstanceOf[RecursiveType] = internal.isInstanceOfRecursiveType

  object RecursiveType {

    /** Create a RecType, normalizing its contents. This means:
     *
     *   1. Nested Rec types on the type's spine are merged with the outer one.
     *   2. Any refinement of the form `type T = z.T` on the spine of the type
     *      where `z` refers to the created rec-type is replaced by
     *      `type T`. This avoids infinite recursions later when we
     *      try to follow these references.
     */
    def apply(parentExp: RecursiveType => Type)(using ctx: Context): RecursiveType =
      internal.RecursiveType_apply(parentExp)

    def unapply(x: RecursiveType)(using ctx: Context): Option[Type] = Some(x.underlying)

  }

  extension RecursiveTypeOps on (self: RecursiveType) {
    def underlying(using ctx: Context): Type = internal.RecursiveType_underlying(self)
    def recThis(using ctx: Context): RecursiveThis = internal.RecursiveThis_recThis(self)
  }

  given (using ctx: Context) as IsInstanceOf[MethodType] = internal.isInstanceOfMethodType

  object MethodType {
    def apply(paramNames: List[String])(paramInfosExp: MethodType => List[Type], resultTypeExp: MethodType => Type): MethodType =
      internal.MethodType_apply(paramNames)(paramInfosExp, resultTypeExp)

    def unapply(x: MethodType)(using ctx: Context): Option[(List[String], List[Type], Type)] =
      Some((x.paramNames, x.paramTypes, x.resType))
  }

  extension MethodTypeOps on (self: MethodType) {
    def isImplicit: Boolean = internal.MethodType_isImplicit(self)
    def isErased: Boolean = internal.MethodType_isErased(self)
    def param(idx: Int)(using ctx: Context): Type = internal.MethodType_param(self, idx)
    def paramNames(using ctx: Context): List[String] = internal.MethodType_paramNames(self)
    def paramTypes(using ctx: Context): List[Type] = internal.MethodType_paramTypes(self)
    def resType(using ctx: Context): Type = internal.MethodType_resType(self)
  }

  given (using ctx: Context) as IsInstanceOf[PolyType] = internal.isInstanceOfPolyType

  object PolyType {
    def apply(paramNames: List[String])(paramBoundsExp: PolyType => List[TypeBounds], resultTypeExp: PolyType => Type)(using ctx: Context): PolyType =
      internal.PolyType_apply(paramNames)(paramBoundsExp, resultTypeExp)
    def unapply(x: PolyType)(using ctx: Context): Option[(List[String], List[TypeBounds], Type)] =
      Some((x.paramNames, x.paramBounds, x.resType))
  }

  extension PolyTypeOps on (self: PolyType) {
    def param(idx: Int)(using ctx: Context): Type = internal.PolyType_param(self, idx)
    def paramNames(using ctx: Context): List[String] = internal.PolyType_paramNames(self)
    def paramBounds(using ctx: Context): List[TypeBounds] = internal.PolyType_paramBounds(self)
    def resType(using ctx: Context): Type = internal.PolyType_resType(self)
  }

  given (using ctx: Context) as IsInstanceOf[TypeLambda] = internal.isInstanceOfTypeLambda

  object TypeLambda {
    def apply(paramNames: List[String], boundsFn: TypeLambda => List[TypeBounds], bodyFn: TypeLambda => Type): TypeLambda =
      internal.TypeLambda_apply(paramNames, boundsFn, bodyFn)
    def unapply(x: TypeLambda)(using ctx: Context): Option[(List[String], List[TypeBounds], Type)] =
      Some((x.paramNames, x.paramBounds, x.resType))
  }

  extension TypeLambdaOps on (self: TypeLambda) {
    def paramNames(using ctx: Context): List[String] = internal.TypeLambda_paramNames(self)
    def paramBounds(using ctx: Context): List[TypeBounds] = internal.TypeLambda_paramBounds(self)
    def param(idx: Int)(using ctx: Context) : Type = internal.TypeLambda_param(self, idx)
    def resType(using ctx: Context): Type = internal.TypeLambda_resType(self)
  }

  // ----- TypeBounds -----------------------------------------------

  given (using ctx: Context) as IsInstanceOf[TypeBounds] = internal.isInstanceOfTypeBounds

  object TypeBounds {
    def apply(low: Type, hi: Type)(using ctx: Context): TypeBounds =
      internal.TypeBounds_apply(low, hi)
    def unapply(x: TypeBounds)(using ctx: Context): Option[(Type, Type)] = Some((x.low, x.hi))
  }

  extension TypeBoundsOps on (self: TypeBounds) {
    def low(using ctx: Context): Type = internal.TypeBounds_low(self)
    def hi(using ctx: Context): Type = internal.TypeBounds_hi(self)
  }

  // ----- NoPrefix -------------------------------------------------

  given (using ctx: Context) as IsInstanceOf[NoPrefix] = internal.isInstanceOfNoPrefix

  object NoPrefix:
    def unapply(x: NoPrefix)(using ctx: Context): Boolean = true


  ///////////////
  // CONSTANTS //
  ///////////////

  /** Members of `Constant` */
  extension ConstantOps on (const: Constant) {

    /** Returns the value of the constant */
    def value: Any = internal.Constant_value(const)

    /** Shows the tree as extractors */
    def showExtractors(using ctx: Context): String =
      new ExtractorsPrinter[self.type](self).showConstant(const)

    /** Shows the tree as fully typed source code */
    def show(using ctx: Context): String =
      const.showWith(SyntaxHighlight.plain)

    /** Shows the tree as fully typed source code */
    def showWith(syntaxHighlight: SyntaxHighlight)(using ctx: Context): String =
      new SourceCodePrinter[self.type](self)(syntaxHighlight).showConstant(const)
  }

  /** Module of Constant literals */
  object Constant {

    def apply(x: Unit | Null | Int | Boolean | Byte | Short | Int | Long | Float | Double | Char | String | Type): Constant =
      internal.Constant_apply(x)

    def unapply(constant: Constant): Option[Unit | Null | Int | Boolean | Byte | Short | Int | Long | Float | Double | Char | String | Type] =
      internal.matchConstant(constant)

    /** Module of ClassTag literals */
    object ClassTag {
      /** scala.reflect.ClassTag literal */
      def apply[T](using x: Type): Constant =
        internal.Constant_ClassTag_apply(x)

      /** Extractor for ClassTag literals */
      def unapply(constant: Constant): Option[Type] =
        internal.matchConstant_ClassTag(constant)
    }
  }


  /////////
  // IDs //
  /////////

  extension IdOps on (id: Id) {

    /** Position in the source code */
    def pos(using ctx: Context): Position = internal.Id_pos(id)

    /** Name of the identifier */
    def name(using ctx: Context): String = internal.Id_name(id)

  }

  object Id {
    def unapply(id: Id)(using ctx: Context): Option[String] = Some(id.name)
  }


  /////////////////////
  // IMPLICIT SEARCH //
  /////////////////////

  def searchImplicit(tpe: Type)(using ctx: Context): ImplicitSearchResult =
    internal.searchImplicit(tpe)

  given (using ctx: Context) as IsInstanceOf[ImplicitSearchSuccess] = internal.isInstanceOfImplicitSearchSuccess

  extension successOps on (self: ImplicitSearchSuccess) {
    def tree(using ctx: Context): Term = internal.ImplicitSearchSuccess_tree(self)
  }

  given (using ctx: Context) as IsInstanceOf[ImplicitSearchFailure] = internal.isInstanceOfImplicitSearchFailure

  extension failureOps on (self: ImplicitSearchFailure) {
    def explanation(using ctx: Context): String = internal.ImplicitSearchFailure_explanation(self)
  }

  given (using ctx: Context) as IsInstanceOf[DivergingImplicit] = internal.isInstanceOfDivergingImplicit

  given (using ctx: Context) as IsInstanceOf[NoMatchingImplicits] = internal.isInstanceOfNoMatchingImplicits

  given (using ctx: Context) as IsInstanceOf[AmbiguousImplicits] = internal.isInstanceOfAmbiguousImplicits


  /////////////
  // SYMBOLS //
  /////////////

  object Symbol {
    /** The class Symbol of a global class definition */
    def classSymbol(fullName: String)(using ctx: Context): Symbol =
      internal.Symbol_of(fullName)

    /** Generates a new method symbol with the given parent, name and type.
     *
     *  This symbol starts without an accompanying definition.
     *  It is the meta-programmer's responsibility to provide exactly one corresponding definition by passing
     *  this symbol to the DefDef constructor.
     *
     *  @note As a macro can only splice code into the point at which it is expanded, all generated symbols must be
     *        direct or indirect children of the reflection context's owner.
     */
    def newMethod(parent: Symbol, name: String, tpe: Type)(using ctx: Context): Symbol =
      newMethod(parent, name, tpe, Flags.EmptyFlags, noSymbol)

    /** Works as the other newMethod, but with additional parameters.
     *
     *  @param flags extra flags to with which the symbol should be constructed
     *  @param privateWithin the symbol within which this new method symbol should be private. May be noSymbol.
     */
    def newMethod(parent: Symbol, name: String, tpe: Type, flags: Flags, privateWithin: Symbol)(using ctx: Context): Symbol =
      internal.Symbol_newMethod(parent, name, flags, tpe, privateWithin)

    /** Generates a new val/var/lazy val symbol with the given parent, name and type.
     *
     *  This symbol starts without an accompanying definition.
     *  It is the meta-programmer's responsibility to provide exactly one corresponding definition by passing
     *  this symbol to the ValDef constructor.
     *
     *  Note: Also see Reflection.let
     *
     *  @param flags extra flags to with which the symbol should be constructed
     *  @param privateWithin the symbol within which this new method symbol should be private. May be noSymbol.
     *  @note As a macro can only splice code into the point at which it is expanded, all generated symbols must be
     *        direct or indirect children of the reflection context's owner.
     */
    def newVal(parent: Symbol, name: String, tpe: Type, flags: Flags, privateWithin: Symbol)(using ctx: Context): Symbol =
      internal.Symbol_newVal(parent, name, flags, tpe, privateWithin)

    /** Definition not available */
    def noSymbol(using ctx: Context): Symbol =
      internal.Symbol_noSymbol
  }

  /** Members of `Symbol` */
  extension SymbolOps on (sym: Symbol) {

    /** Owner of this symbol. The owner is the symbol in which this symbol is defined. Throws if this symbol does not have an owner. */
    def owner(using ctx: Context): Symbol = internal.Symbol_owner(sym)

    /** Owner of this symbol. The owner is the symbol in which this symbol is defined. Returns `NoSymbol` if this symbol does not have an owner. */
    def maybeOwner(using ctx: Context): Symbol = internal.Symbol_maybeOwner(sym)

    /** Flags of this symbol */
    def flags(using ctx: Context): Flags = internal.Symbol_flags(sym)

    /** This symbol is private within the resulting type */
    def privateWithin(using ctx: Context): Option[Type] = internal.Symbol_privateWithin(sym)

    /** This symbol is protected within the resulting type */
    def protectedWithin(using ctx: Context): Option[Type] = internal.Symbol_protectedWithin(sym)

    /** The name of this symbol */
    def name(using ctx: Context): String = internal.Symbol_name(sym)

    /** The full name of this symbol up to the root package */
    def fullName(using ctx: Context): String = internal.Symbol_fullName(sym)

    /** The position of this symbol */
    def pos(using ctx: Context): Position = internal.Symbol_pos(sym)

    def localContext(using ctx: Context): Context = internal.Symbol_localContext(sym)

    /** The comment for this symbol, if any */
    def comment(using ctx: Context): Option[Comment] = internal.Symbol_comment(sym)

    /** Tree of this definition
     *
     * if this symbol `isPackageDef` it will return a `PackageDef`,
     * if this symbol `isClassDef` it will return a `ClassDef`,
     * if this symbol `isTypeDef` it will return a `TypeDef`,
     * if this symbol `isValDef` it will return a `ValDef`,
     * if this symbol `isDefDef` it will return a `DefDef`
     * if this symbol `isBind` it will return a `Bind`
     */
    def tree(using ctx: Context): Tree =
      internal.Symbol_tree(sym)

    /** Annotations attached to this symbol */
    def annots(using ctx: Context): List[Term] = internal.Symbol_annots(sym)

    def isDefinedInCurrentRun(using ctx: Context): Boolean = internal.Symbol_isDefinedInCurrentRun(sym)

    def isLocalDummy(using ctx: Context): Boolean = internal.Symbol_isLocalDummy(sym)
    def isRefinementClass(using ctx: Context): Boolean = internal.Symbol_isRefinementClass(sym)
    def isAliasType(using ctx: Context): Boolean = internal.Symbol_isAliasType(sym)
    def isAnonymousClass(using ctx: Context): Boolean = internal.Symbol_isAnonymousClass(sym)
    def isAnonymousFunction(using ctx: Context): Boolean = internal.Symbol_isAnonymousFunction(sym)
    def isAbstractType(using ctx: Context): Boolean = internal.Symbol_isAbstractType(sym)
    def isClassConstructor(using ctx: Context): Boolean = internal.Symbol_isClassConstructor(sym)

    /** Is this the definition of a type? */
    def isType(using ctx: Context): Boolean = internal.Symbol_isType(sym)

    /** Is this the definition of a term? */
    def isTerm(using ctx: Context): Boolean = internal.Symbol_isTerm(sym)

    /** Is this the definition of a PackageDef tree? */
    def isPackageDef(using ctx: Context): Boolean = internal.Symbol_isPackageDef(sym)

    /** Is this the definition of a ClassDef tree? */
    def isClassDef(using ctx: Context): Boolean = internal.Symbol_isClassDef(sym)

    /** Is this the definition of a TypeDef tree */
    def isTypeDef(using ctx: Context): Boolean = internal.Symbol_isTypeDef(sym)

    /** Is this the definition of a ValDef tree? */
    def isValDef(using ctx: Context): Boolean = internal.Symbol_isValDef(sym)

    /** Is this the definition of a DefDef tree? */
    def isDefDef(using ctx: Context): Boolean = internal.Symbol_isDefDef(sym)

    /** Is this the definition of a Bind pattern? */
    def isBind(using ctx: Context): Boolean = internal.Symbol_isBind(sym)

    /** Does this symbol represent a no definition? */
    def isNoSymbol(using ctx: Context): Boolean = sym == Symbol.noSymbol

    /** Does this symbol represent a definition? */
    def exists(using ctx: Context): Boolean = sym != Symbol.noSymbol

    /** Fields directly declared in the class */
    def fields(using ctx: Context): List[Symbol] =
      internal.Symbol_fields(sym)

    /** Field with the given name directly declared in the class */
    def field(name: String)(using ctx: Context): Symbol =
      internal.Symbol_field(sym)(name)

    /** Get non-private named methods defined directly inside the class */
    def classMethod(name: String)(using ctx: Context): List[Symbol] =
      internal.Symbol_classMethod(sym)(name)

    /** Get all non-private methods defined directly inside the class, exluding constructors */
    def classMethods(using ctx: Context): List[Symbol] =
      internal.Symbol_classMethods(sym)

    /** Get named non-private methods declared or inherited */
    def method(name: String)(using ctx: Context): List[Symbol] =
      internal.Symbol_method(sym)(name)

    /** Get all non-private methods declared or inherited */
    def methods(using ctx: Context): List[Symbol] =
      internal.Symbol_methods(sym)

    /** The symbols of each type parameter list and value parameter list of this
     *  method, or Nil if this isn't a method.
     */
    def paramSymss(using ctx: Context): List[List[Symbol]] =
      internal.Symbol_paramSymss(sym)

    /** The primary constructor of a class or trait, `noSymbol` if not applicable. */
    def primaryConstructor(using Context): Symbol =
      internal.Symbol_primaryConstructor(sym)

    /** Fields of a case class type -- only the ones declared in primary constructor */
    def caseFields(using ctx: Context): List[Symbol] =
      internal.Symbol_caseFields(sym)

    def isTypeParam(using ctx: Context): Boolean =
      internal.Symbol_isTypeParam(sym)

    /** Signature of this definition */
    def signature(using ctx: Context): Signature =
      internal.Symbol_signature(sym)

    /** The class symbol of the companion module class */
    def moduleClass(using ctx: Context): Symbol =
      internal.Symbol_moduleClass(sym)

    /** The symbol of the companion class */
    def companionClass(using ctx: Context): Symbol =
      internal.Symbol_companionClass(sym)

    /** The symbol of the companion module */
    def companionModule(using ctx: Context): Symbol =
      internal.Symbol_companionModule(sym)

    /** Shows the tree as extractors */
    def showExtractors(using ctx: Context): String =
      new ExtractorsPrinter[self.type](self).showSymbol(sym)

    /** Shows the tree as fully typed source code */
    def show(using ctx: Context): String =
      sym.showWith(SyntaxHighlight.plain)

    /** Shows the tree as fully typed source code */
    def showWith(syntaxHighlight: SyntaxHighlight)(using ctx: Context): String =
      new SourceCodePrinter[self.type](self)(syntaxHighlight).showSymbol(sym)

    /** Case class or case object children of a sealed trait */
    def children(using ctx: Context): List[Symbol] =
      internal.Symbol_children(sym)
  }


  ////////////////
  // SIGNATURES //
  ////////////////

  /** The signature of a method */
  object Signature {
    /** Matches the method signature and returns its parameters and result type. */
    def unapply(sig: Signature)(using ctx: Context): Option[(List[String | Int], String)] =
      Some((sig.paramSigs, sig.resultSig))
  }

  extension signatureOps on (sig: Signature) {

    /** The signatures of the method parameters.
     *
     *  Each *type parameter section* is represented by a single Int corresponding
     *  to the number of type parameters in the section.
     *  Each *term parameter* is represented by a String corresponding to the fully qualified
     *  name of the parameter type.
     */
    def paramSigs: List[String | Int] = internal.Signature_paramSigs(sig)

    /** The signature of the result type */
    def resultSig: String = internal.Signature_resultSig(sig)

  }

  //////////////////////////
  // STANDARD DEFINITIONS //
  //////////////////////////

  /** A value containing all standard definitions in [[DefinitionsAPI]]
   *  @group Definitions
   */
  object defn extends StandardSymbols with StandardTypes

  /** Defines standard symbols (and types via its base trait).
   *  @group API
   */
  trait StandardSymbols {

    /** The module symbol of root package `_root_`. */
    def RootPackage: Symbol = internal.Definitions_RootPackage

    /** The class symbol of root package `_root_`. */
    def RootClass: Symbol = internal.Definitions_RootClass

    /** The class symbol of empty package `_root_._empty_`. */
    def EmptyPackageClass: Symbol = internal.Definitions_EmptyPackageClass

    /** The module symbol of package `scala`. */
    def ScalaPackage: Symbol = internal.Definitions_ScalaPackage

    /** The class symbol of package `scala`. */
    def ScalaPackageClass: Symbol = internal.Definitions_ScalaPackageClass

    /** The class symbol of core class `scala.Any`. */
    def AnyClass: Symbol = internal.Definitions_AnyClass

    /** The class symbol of core class `scala.AnyVal`. */
    def AnyValClass: Symbol = internal.Definitions_AnyValClass

    /** The class symbol of core class `java.lang.Object`. */
    def ObjectClass: Symbol = internal.Definitions_ObjectClass

    /** The type symbol of core class `scala.AnyRef`. */
    def AnyRefClass: Symbol = internal.Definitions_AnyRefClass

    /** The class symbol of core class `scala.Null`. */
    def NullClass: Symbol = internal.Definitions_NullClass

    /** The class symbol of core class `scala.Nothing`. */
    def NothingClass: Symbol = internal.Definitions_NothingClass

    /** The class symbol of primitive class `scala.Unit`. */
    def UnitClass: Symbol = internal.Definitions_UnitClass

    /** The class symbol of primitive class `scala.Byte`. */
    def ByteClass: Symbol = internal.Definitions_ByteClass

    /** The class symbol of primitive class `scala.Short`. */
    def ShortClass: Symbol = internal.Definitions_ShortClass

    /** The class symbol of primitive class `scala.Char`. */
    def CharClass: Symbol = internal.Definitions_CharClass

    /** The class symbol of primitive class `scala.Int`. */
    def IntClass: Symbol = internal.Definitions_IntClass

    /** The class symbol of primitive class `scala.Long`. */
    def LongClass: Symbol = internal.Definitions_LongClass

    /** The class symbol of primitive class `scala.Float`. */
    def FloatClass: Symbol = internal.Definitions_FloatClass

    /** The class symbol of primitive class `scala.Double`. */
    def DoubleClass: Symbol = internal.Definitions_DoubleClass

    /** The class symbol of primitive class `scala.Boolean`. */
    def BooleanClass: Symbol = internal.Definitions_BooleanClass

    /** The class symbol of class `scala.String`. */
    def StringClass: Symbol = internal.Definitions_StringClass

    /** The class symbol of class `java.lang.Class`. */
    def ClassClass: Symbol = internal.Definitions_ClassClass

    /** The class symbol of class `scala.Array`. */
    def ArrayClass: Symbol = internal.Definitions_ArrayClass

    /** The module symbol of module `scala.Predef`. */
    def PredefModule: Symbol = internal.Definitions_PredefModule

    /** The method symbol of method `scala.Predef.classOf`. */
    def Predef_classOf: Symbol = internal.Definitions_Predef_classOf

    /** The module symbol of package `java.lang`. */
    def JavaLangPackage: Symbol = internal.Definitions_JavaLangPackage

    /** The module symbol of module `scala.Array`. */
    def ArrayModule: Symbol = internal.Definitions_ArrayModule

    /** The method symbol of method `apply` in class `scala.Array`. */
    def Array_apply: Symbol = internal.Definitions_Array_apply

    /** The method symbol of method `clone` in class `scala.Array`. */
    def Array_clone: Symbol = internal.Definitions_Array_clone

    /** The method symbol of method `length` in class `scala.Array`. */
    def Array_length: Symbol = internal.Definitions_Array_length

    /** The method symbol of method `update` in class `scala.Array`. */
    def Array_update: Symbol = internal.Definitions_Array_update

    /** A dummy class symbol that is used to indicate repeated parameters
     *  compiled by the Scala compiler.
     */
    def RepeatedParamClass: Symbol = internal.Definitions_RepeatedParamClass

    /** The class symbol of class `scala.Option`. */
    def OptionClass: Symbol = internal.Definitions_OptionClass

    /** The module symbol of module `scala.None`. */
    def NoneModule: Symbol = internal.Definitions_NoneModule

    /** The module symbol of module `scala.Some`. */
    def SomeModule: Symbol = internal.Definitions_SomeModule

    /** Function-like object that maps arity to symbols for classes `scala.Product` */
    def ProductClass: Symbol = internal.Definitions_ProductClass

    /** Function-like object that maps arity to symbols for classes `scala.FunctionX`.
     *   -  0th element is `Function0`
     *   -  1st element is `Function1`
     *   -  ...
     *   -  Nth element is `FunctionN`
     */
    def FunctionClass(arity: Int, isImplicit: Boolean = false, isErased: Boolean = false): Symbol =
      internal.Definitions_FunctionClass(arity, isImplicit, isErased)

    /** Function-like object that maps arity to symbols for classes `scala.TupleX`.
     *   -  0th element is `NoSymbol`
     *   -  1st element is `NoSymbol`
     *   -  2st element is `Tuple2`
     *   -  ...
     *   - 22nd element is `Tuple22`
     *   - 23nd element is `NoSymbol`  // TODO update when we will have more tuples
     *   - ...
     */
    def TupleClass(arity: Int): Symbol =
      internal.Definitions_TupleClass(arity)

    /** Returns `true` if `sym` is a `Tuple1`, `Tuple2`, ... `Tuple22` */
    def isTupleClass(sym: Symbol): Boolean =
      internal.Definitions_isTupleClass(sym)

    /** Contains Scala primitive value classes:
     *   - Byte
     *   - Short
     *   - Int
     *   - Long
     *   - Float
     *   - Double
     *   - Char
     *   - Boolean
     *   - Unit
     */
    def ScalaPrimitiveValueClasses: List[Symbol] =
      UnitClass :: BooleanClass :: ScalaNumericValueClasses

    /** Contains Scala numeric value classes:
     *   - Byte
     *   - Short
     *   - Int
     *   - Long
     *   - Float
     *   - Double
     *   - Char
     */
    def ScalaNumericValueClasses: List[Symbol] =
      ByteClass :: ShortClass :: IntClass :: LongClass :: FloatClass :: DoubleClass :: CharClass :: Nil

  }

  /** Defines standard types.
   *  @group Definitions
   */
  trait StandardTypes {
    /** The type of primitive type `Unit`. */
    def UnitType: Type = internal.Definitions_UnitType

    /** The type of primitive type `Byte`. */
    def ByteType: Type = internal.Definitions_ByteType

    /** The type of primitive type `Short`. */
    def ShortType: Type = internal.Definitions_ShortType

    /** The type of primitive type `Char`. */
    def CharType: Type = internal.Definitions_CharType

    /** The type of primitive type `Int`. */
    def IntType: Type = internal.Definitions_IntType

    /** The type of primitive type `Long`. */
    def LongType: Type = internal.Definitions_LongType

    /** The type of primitive type `Float`. */
    def FloatType: Type = internal.Definitions_FloatType

    /** The type of primitive type `Double`. */
    def DoubleType: Type = internal.Definitions_DoubleType

    /** The type of primitive type `Boolean`. */
    def BooleanType: Type = internal.Definitions_BooleanType

    /** The type of core type `Any`. */
    def AnyType: Type = internal.Definitions_AnyType

    /** The type of core type `AnyVal`. */
    def AnyValType: Type = internal.Definitions_AnyValType

    /** The type of core type `AnyRef`. */
    def AnyRefType: Type = internal.Definitions_AnyRefType

    /** The type of core type `Object`. */
    def ObjectType: Type = internal.Definitions_ObjectType

    /** The type of core type `Nothing`. */
    def NothingType: Type = internal.Definitions_NothingType

    /** The type of core type `Null`. */
    def NullType: Type = internal.Definitions_NullType

    /** The type for `scala.String`. */
    def StringType: Type = internal.Definitions_StringType
  }


  ///////////////
  //   FLAGS   //
  ///////////////

  /** Members of `Flag` */
  extension FlagsOps on (flags: Flags) {

    /** Is the given flag set a subset of this flag sets */
    def is(that: Flags): Boolean = internal.Flags_is(flags)(that)

    /** Union of the two flag sets */
    def |(that: Flags): Flags = internal.Flags_or(flags)(that)

    /** Intersection of the two flag sets */
    def &(that: Flags): Flags = internal.Flags_and(flags)(that)

    /** Shows the tree as extractors */
    def showExtractors(using ctx: Context): String =
      new ExtractorsPrinter[self.type](self).showFlags(flags)

    /** Shows the tree as fully typed source code */
    def show(using ctx: Context): String =
      flags.showWith(SyntaxHighlight.plain)

    /** Shows the tree as fully typed source code */
    def showWith(syntaxHighlight: SyntaxHighlight)(using ctx: Context): String =
      new SourceCodePrinter[self.type](self)(syntaxHighlight).showFlags(flags)

  }

  object Flags {

    /** The empty set of flags */
    def EmptyFlags = internal.Flags_EmptyFlags

    /** Is this symbol `private` */
    def Private: Flags = internal.Flags_Private

    /** Is this symbol `protected` */
    def Protected: Flags = internal.Flags_Protected

    /** Is this symbol `abstract` */
    def Abstract: Flags = internal.Flags_Abstract

    /** Is this symbol `final` */
    def Final: Flags = internal.Flags_Final

    /** Is this symbol `sealed` */
    def Sealed: Flags = internal.Flags_Sealed

    /** Is this symbol `case` */
    def Case: Flags = internal.Flags_Case

    /** Is this symbol `implicit` */
    def Implicit: Flags = internal.Flags_Implicit

    /** Is this symbol an inferable ("given") parameter */
    def Given: Flags = internal.Flags_Given

     /** Is this symbol `erased` */
    def Erased: Flags = internal.Flags_Erased

    /** Is this symbol `lazy` */
    def Lazy: Flags = internal.Flags_Lazy

    /** Is this symbol `override` */
    def Override: Flags = internal.Flags_Override

    /** Is this symbol `inline` */
    def Inline: Flags = internal.Flags_Inline

    /** Is this symbol marked as a macro. An inline method containing toplevel splices */
    def Macro: Flags = internal.Flags_Macro

    /** Is this symbol marked as static. Mapped to static Java member */
    def Static: Flags = internal.Flags_Static

    /** Is this symbol defined in a Java class */
    def JavaDefined: Flags = internal.Flags_JavaDefined

    /** Is this symbol an object or its class (used for a ValDef or a ClassDef extends Modifier respectively) */
    def Object: Flags = internal.Flags_Object

    /** Is this symbol a trait */
    def Trait: Flags = internal.Flags_Trait

    /** Is this symbol local? Used in conjunction with private/private[Type] to mean private[this] extends Modifier proctected[this] */
    def Local: Flags = internal.Flags_Local

    /** Was this symbol generated by Scala compiler */
    def Synthetic: Flags = internal.Flags_Synthetic

    /** Is this symbol to be tagged Java Synthetic */
    def Artifact: Flags = internal.Flags_Artifact

    /** Is this symbol a `var` (when used on a ValDef) */
    def Mutable: Flags = internal.Flags_Mutable

    /** Is this symbol a getter or a setter */
    def FieldAccessor: Flags = internal.Flags_FieldAccessor

    /** Is this symbol a getter for case class parameter */
    def CaseAcessor: Flags = internal.Flags_CaseAcessor

    /** Is this symbol a type parameter marked as covariant `+` */
    def Covariant: Flags = internal.Flags_Covariant

    /** Is this symbol a type parameter marked as contravariant `-` */
    def Contravariant: Flags = internal.Flags_Contravariant

    /** Was this symbol imported from Scala2.x */
    def Scala2X: Flags = internal.Flags_Scala2X

    /** Is this symbol a parameter with a default value? */
    def HasDefault: Flags = internal.Flags_HasDefault

    /** Is this symbol member that is assumed to be stable and realizable */
    def StableRealizable: Flags = internal.Flags_StableRealizable

    /** Is this symbol a parameter */
    def Param: Flags = internal.Flags_Param

    /** Is this symbol a parameter accessor */
    def ParamAccessor: Flags = internal.Flags_ParamAccessor

    /** Is this symbol an enum */
    def Enum: Flags = internal.Flags_Enum

    /** Is this symbol a module class */
    def ModuleClass: Flags = internal.Flags_ModuleClass

    /** Is this symbol labeled private[this] */
    def PrivateLocal: Flags = internal.Flags_PrivateLocal

    /** Is this symbol a package */
    def Package: Flags = internal.Flags_Package
  }


  ///////////////
  // POSITIONS //
  ///////////////

  // TODO: Should this be in the QuoteContext?
  // TODO: rename to enclosingPosition (as in scala.reflect)
  /** Root position of this tasty context. For macros it corresponds to the expansion site. */
  def rootPosition: Position = internal.rootPosition

  extension positionOps on (pos: Position) {

    /** The start offset in the source file */
    def start: Int = internal.Position_start(pos)

    /** The end offset in the source file */
    def end: Int = internal.Position_end(pos)

    /** Does this position exist */
    def exists: Boolean = internal.Position_exists(pos)

    /** Source file in which this position is located */
    def sourceFile: SourceFile = internal.Position_sourceFile(pos)

    /** The start line in the source file */
    def startLine: Int = internal.Position_startLine(pos)

    /** The end line in the source file */
    def endLine: Int = internal.Position_endLine(pos)

    /** The start column in the source file */
    def startColumn: Int = internal.Position_startColumn(pos)

    /** The end column in the source file */
    def endColumn: Int = internal.Position_endColumn(pos)

    /** Source code within the position */
    def sourceCode: String = internal.Position_sourceCode(pos)

  }

  extension sourceFileOps on (sourceFile: SourceFile) {

    /** Path to this source file */
    def jpath: java.nio.file.Path = internal.SourceFile_jpath(sourceFile)

    /** Content of this source file */
    def content: String = internal.SourceFile_content(sourceFile)

  }


  ///////////////
  // REPORTING //
  ///////////////

  /** Emits an error message */
  def error(msg: => String, pos: Position)(using ctx: Context): Unit =
    internal.error(msg, pos)

  /** Emits an error at a specific range of a file */
  def error(msg: => String, source: SourceFile, start: Int, end: Int)(using ctx: Context): Unit =
    internal.error(msg, source, start, end)

  /** Emits an error message */
  def warning(msg: => String, pos: Position)(using ctx: Context): Unit =
    internal.warning(msg, pos)

  /** Emits a warning at a specific range of a file */
  def warning(msg: => String, source: SourceFile, start: Int, end: Int)(using ctx: Context): Unit =
    internal.warning(msg, source, start, end)


  //////////////
  // COMMENTS //
  //////////////

  extension CommentOps on (self: Comment) {

    /** Raw comment string */
    def raw: String = internal.Comment_raw(self)

    /** Expanded comment string, if any */
    def expanded: Option[String] = internal.Comment_expanded(self)

    /** List of usecases and their corresponding trees, if any */
    def usecases: List[(String, Option[DefDef])] = internal.Comment_usecases(self)

  }


  ///////////////
  //   UTILS   //
  ///////////////

  /** TASTy Reflect tree accumulator */
  trait TreeAccumulator[X] extends reflect.TreeAccumulator[X] {
    val reflect: self.type = self
  }

  /** TASTy Reflect tree traverser */
  trait TreeTraverser extends reflect.TreeTraverser {
    val reflect: self.type = self
  }

  /** TASTy Reflect tree map */
  trait TreeMap extends reflect.TreeMap {
    val reflect: self.type = self
  }

  // TODO extract from Reflection

  /** Bind the `rhs` to a `val` and use it in `body` */
  def let(rhs: Term)(body: Ident => Term)(using ctx: Context): Term = {
    val sym = Symbol.newVal(ctx.owner, "x", rhs.tpe.widen, Flags.EmptyFlags, Symbol.noSymbol)
    Block(List(ValDef(sym, Some(rhs))), body(Ref(sym).asInstanceOf[Ident]))
  }

  /** Bind the given `terms` to names and use them in the `body` */
  def lets(terms: List[Term])(body: List[Term] => Term)(using ctx: Context): Term = {
    def rec(xs: List[Term], acc: List[Term]): Term = xs match {
      case Nil => body(acc)
      case x :: xs => let(x) { (x: Term) => rec(xs, x :: acc) }
    }
    rec(terms, Nil)
  }

}
