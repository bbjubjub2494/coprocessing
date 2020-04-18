package dotty.tools
package dotc
package typer

import core._
import ast._
import Trees._, StdNames._, Scopes._, Denotations._
import Contexts._, Symbols._, Types._, SymDenotations._, Names._, NameOps._, Flags._
import Decorators.{given _}, Comments.{_, given _}
import NameKinds.DefaultGetterName
import TypeApplications.TypeParamInfo
import ast.desugar, ast.desugar._
import ProtoTypes._
import util.Spans._
import util.Property
import collection.mutable
import tpd.ListOfTreeDecorator
import config.{Config, Feature}
import config.Printers.typr
import Annotations._
import Inferencing._
import transform.ValueClasses._
import transform.TypeUtils._
import transform.SymUtils._
import reporting.messages._
import config.Feature.sourceVersion
import config.SourceVersion._

trait NamerContextOps {
  thisCtx: Context =>

  import NamerContextOps._

  def typer: Typer = this.typeAssigner match {
    case typer: Typer => typer
    case _ => new Typer
  }

  /** Enter symbol into current class, if current class is owner of current context,
   *  or into current scope, if not. Should always be called instead of scope.enter
   *  in order to make sure that updates to class members are reflected in
   *  finger prints.
   */
  def enter(sym: Symbol): Symbol =
    thisCtx.owner match
      case cls: ClassSymbol => cls.enter(sym)
      case _ => thisCtx.scope.openForMutations.enter(sym)
    sym

  /** The denotation with the given `name` and all `required` flags in current context
   */
  def denotNamed(name: Name, required: FlagSet = EmptyFlags): Denotation =
    if (owner.isClass)
      if (outer.owner == owner) { // inner class scope; check whether we are referring to self
        if (scope.size == 1) {
          val elem = scope.lastEntry
          if (elem.name == name) return elem.sym.denot // return self
        }
        val pre = owner.thisType
        pre.findMember(name, pre, required, EmptyFlags)
      }
      else // we are in the outermost context belonging to a class; self is invisible here. See inClassContext.
        owner.findMember(name, owner.thisType, required, EmptyFlags)
    else
      scope.denotsNamed(name).filterWithFlags(required, EmptyFlags).toDenot(NoPrefix)

  /** Either the current scope, or, if the current context owner is a class,
   *  the declarations of the current class.
   */
  def effectiveScope: Scope =
    if (owner != null && owner.isClass) owner.asClass.unforcedDecls
    else scope

  /** The symbol (stored in some typer's symTree) of an enclosing context definition */
  def symOfContextTree(tree: untpd.Tree): Symbol = {
    def go(contxt: Context): Symbol =
      contxt.typeAssigner match {
        case typer: Typer =>
          tree.getAttachment(typer.SymOfTree) match {
            case Some(sym) => sym
            case None =>
              var cx = ctx.outer
              while (cx.typeAssigner eq typer) cx = cx.outer
              go(cx)
          }
        case _ => NoSymbol
      }
    go(this)
  }

  /** Context where `sym` is defined, assuming we are in a nested context. */
  def defContext(sym: Symbol): Context =
    outersIterator
      .dropWhile(_.owner != sym)
      .dropWhile(_.owner == sym)
      .next()

  /** A fresh local context with given tree and owner.
   *  Owner might not exist (can happen for self valdefs), in which case
   *  no owner is set in result context
   */
  def localContext(tree: untpd.Tree, owner: Symbol): FreshContext = {
    val freshCtx = fresh.setTree(tree)
    if (owner.exists) freshCtx.setOwner(owner) else freshCtx
  }

  /** A new context for the interior of a class */
  def inClassContext(selfInfo: TypeOrSymbol): Context = {
    val localCtx: Context = thisCtx.fresh.setNewScope
    selfInfo match {
      case sym: Symbol if sym.exists && sym.name != nme.WILDCARD => localCtx.scope.openForMutations.enter(sym)
      case _ =>
    }
    localCtx
  }

  def packageContext(tree: untpd.PackageDef, pkg: Symbol): Context =
    if (pkg.is(Package)) thisCtx.fresh.setOwner(pkg.moduleClass).setTree(tree)
    else thisCtx

  /** The given type, unless `sym` is a constructor, in which case the
   *  type of the constructed instance is returned
   */
  def effectiveResultType(sym: Symbol, typeParams: List[Symbol], givenTp: Type): Type =
    if (sym.name == nme.CONSTRUCTOR) sym.owner.typeRef.appliedTo(typeParams.map(_.typeRef))
    else givenTp

  /** if isConstructor, make sure it has one non-implicit parameter list */
  def normalizeIfConstructor(termParamss: List[List[Symbol]], isConstructor: Boolean): List[List[Symbol]] =
    if (isConstructor &&
      (termParamss.isEmpty || termParamss.head.nonEmpty && termParamss.head.head.isOneOf(GivenOrImplicit)))
      Nil :: termParamss
    else
      termParamss

  /** The method type corresponding to given parameters and result type */
  def methodType(typeParams: List[Symbol], valueParamss: List[List[Symbol]], resultType: Type, isJava: Boolean = false)(using Context): Type = {
    val monotpe =
      valueParamss.foldRight(resultType) { (params, resultType) =>
        val (isContextual, isImplicit, isErased) =
          if (params.isEmpty) (false, false, false)
          else (params.head.is(Given), params.head.is(Implicit), params.head.is(Erased))
        val make = MethodType.companion(isJava = isJava, isContextual = isContextual, isImplicit = isImplicit, isErased = isErased)
        if (isJava)
          for (param <- params)
            if (param.info.isDirectRef(defn.ObjectClass)) param.info = defn.AnyType
        make.fromSymbols(params, resultType)
      }
    if (typeParams.nonEmpty) PolyType.fromParams(typeParams.asInstanceOf[List[TypeSymbol]], monotpe)
    else if (valueParamss.isEmpty) ExprType(monotpe)
    else monotpe
  }

  /** Add moduleClass or sourceModule functionality to completer
   *  for a module or module class
   */
  def adjustModuleCompleter(completer: LazyType, name: Name): LazyType = {
    val scope = this.effectiveScope
    if (name.isTermName)
      completer withModuleClass (implicit ctx => findModuleBuddy(name.moduleClassName, scope))
    else
      completer withSourceModule (implicit ctx => findModuleBuddy(name.sourceModuleName, scope))
  }
}

object NamerContextOps {
  /** Find moduleClass/sourceModule in effective scope */
  private def findModuleBuddy(name: Name, scope: Scope)(using Context) = {
    val it = scope.lookupAll(name).filter(_.is(Module))
    if (it.hasNext) it.next()
    else NoSymbol.assertingErrorsReported(s"no companion $name in $scope")
  }
}

/** This class creates symbols from definitions and imports and gives them
 *  lazy types.
 *
 *  Timeline:
 *
 *  During enter, trees are expanded as necessary, populating the expandedTree map.
 *  Symbols are created, and the symOfTree map is set up.
 *
 *  Symbol completion causes some trees to be already typechecked and typedTree
 *  entries are created to associate the typed trees with the untyped expanded originals.
 *
 *  During typer, original trees are first expanded using expandedTree. For each
 *  expanded member definition or import we extract and remove the corresponding symbol
 *  from the symOfTree map and complete it. We then consult the typedTree map to see
 *  whether a typed tree exists already. If yes, the typed tree is returned as result.
 *  Otherwise, we proceed with regular type checking.
 *
 *  The scheme is designed to allow sharing of nodes, as long as each duplicate appears
 *  in a different method.
 */
class Namer { typer: Typer =>

  import untpd._

  val TypedAhead: Property.Key[tpd.Tree] = new Property.Key
  val ExpandedTree: Property.Key[untpd.Tree] = new Property.Key
  val ExportForwarders: Property.Key[List[tpd.MemberDef]] = new Property.Key
  val SymOfTree: Property.Key[Symbol] = new Property.Key
  val Deriver: Property.Key[typer.Deriver] = new Property.Key

  /** A partial map from unexpanded member and pattern defs and to their expansions.
   *  Populated during enterSyms, emptied during typer.
   */
  //lazy val expandedTree = new mutable.AnyRefMap[DefTree, Tree]
  /*{
    override def default(tree: DefTree) = tree // can't have defaults on AnyRefMaps :-(
  }*/

  /** A map from expanded MemberDef, PatDef or Import trees to their symbols.
   *  Populated during enterSyms, emptied at the point a typed tree
   *  with the same symbol is created (this can be when the symbol is completed
   *  or at the latest when the tree is typechecked.
   */
  //lazy val symOfTree = new mutable.AnyRefMap[Tree, Symbol]

  /** A map from expanded trees to their typed versions.
   *  Populated when trees are typechecked during completion (using method typedAhead).
   */
  // lazy val typedTree = new mutable.AnyRefMap[Tree, tpd.Tree]

  /** A map from method symbols to nested typers.
   *  Populated when methods are completed. Emptied when they are typechecked.
   *  The nested typer contains new versions of the four maps above including this
   *  one, so that trees that are shared between different DefDefs can be independently
   *  used as indices. It also contains a scope that contains nested parameters.
   */
  lazy val nestedTyper: mutable.AnyRefMap[Symbol, Typer] = new mutable.AnyRefMap

  /** The scope of the typer.
   *  For nested typers this is a place parameters are entered during completion
   *  and where they survive until typechecking. A context with this typer also
   *  has this scope.
   */
  val scope: MutableScope = newScope

  /** We are entering symbols coming from a SourceLoader */
  private var lateCompile = false

  /** The symbol of the given expanded tree. */
  def symbolOfTree(tree: Tree)(using Context): Symbol = {
    val xtree = expanded(tree)
    xtree.getAttachment(TypedAhead) match {
      case Some(ttree) => ttree.symbol
      case none => xtree.attachment(SymOfTree)
    }
  }

  /** The enclosing class with given name; error if none exists */
  def enclosingClassNamed(name: TypeName, span: Span)(using Context): Symbol =
    if (name.isEmpty) NoSymbol
    else {
      val cls = ctx.owner.enclosingClassNamed(name)
      if (!cls.exists)
        ctx.error(UnknownNamedEnclosingClassOrObject(name), ctx.source.atSpan(span))
      cls
    }

  /** Record `sym` as the symbol defined by `tree` */
  def recordSym(sym: Symbol, tree: Tree)(using Context): Symbol = {
    for (refs <- tree.removeAttachment(References); ref <- refs) ref.watching(sym)
    tree.pushAttachment(SymOfTree, sym)
    sym
  }

  /** Check that a new definition with given name and privacy status
   *  in current context would not conflict with existing currently
   *  compiled definitions.
   *  The logic here is very subtle and fragile due to the fact that
   *  we are not allowed to force anything.
   */
  def checkNoConflict(name: Name, isPrivate: Boolean, span: Span)(using Context): Name =
    val owner = ctx.owner
    var conflictsDetected = false

    def conflict(conflicting: Symbol) =
      val where: String =
        if conflicting.owner == owner then ""
        else if conflicting.owner.isPackageObject then i" in ${conflicting.associatedFile}"
        else i" in ${conflicting.owner}"
      ctx.error(i"$name is already defined as $conflicting$where", ctx.source.atSpan(span))
      conflictsDetected = true

    def checkNoConflictIn(owner: Symbol) =
      val preExisting = owner.unforcedDecls.lookup(name)
      if (preExisting.isDefinedInCurrentRun || preExisting.lastKnownDenotation.is(Package))
          && (!preExisting.lastKnownDenotation.is(Private) || preExisting.owner.is(Package))
      then conflict(preExisting)

    def pkgObjs(pkg: Symbol) =
      pkg.denot.asInstanceOf[PackageClassDenotation].packageObjs.map(_.symbol)

    if owner.is(PackageClass) then
      checkNoConflictIn(owner)
      for pkgObj <- pkgObjs(owner) do
        checkNoConflictIn(pkgObj)
    else
      def preExisting = ctx.effectiveScope.lookup(name)
      if (!owner.isClass || name.isTypeName) && preExisting.exists then
        conflict(preExisting)
      else if owner.isPackageObject && !isPrivate && name != nme.CONSTRUCTOR then
        checkNoConflictIn(owner.owner)
        for pkgObj <- pkgObjs(owner.owner) if pkgObj != owner do
          checkNoConflictIn(pkgObj)

    if conflictsDetected then name.freshened else name
  end checkNoConflict

  /** If this tree is a member def or an import, create a symbol of it
   *  and store in symOfTree map.
   */
  def createSymbol(tree: Tree)(using Context): Symbol = {

    def privateWithinClass(mods: Modifiers) =
      enclosingClassNamed(mods.privateWithin, tree.span)

    /** Check that flags are OK for symbol. This is done early to avoid
     *  catastrophic failure when we create a TermSymbol with TypeFlags, or vice versa.
     *  A more complete check is done in checkWellFormed.
     *  Also, speculatively add a Local flag to private members that can be Local if
     *  referred to exclusively from their owner's this-type. The Local flag is retracted in
     *  `isAccessibleFrom` if the access not from such a this-type.
     */
    def checkFlags(flags: FlagSet) =
      if (flags.isEmpty) flags
      else {
        val (ok, adapted, kind) = tree match {
          case tree: TypeDef => (flags.isTypeFlags, flags.toTypeFlags, "type")
          case _ => (flags.isTermFlags, flags.toTermFlags, "value")
        }
        def canBeLocal = tree match
          case tree: MemberDef => SymDenotations.canBeLocal(tree.name, flags)
          case _ => false
        if !ok then
          ctx.error(i"modifier(s) `${flags.flagsString}` incompatible with $kind definition", tree.sourcePos)
        if adapted.is(Private) && canBeLocal then adapted | Local else adapted
      }

    /** Add moduleClass/sourceModule to completer if it is for a module val or class */
    def adjustIfModule(completer: LazyType, tree: MemberDef) =
      if (tree.mods.is(Module)) ctx.adjustModuleCompleter(completer, tree.name)
      else completer

    typr.println(i"creating symbol for $tree in ${ctx.mode}")

    /** Create new symbol or redefine existing symbol under lateCompile. */
    def createOrRefine[S <: Symbol](
        tree: MemberDef, name: Name, flags: FlagSet, owner: Symbol, infoFn: S => Type,
        symFn: (FlagSet, S => Type, Symbol) => S): Symbol = {
      val prev =
        if (lateCompile && ctx.owner.is(Package)) ctx.effectiveScope.lookup(name)
        else NoSymbol

      var flags1 = flags
      var privateWithin = privateWithinClass(tree.mods)
      val effectiveOwner = owner.skipWeakOwner
      if (flags.is(Private) && effectiveOwner.is(Package)) {
        // If effective owner is a package p, widen private to private[p]
        flags1 = flags1 &~ PrivateLocal
        privateWithin = effectiveOwner
      }

      val sym =
        if (prev.exists) {
          prev.flags = flags1
          prev.info = infoFn(prev.asInstanceOf[S])
          prev.setPrivateWithin(privateWithin)
          prev
        }
        else symFn(flags1, infoFn, privateWithin)
      recordSym(sym, tree)
    }

    tree match {
      case tree: TypeDef if tree.isClassDef =>
        val flags = checkFlags(tree.mods.flags &~ GivenOrImplicit)
        val name = checkNoConflict(tree.name, flags.is(Private), tree.span).asTypeName
        val cls =
          createOrRefine[ClassSymbol](tree, name, flags, ctx.owner,
            cls => adjustIfModule(new ClassCompleter(cls, tree)(ctx), tree),
            ctx.newClassSymbol(ctx.owner, name, _, _, _, tree.nameSpan, ctx.source.file))
        cls.completer.asInstanceOf[ClassCompleter].init()
        cls
      case tree: MemberDef =>
        var flags = checkFlags(tree.mods.flags)
        val name = checkNoConflict(tree.name, flags.is(Private), tree.span)
        tree match
          case tree: ValOrDefDef =>
            if tree.unforcedRhs == EmptyTree
               && !flags.isOneOf(TermParamOrAccessor)
               && !tree.name.isConstructorName
            then
              flags |= Deferred
            if (tree.isInstanceOf[DefDef]) flags |= Method
            else if flags.isAllOf(EnumValue) && ctx.owner.isStaticOwner then flags |= JavaStatic
          case tree: TypeDef =>
            def analyzeRHS(rhs: Tree): Unit = rhs match
              case _: TypeBoundsTree | _: MatchTypeTree =>
                flags |= Deferred // Typedefs with Match rhs classify as abstract
              case LambdaTypeTree(_, body) =>
                flags |= HigherKinded
                analyzeRHS(body)
              case _ =>
                if rhs.isEmpty || flags.is(Opaque) then flags |= Deferred
            analyzeRHS(tree.rhs)

        // to complete a constructor, move one context further out -- this
        // is the context enclosing the class. Note that the context in which a
        // constructor is recorded and the context in which it is completed are
        // different: The former must have the class as owner (because the
        // constructor is owned by the class), the latter must not (because
        // constructor parameters are interpreted as if they are outside the class).
        // Don't do this for Java constructors because they need to see the import
        // of the companion object, and it is not necessary for them because they
        // have no implementation.
        val cctx = if (tree.name == nme.CONSTRUCTOR && !flags.is(JavaDefined)) ctx.outer else ctx

        val completer = tree match
          case tree: TypeDef => TypeDefCompleter(tree)(cctx)
          case _ => Completer(tree)(cctx)
        val info = adjustIfModule(completer, tree)
        createOrRefine[Symbol](tree, name, flags, ctx.owner, _ => info,
          (fs, _, pwithin) => ctx.newSymbol(ctx.owner, name, fs, info, pwithin, tree.nameSpan))
      case tree: Import =>
        recordSym(ctx.newImportSymbol(ctx.owner, Completer(tree)(ctx), tree.span), tree)
      case _ =>
        NoSymbol
    }
  }

   /** If `sym` exists, enter it in effective scope. Check that
    *  package members are not entered twice in the same run.
    */
  def enterSymbol(sym: Symbol)(using Context): Symbol = {
    if (sym.exists) {
      typr.println(s"entered: $sym in ${ctx.owner}")
      ctx.enter(sym)
    }
    sym
  }

  /** Create package if it does not yet exist. */
  private def createPackageSymbol(pid: RefTree)(using Context): Symbol = {
    val pkgOwner = pid match {
      case Ident(_) => if (ctx.owner eq defn.EmptyPackageClass) defn.RootClass else ctx.owner
      case Select(qual: RefTree, _) => createPackageSymbol(qual).moduleClass
    }
    val existing = pkgOwner.info.decls.lookup(pid.name)

    if (existing.is(Package) && (pkgOwner eq existing.owner)) {
      existing.moduleClass.denot match {
        case d: PackageClassDenotation =>
          // Remove existing members coming from a previous compilation of this file,
          // they are obsolete.
          d.unlinkFromFile(ctx.source.file)
        case _ =>
      }
      existing
    }
    else {
      /** If there's already an existing type, then the package is a dup of this type */
      val existingType = pkgOwner.info.decls.lookup(pid.name.toTypeName)
      if (existingType.exists) {
        ctx.error(PkgDuplicateSymbol(existingType), pid.sourcePos)
        ctx.newCompletePackageSymbol(pkgOwner, (pid.name ++ "$_error_").toTermName).entered
      }
      else ctx.newCompletePackageSymbol(pkgOwner, pid.name.asTermName).entered
    }
  }

  /** Expand tree and store in `expandedTree` */
  def expand(tree: Tree)(using Context): Unit = {
    def record(expanded: Tree) =
      if (expanded `ne` tree) {
        typr.println(i"Expansion: $tree expands to $expanded")
        tree.pushAttachment(ExpandedTree, expanded)
      }
    tree match {
      case tree: DefTree => record(desugar.defTree(tree))
      case tree: PackageDef => record(desugar.packageDef(tree))
      case _ =>
    }
  }

  /** The expanded version of this tree, or tree itself if not expanded */
  def expanded(tree: Tree)(using Context): Tree = tree match {
    case _: DefTree | _: PackageDef => tree.attachmentOrElse(ExpandedTree, tree)
    case _ => tree
  }

  /** For all class definitions `stat` in `xstats`: If the companion class is
    * not also defined in `xstats`, invalidate it by setting its info to
    * NoType.
    */
  def invalidateCompanions(pkg: Symbol, xstats: List[untpd.Tree])(using Context): Unit = {
    val definedNames = xstats collect { case stat: NameTree => stat.name }
    def invalidate(name: TypeName) =
      if (!(definedNames contains name)) {
        val member = pkg.info.decl(name).asSymDenotation
        if (member.isClass && !(member.is(Package))) member.markAbsent()
      }
    xstats foreach {
      case stat: TypeDef if stat.isClassDef =>
        invalidate(stat.name.moduleClassName)
      case _ =>
    }
  }

  /** Expand tree and create top-level symbols for statement and enter them into symbol table */
  def index(stat: Tree)(using Context): Context = {
    expand(stat)
    indexExpanded(stat)
  }

  /** Create top-level symbols for all statements in the expansion of this statement and
   *  enter them into symbol table
   */
  def indexExpanded(origStat: Tree)(using Context): Context = {
    def recur(stat: Tree): Context = stat match {
      case pcl: PackageDef =>
        val pkg = createPackageSymbol(pcl.pid)
        index(pcl.stats)(using ctx.fresh.setOwner(pkg.moduleClass))
        invalidateCompanions(pkg, Trees.flatten(pcl.stats map expanded))
        setDocstring(pkg, stat)
        ctx
      case imp: Import =>
        ctx.importContext(imp, createSymbol(imp))
      case mdef: DefTree =>
        val sym = enterSymbol(createSymbol(mdef))
        setDocstring(sym, origStat)
        addEnumConstants(mdef, sym)
        ctx
      case stats: Thicket =>
        stats.toList.foreach(recur)
        ctx
      case _ =>
        ctx
    }
    recur(expanded(origStat))
  }

  /** Determines whether this field holds an enum constant. */
  def isEnumConstant(vd: ValDef)(using Context): Boolean =
    vd.mods.isAllOf(JavaEnumValue)

  /** Add child annotation for `child` to annotations of `cls`. The annotation
   *  is added at the correct insertion point, so that Child annotations appear
   *  in reverse order of their start positions.
   *  @pre `child` must have a position.
   */
  final def addChild(cls: Symbol, child: Symbol)(using Context): Unit = {
    val childStart = if (child.span.exists) child.span.start else -1
    def insertInto(annots: List[Annotation]): List[Annotation] =
      annots.find(_.symbol == defn.ChildAnnot) match {
        case Some(Annotation.Child(other)) if other.span.exists && childStart <= other.span.start =>
          if (child == other)
            annots // can happen if a class has several inaccessible children
          else {
            assert(childStart != other.span.start, i"duplicate child annotation $child / $other")
            val (prefix, otherAnnot :: rest) = annots.span(_.symbol != defn.ChildAnnot)
            prefix ::: otherAnnot :: insertInto(rest)
          }
        case _ =>
          Annotation.Child(child, cls.span.startPos) :: annots
      }
    cls.annotations = insertInto(cls.annotations)
  }

  /** Add java enum constants */
  def addEnumConstants(mdef: DefTree, sym: Symbol)(using Context): Unit = mdef match {
    case vdef: ValDef if (isEnumConstant(vdef)) =>
      val enumClass = sym.owner.linkedClass
      if (!enumClass.is(Sealed)) enumClass.setFlag(Flags.AbstractSealed)
      addChild(enumClass, sym)
    case _ =>
  }

  def setDocstring(sym: Symbol, tree: Tree)(using Context): Unit = tree match {
    case t: MemberDef if t.rawComment.isDefined =>
      ctx.docCtx.foreach(_.addDocstring(sym, t.rawComment))
    case _ => ()
  }

  /** Create top-level symbols for statements and enter them into symbol table
   *  @return A context that reflects all imports in `stats`.
   */
  def index(stats: List[Tree])(using Context): Context = {

    // module name -> (stat, moduleCls | moduleVal)
    val moduleClsDef = mutable.Map[TypeName, (Tree, TypeDef)]()
    val moduleValDef = mutable.Map[TermName, (Tree, ValDef)]()

    /** Remove the subtree `tree` from the expanded tree of `mdef` */
    def removeInExpanded(mdef: Tree, tree: Tree): Unit = {
      val Thicket(trees) = expanded(mdef)
      mdef.putAttachment(ExpandedTree, Thicket(trees.filter(_ != tree)))
    }

    /** Transfer all references to `from` to `to` */
    def transferReferences(from: ValDef, to: ValDef): Unit = {
      val fromRefs = from.removeAttachment(References).getOrElse(Nil)
      val toRefs = to.removeAttachment(References).getOrElse(Nil)
      to.putAttachment(References, fromRefs ++ toRefs)
    }

    /** Merge the module class `modCls` in the expanded tree of `mdef` with the
     *  body and derived clause of the synthetic module class `fromCls`.
     */
    def mergeModuleClass(mdef: Tree, modCls: TypeDef, fromCls: TypeDef): TypeDef = {
      var res: TypeDef = null
      val Thicket(trees) = expanded(mdef)
      val merged = trees.map { tree =>
        if (tree == modCls) {
          val fromTempl = fromCls.rhs.asInstanceOf[Template]
          val modTempl = modCls.rhs.asInstanceOf[Template]
          res = cpy.TypeDef(modCls)(
            rhs = cpy.Template(modTempl)(
              derived = if (fromTempl.derived.nonEmpty) fromTempl.derived else modTempl.derived,
              body = fromTempl.body ++ modTempl.body))
          if (fromTempl.derived.nonEmpty) {
            if (modTempl.derived.nonEmpty)
              ctx.error(em"a class and its companion cannot both have `derives` clauses", mdef.sourcePos)
            res.putAttachment(desugar.DerivingCompanion, fromTempl.sourcePos.startPos)
          }
          res
        }
        else tree
      }

      mdef.putAttachment(ExpandedTree, Thicket(merged))

      res
    }

    /** Merge `fromCls` of `fromStat` into `toCls` of `toStat`
     *  if the former is synthetic and the latter not.
     *
     *  Note:
     *    1. `fromStat` and `toStat` could be the same stat
     *    2. `fromCls` and `toCls` are necessarily different
     */
    def mergeIfSynthetic(fromStat: Tree, fromCls: TypeDef, toStat: Tree, toCls: TypeDef): Unit =
      if (fromCls.mods.is(Synthetic) && !toCls.mods.is(Synthetic)) {
        removeInExpanded(fromStat, fromCls)
        val mcls = mergeModuleClass(toStat, toCls, fromCls)
        mcls.setMods(toCls.mods)
        moduleClsDef(fromCls.name) = (toStat, mcls)
      }

    /** Merge the definitions of a synthetic companion generated by a case class
     *  and the real companion, if both exist.
     */
    def mergeCompanionDefs() = {
      def valid(mdef: MemberDef): Boolean = mdef.mods.is(Module, butNot = Package)

      for (stat <- stats)
        expanded(stat) match {
          case Thicket(trees) => // companion object always expands to thickets
            trees.map {
              case mcls @ TypeDef(name, impl: Template) if valid(mcls) =>
                (moduleClsDef.get(name): @unchecked) match {
                  case Some((stat1, mcls1@TypeDef(_, impl1: Template))) =>
                    mergeIfSynthetic(stat, mcls, stat1, mcls1)
                    mergeIfSynthetic(stat1, mcls1, stat, mcls)
                  case None =>
                    moduleClsDef(name) = (stat, mcls)
                }

              case vdef @ ValDef(name, _, _) if valid(vdef) =>
                moduleValDef.get(name) match {
                  case Some((stat1, vdef1)) =>
                    if (vdef.mods.is(Synthetic) && !vdef1.mods.is(Synthetic)) {
                      transferReferences(vdef, vdef1)
                      removeInExpanded(stat, vdef)
                    }
                    else if (!vdef.mods.is(Synthetic) && vdef1.mods.is(Synthetic)) {
                      transferReferences(vdef1, vdef)
                      removeInExpanded(stat1, vdef1)
                      moduleValDef(name) = (stat, vdef)
                    }
                    else {
                      // double definition of objects or case classes, handled elsewhere
                    }
                  case None =>
                    moduleValDef(name) = (stat, vdef)
                }

              case _ =>
            }
          case _ =>
        }
    }

    /** Create links between companion object and companion class */
    def createLinks(classTree: TypeDef, moduleTree: TypeDef)(using Context) = {
      val claz = ctx.effectiveScope.lookup(classTree.name)
      val modl = ctx.effectiveScope.lookup(moduleTree.name)
      modl.registerCompanion(claz)
      claz.registerCompanion(modl)
    }

    def createCompanionLinks(using Context): Unit = {
      val classDef  = mutable.Map[TypeName, TypeDef]()
      val moduleDef = mutable.Map[TypeName, TypeDef]()

      def updateCache(cdef: TypeDef): Unit =
        if (cdef.isClassDef && !cdef.mods.is(Package))
          if (cdef.mods.is(ModuleClass)) moduleDef(cdef.name) = cdef
          else classDef(cdef.name) = cdef

      for (stat <- stats)
        expanded(stat) match {
          case cdef : TypeDef => updateCache(cdef)
          case Thicket(trees) =>
            trees.map {
              case cdef: TypeDef => updateCache(cdef)
              case _ =>
            }
          case _ =>
        }

      for (cdef @ TypeDef(name, _) <- classDef.values)
        moduleDef.getOrElse(name.moduleClassName, EmptyTree) match {
          case t: TypeDef =>
            createLinks(cdef, t)
          case EmptyTree =>
        }

      // If a top-level object or class has no companion in the current run, we
      // enter a dummy companion (`denot.isAbsent` returns true) in scope. This
      // ensures that we never use a companion from a previous run or from the
      // class path. See tests/pos/false-companion for an example where this
      // matters.
      if (ctx.owner.is(PackageClass)) {
        for (cdef @ TypeDef(moduleName, _) <- moduleDef.values) {
          val moduleSym = ctx.effectiveScope.lookup(moduleName)
          if (moduleSym.isDefinedInCurrentRun) {
            val className = moduleName.stripModuleClassSuffix.toTypeName
            val classSym = ctx.effectiveScope.lookup(className)
            if (!classSym.isDefinedInCurrentRun) {
              val absentClassSymbol = ctx.newClassSymbol(ctx.owner, className, EmptyFlags, _ => NoType)
              enterSymbol(absentClassSymbol)
            }
          }
        }
        for (cdef @ TypeDef(className, _) <- classDef.values) {
          val classSym = ctx.effectiveScope.lookup(className.encode)
          if (classSym.isDefinedInCurrentRun) {
            val moduleName = className.toTermName
            for (moduleSym <- ctx.effectiveScope.lookupAll(moduleName.encode))
              if (moduleSym.is(Module) && !moduleSym.isDefinedInCurrentRun) {
                val absentModuleSymbol = ctx.newModuleSymbol(ctx.owner, moduleName, EmptyFlags, EmptyFlags, (_, _) => NoType)
                enterSymbol(absentModuleSymbol)
              }
          }
        }
      }
    }

    stats.foreach(expand)
    mergeCompanionDefs()
    val ctxWithStats = stats.foldLeft(ctx)((ctx, stat) => indexExpanded(stat)(using ctx))
    createCompanionLinks(using ctxWithStats)
    ctxWithStats
  }

  /** Index symbols in `tree` while asserting the `lateCompile` flag.
   *  This will cause any old top-level symbol with the same fully qualified
   *  name as a newly created symbol to be replaced.
   */
  def lateEnter(tree: Tree)(using Context): Context = {
    val saved = lateCompile
    lateCompile = true
    try index(tree :: Nil) finally lateCompile = saved
  }

  /** The type bound on wildcard imports of an import list, with special values
   *    Nothing  if no wildcard imports of this kind exist
   *    Any      if there are unbounded wildcard imports of this kind
   */
  def importBound(sels: List[untpd.ImportSelector], isGiven: Boolean)(using Context): Type =
    sels.foldLeft(defn.NothingType: Type) { (bound, sel) =>
      if sel.isWildcard && sel.isGiven == isGiven then
        if sel.bound.isEmpty then defn.AnyType
        else bound | typedAheadType(sel.bound).tpe
      else bound
    }

  def missingType(sym: Symbol, modifier: String)(using Context): Unit = {
    ctx.error(s"${modifier}type of implicit definition needs to be given explicitly", sym.sourcePos)
    sym.resetFlag(GivenOrImplicit)
  }

  /** The completer of a symbol defined by a member def or import (except ClassSymbols) */
  class Completer(val original: Tree)(ictx: Context) extends LazyType with SymbolLoaders.SecondCompleter {

    protected def localContext(owner: Symbol): FreshContext = ctx.fresh.setOwner(owner).setTree(original)

    /** The context with which this completer was created */
    given creationContext as Context = ictx
    ictx.typerState.markShared()

    protected def typeSig(sym: Symbol): Type = original match {
      case original: ValDef =>
        if (sym.is(Module)) moduleValSig(sym)
        else valOrDefDefSig(original, sym, Nil, Nil, identity)(localContext(sym).setNewScope)
      case original: DefDef =>
        val typer1 = ctx.typer.newLikeThis
        nestedTyper(sym) = typer1
        typer1.defDefSig(original, sym)(localContext(sym).setTyper(typer1))
      case imp: Import =>
        try {
          val expr1 = typedAheadExpr(imp.expr, AnySelectionProto)
          ImportType(expr1)
        }
        catch {
          case ex: CyclicReference =>
            typr.println(s"error while completing ${imp.expr}")
            throw ex
        }
    }

    final override def complete(denot: SymDenotation)(using Context): Unit = {
      if (Config.showCompletions && ctx.typerState != creationContext.typerState) {
        def levels(c: Context): Int =
          if (c.typerState eq creationContext.typerState) 0
          else if (c.typerState == null) -1
          else if (c.outer.typerState == c.typerState) levels(c.outer)
          else levels(c.outer) + 1
        println(s"!!!completing ${denot.symbol.showLocated} in buried typerState, gap = ${levels(ctx)}")
      }
      if (ctx.runId > creationContext.runId) {
        assert(ctx.mode.is(Mode.Interactive), s"completing $denot in wrong run ${ctx.runId}, was created in ${creationContext.runId}")
        denot.info = UnspecifiedErrorType
      }
      else
        try
          completeInCreationContext(denot)
          if (denot.isCompleted) registerIfChild(denot)
        catch
          case ex: CompilationUnit.SuspendException =>
            val completer = SuspendCompleter()
            denot.info = completer
            completer.complete(denot)
    }

    protected def addAnnotations(sym: Symbol): Unit = original match {
      case original: untpd.MemberDef =>
        lazy val annotCtx = annotContext(original, sym)
        for (annotTree <- untpd.modsDeco(original).mods.annotations) {
          val cls = typedAheadAnnotationClass(annotTree)(annotCtx)
          if (cls eq sym)
            ctx.error("An annotation class cannot be annotated with iself", annotTree.sourcePos)
          else {
            val ann = Annotation.deferred(cls)(typedAnnotation(annotTree))
            sym.addAnnotation(ann)
          }
        }
      case _ =>
    }

    private def addInlineInfo(sym: Symbol) = original match {
      case original: untpd.DefDef if sym.isInlineMethod =>
        def rhsToInline(using Context): tpd.Tree =
          val mdef = typedAheadExpr(original).asInstanceOf[tpd.DefDef]
          PrepareInlineable.wrapRHS(original, mdef.tpt, mdef.rhs)
        PrepareInlineable.registerInlineInfo(sym, rhsToInline)(using localContext(sym))
      case _ =>
    }

    /** Invalidate `denot` by overwriting its info with `NoType` if
     *  `denot` is a compiler generated case class method that clashes
     *  with a user-defined method in the same scope with a matching type.
     */
    private def invalidateIfClashingSynthetic(denot: SymDenotation): Unit = {
      def isCaseClass(owner: Symbol) =
        owner.isClass && {
          if (owner.is(Module)) owner.linkedClass.is(CaseClass)
          else owner.is(CaseClass)
        }
      val isClashingSynthetic =
        denot.is(Synthetic) &&
        desugar.isRetractableCaseClassMethodName(denot.name) &&
        isCaseClass(denot.owner) &&
        denot.owner.info.decls.lookupAll(denot.name).exists(alt =>
          alt != denot.symbol && alt.info.matchesLoosely(denot.info))
      if (isClashingSynthetic) {
        typr.println(i"invalidating clashing $denot in ${denot.owner}")
        denot.markAbsent()
      }
    }

    /** If completed symbol is an enum value or a named class, register it as a child
     *  in all direct parent classes which are sealed.
     */
    def registerIfChild(denot: SymDenotation)(implicit ctx: Context): Unit = {
      val sym = denot.symbol

      def register(child: Symbol, parentCls: ClassSymbol) = {
        if (parentCls.is(Sealed))
          if ((child.isInaccessibleChildOf(parentCls) || child.isAnonymousClass) && !sym.hasAnonymousChild)
            addChild(parentCls, parentCls)
          else if (!parentCls.is(ChildrenQueried))
            addChild(parentCls, child)
          else
            ctx.error(em"""children of $parentCls were already queried before $sym was discovered.
                          |As a remedy, you could move $sym on the same nesting level as $parentCls.""",
                      child.sourcePos)
      }

      if denot.isClass && !sym.isEnumAnonymClass && !sym.isRefinementClass then
        val child = if (denot.is(Module)) denot.sourceModule else denot.symbol
        denot.asClass.classParents.foreach { parent => register(child, parent.classSymbol.asClass) }
      else if denot.is(CaseVal, butNot = Method | Module) then
        assert(denot.is(Enum), denot)
        denot.info.classSymbols.foreach { parent => register(denot.symbol, parent) }
      end if
    }

    /** Intentionally left without `implicit ctx` parameter. We need
     *  to pick up the context at the point where the completer was created.
     */
    def completeInCreationContext(denot: SymDenotation): Unit = {
      val sym = denot.symbol
      addAnnotations(sym)
      addInlineInfo(sym)
      denot.info = typeSig(sym)
      invalidateIfClashingSynthetic(denot)
      Checking.checkWellFormed(sym)
      denot.info = avoidPrivateLeaks(sym)
    }
  }

  class TypeDefCompleter(original: TypeDef)(ictx: Context)
  extends Completer(original)(ictx) with TypeParamsCompleter {
    private var myTypeParams: List[TypeSymbol] = null
    private var nestedCtx: Context = null
    assert(!original.isClassDef)

    override def completerTypeParams(sym: Symbol)(implicit ctx: Context): List[TypeSymbol] =
      if myTypeParams == null then
        //println(i"completing type params of $sym in ${sym.owner}")
        nestedCtx = localContext(sym).setNewScope
        given Context = nestedCtx

        def typeParamTrees(tdef: Tree): List[TypeDef] = tdef match
          case TypeDef(_, original) =>
            original match
              case LambdaTypeTree(tparams, _) => tparams
              case original: DerivedFromParamTree => typeParamTrees(original.watched)
              case _ => Nil
          case _ => Nil

        val tparams = typeParamTrees(original)
        index(tparams)
        myTypeParams = tparams.map(symbolOfTree(_).asType)
        for param <- tparams do typedAheadExpr(param)
      end if
      myTypeParams
    end completerTypeParams

    override final def typeSig(sym: Symbol): Type =
      val tparamSyms = completerTypeParams(sym)(ictx)
      given ctx as Context = nestedCtx
      def abstracted(tp: TypeBounds): TypeBounds = HKTypeLambda.boundsFromParams(tparamSyms, tp)
      val dummyInfo1 = abstracted(TypeBounds.empty)
      sym.info = dummyInfo1
      sym.setFlag(Provisional)
        // Temporarily set info of defined type T to ` >: Nothing <: Any.
        // This is done to avoid cyclic reference errors for F-bounds.
        // This is subtle: `sym` has now an empty TypeBounds, but is not automatically
        // made an abstract type. If it had been made an abstract type, it would count as an
        // abstract type of its enclosing class, which might make that class an invalid
        // prefix. I verified this would lead to an error when compiling io.ClassPath.
        // A distilled version is in pos/prefix.scala.
        //
        // The scheme critically relies on an implementation detail of isRef, which
        // inspects a TypeRef's info, instead of simply dealiasing alias types.

      val isDerived = original.rhs.isInstanceOf[untpd.DerivedTypeTree]
      val rhs = original.rhs match {
        case LambdaTypeTree(_, body) => body
        case rhs => rhs
      }

      // For match types: approximate with upper bound while evaluating the rhs.
      val dummyInfo2 = rhs match {
        case MatchTypeTree(bound, _, _) if !bound.isEmpty =>
          abstracted(TypeBounds.upper(typedAheadType(bound).tpe))
        case _ =>
          dummyInfo1
      }
      sym.info = dummyInfo2

      val rhs1 = typedAheadType(rhs)
      val rhsBodyType: TypeBounds = rhs1.tpe.toBounds
      val unsafeInfo = if (isDerived) rhsBodyType else abstracted(rhsBodyType)

      def opaqueToBounds(info: Type): Type =
        if sym.isOpaqueAlias && tparamSyms.isEmpty && info.typeParams.nonEmpty then
          ctx.error(em"opaque type alias must be fully applied", rhs.sourcePos)
        sym.opaqueToBounds(info, rhs1, tparamSyms)

      if (isDerived) sym.info = unsafeInfo
      else {
        sym.info = NoCompleter
        sym.info = opaqueToBounds(checkNonCyclic(sym, unsafeInfo, reportErrors = true))
      }
      if sym.isOpaqueAlias then sym.typeRef.recomputeDenot() // make sure we see the new bounds from now on
      sym.resetFlag(Provisional)

      // Here we pay the price for the cavalier setting info to TypeBounds.empty above.
      // We need to compensate by reloading the denotation of references that might
      // still contain the TypeBounds.empty. If we do not do this, stdlib factories
      // fail with a bounds error in PostTyper.
      def ensureUpToDate(tref: TypeRef, outdated: Type) =
        if (tref.info == outdated && sym.info != outdated) tref.recomputeDenot()
      ensureUpToDate(sym.typeRef, dummyInfo1)
      if (dummyInfo2 `ne` dummyInfo1) ensureUpToDate(sym.typeRef, dummyInfo2)

      sym.info
    end typeSig
  }

  class ClassCompleter(cls: ClassSymbol, original: TypeDef)(ictx: Context) extends Completer(original)(ictx) {
    withDecls(newScope)

    protected implicit val completerCtx: Context = localContext(cls)

    private var localCtx: Context = _

    /** info to be used temporarily while completing the class, to avoid cyclic references. */
    private var tempInfo: TempClassInfo = _

    val TypeDef(name, impl @ Template(constr, _, self, _)) = original

    private val (params, rest): (List[Tree], List[Tree]) = impl.body.span {
      case td: TypeDef => td.mods.is(Param)
      case vd: ValDef => vd.mods.is(ParamAccessor)
      case _ => false
    }

    def init(): Context = index(params)

    /** Add forwarders as required by the export statements in this class */
    private def processExports(implicit ctx: Context): Unit = {

      /** A string indicating that no forwarders for this kind of symbol are emitted */
      val SKIP = "(skip)"

      /** The forwarders defined by export `exp`.
       */
      def exportForwarders(exp: Export): List[tpd.MemberDef] = {
        val buf = new mutable.ListBuffer[tpd.MemberDef]
        val Export(expr, selectors) = exp
        val path = typedAheadExpr(expr, AnySelectionProto)
        checkLegalImportPath(path)
        lazy val wildcardBound = importBound(selectors, isGiven = false)
        lazy val givenBound = importBound(selectors, isGiven = true)

        def whyNoForwarder(mbr: SingleDenotation): String = {
          val sym = mbr.symbol
          if (!sym.isAccessibleFrom(path.tpe)) "is not accessible"
          else if (sym.isConstructor || sym.is(ModuleClass) || sym.is(Bridge)) SKIP
          else if (cls.derivesFrom(sym.owner) &&
                   (sym.owner == cls || !sym.is(Deferred))) i"is already a member of $cls"
          else if (sym.is(Override))
            sym.allOverriddenSymbols.find(
             other => cls.derivesFrom(other.owner) && !other.is(Deferred)) match {
               case Some(other) => i"overrides ${other.showLocated}, which is already a member of $cls"
               case None => ""
            }
          else ""
        }

        /** Add a forwarder with name `alias` or its type name equivalent to `mbr`,
         *  provided `mbr` is accessible and of the right implicit/non-implicit kind.
         */
        def addForwarder(alias: TermName, mbr: SingleDenotation, span: Span): Unit =
          if (whyNoForwarder(mbr) == "") {
            val sym = mbr.symbol
            val forwarder =
              if mbr.isType then
                val forwarderName = checkNoConflict(alias.toTypeName, isPrivate = false, span)
                ctx.newSymbol(
                  cls, forwarderName,
                  Exported | Final,
                  TypeAlias(path.tpe.select(sym)),
                  coord = span)
                // Note: This will always create unparameterzied aliases. So even if the original type is
                // a parameterized class, say `C[X]` the alias will read `type C = d.C`. We currently do
                // allow such type aliases. If we forbid them at some point (requiring the referred type to be
                // fully applied), we'd have to change the scheme here as well.
              else {
                def refersToPrivate(tp: Type): Boolean = tp match
                  case tp: TermRef => tp.termSymbol.is(Private) || refersToPrivate(tp.prefix)
                  case _ => false
                val (maybeStable, mbrInfo) =
                  if sym.isStableMember && sym.isPublic && !refersToPrivate(path.tpe) then
                    (StableRealizable, ExprType(path.tpe.select(sym)))
                  else
                    (EmptyFlags, mbr.info.ensureMethodic)
                var mbrFlags = Exported | Method | Final | maybeStable | sym.flags & RetainedExportFlags
                if sym.isAllOf(ExtensionMethod) then mbrFlags |= Extension
                val forwarderName = checkNoConflict(alias, isPrivate = false, span)
                ctx.newSymbol(cls, forwarderName, mbrFlags, mbrInfo, coord = span)
              }
            forwarder.info = avoidPrivateLeaks(forwarder)
            val forwarderDef =
              if (forwarder.isType) tpd.TypeDef(forwarder.asType)
              else {
                import tpd._
                val ref = path.select(sym.asTerm)
                tpd.polyDefDef(forwarder.asTerm, targs => prefss =>
                  ref.appliedToTypes(targs).appliedToArgss(prefss)
                )
              }
            buf += forwarderDef.withSpan(span)
          }

        def addForwardersNamed(name: TermName, alias: TermName, span: Span): Unit = {
          val size = buf.size
          val mbrs = List(name, name.toTypeName).flatMap(path.tpe.member(_).alternatives)
          mbrs.foreach(addForwarder(alias, _, span))
          if (buf.size == size) {
            val reason = mbrs.map(whyNoForwarder).dropWhile(_ == SKIP) match {
              case Nil => ""
              case why :: _ => i"\n$path.$name cannot be exported because it $why"
            }
            ctx.error(i"""no eligible member $name at $path$reason""", ctx.source.atSpan(span))
          }
        }

        def addWildcardForwarders(seen: List[TermName], span: Span): Unit =
          for mbr <- path.tpe.membersBasedOnFlags(required = EmptyFlags, excluded = PrivateOrSynthetic) do
            val alias = mbr.name.toTermName
            if !seen.contains(alias)
               && mbr.matchesImportBound(if mbr.symbol.is(Given) then givenBound else wildcardBound)
            then addForwarder(alias, mbr, span)

        def addForwarders(sels: List[untpd.ImportSelector], seen: List[TermName]): Unit = sels match
          case sel :: sels1 =>
            if sel.isWildcard then
              addWildcardForwarders(seen, sel.span)
            else
              if sel.rename != nme.WILDCARD then
                addForwardersNamed(sel.name, sel.rename, sel.span)
              addForwarders(sels1, sel.name :: seen)
          case _ =>

        addForwarders(selectors, Nil)
        val forwarders = buf.toList
        exp.pushAttachment(ExportForwarders, forwarders)
        forwarders
      }

      for case exp @ Export(_, _) <- rest do
        for forwarder <- exportForwarders(exp) do
          forwarder.symbol.entered
    }

    /** Ensure constructor is completed so that any parameter accessors
     *  which have type trees deriving from its parameters can be
     *  completed in turn. Note that parent types access such parameter
     *  accessors, that's why the constructor needs to be completed before
     *  the parent types are elaborated.
     */
    def completeConstructor(denot: SymDenotation): Unit = {
      if (tempInfo != null) // Constructor has been completed already
        return

      addAnnotations(denot.symbol)

      val selfInfo: TypeOrSymbol =
        if (self.isEmpty) NoType
        else if (cls.is(Module)) {
          val moduleType = cls.owner.thisType select sourceModule
          if (self.name == nme.WILDCARD) moduleType
          else recordSym(
            completerCtx.newSymbol(cls, self.name, self.mods.flags, moduleType, coord = self.span),
            self)
        }
        else createSymbol(self)

      val savedInfo = denot.infoOrCompleter
      denot.info = new TempClassInfo(cls.owner.thisType, cls, decls, selfInfo)

      localCtx = completerCtx.inClassContext(selfInfo)

      index(constr)
      index(rest)(using localCtx)

      symbolOfTree(constr).info.stripPoly match // Completes constr symbol as a side effect
        case mt: MethodType if cls.is(Case) && mt.isParamDependent =>
          // See issue #8073 for background
          completerCtx.error(
              i"""Implementation restriction: case classes cannot have dependencies between parameters""",
              cls.sourcePos)
        case _ =>

      tempInfo = denot.asClass.classInfo.integrateOpaqueMembers.asInstanceOf[TempClassInfo]
      denot.info = savedInfo
    }

    /** The type signature of a ClassDef with given symbol */
    override def completeInCreationContext(denot: SymDenotation): Unit = {
      val parents = impl.parents

      /* The type of a parent constructor. Types constructor arguments
       * only if parent type contains uninstantiated type parameters.
       */
      def parentType(parent: untpd.Tree)(implicit ctx: Context): Type =
        if (parent.isType)
          typedAheadType(parent, AnyTypeConstructorProto).tpe
        else {
          val (core, targs) = stripApply(parent) match {
            case TypeApply(core, targs) => (core, targs)
            case core => (core, Nil)
          }
          core match {
            case Select(New(tpt), nme.CONSTRUCTOR) =>
              val targs1 = targs map (typedAheadType(_))
              val ptype = typedAheadType(tpt).tpe appliedTo targs1.tpes
              if (ptype.typeParams.isEmpty) ptype
              else {
                if (denot.is(ModuleClass) && denot.sourceModule.isOneOf(GivenOrImplicit))
                  missingType(denot.symbol, "parent ")(using creationContext)
                fullyDefinedType(typedAheadExpr(parent).tpe, "class parent", parent.span)
              }
            case _ =>
              UnspecifiedErrorType.assertingErrorsReported
          }
        }

      /** Check parent type tree `parent` for the following well-formedness conditions:
       *  (1) It must be a class type with a stable prefix (@see checkClassTypeWithStablePrefix)
       *  (2) If may not derive from itself
       *  (3) The class is not final
       *  (4) If the class is sealed, it is defined in the same compilation unit as the current class
       */
      def checkedParentType(parent: untpd.Tree): Type = {
        val ptype = parentType(parent)(completerCtx.superCallContext).dealiasKeepAnnots
        if (cls.isRefinementClass) ptype
        else {
          val pt = checkClassType(ptype, parent.sourcePos,
              traitReq = parent ne parents.head, stablePrefixReq = true)
          if (pt.derivesFrom(cls)) {
            val addendum = parent match {
              case Select(qual: Super, _) if Feature.migrateTo3 =>
                "\n(Note that inheriting a class of the same name is no longer allowed)"
              case _ => ""
            }
            completerCtx.error(CyclicInheritance(cls, addendum), parent.sourcePos)
            defn.ObjectType
          }
          else {
            val pclazz = pt.typeSymbol
            if pclazz.is(Final) then
              completerCtx.error(ExtendFinalClass(cls, pclazz), cls.sourcePos)
            else if pclazz.isEffectivelySealed && pclazz.associatedFile != cls.associatedFile then
              if pclazz.is(Sealed) then
                completerCtx.error(UnableToExtendSealedClass(pclazz), cls.sourcePos)
              else if sourceVersion.isAtLeast(`3.1`) then
                checkFeature(nme.adhocExtensions,
                  i"Unless $pclazz is declared 'open', its extension in a separate file",
                  cls.topLevelClass,
                  parent.sourcePos)
            pt
          }
        }
      }

      completeConstructor(denot)
      denot.info = tempInfo

      val parentTypes = defn.adjustForTuple(cls, cls.typeParams,
        ensureFirstIsClass(parents.map(checkedParentType(_)), cls.span))
      typr.println(i"completing $denot, parents = $parents%, %, parentTypes = $parentTypes%, %")

      if (impl.derived.nonEmpty) {
        val (derivingClass, derivePos) = original.removeAttachment(desugar.DerivingCompanion) match {
          case Some(pos) => (cls.companionClass.orElse(cls).asClass, pos)
          case None => (cls, impl.sourcePos.startPos)
        }
        val deriver = new Deriver(derivingClass, derivePos)(localCtx)
        deriver.enterDerived(impl.derived)
        original.putAttachment(Deriver, deriver)
      }

      denot.info = tempInfo.finalized(parentTypes)
      tempInfo = null // The temporary info can now be garbage-collected

      Checking.checkWellFormed(cls)
      if (isDerivedValueClass(cls)) cls.setFlag(Final)
      cls.info = avoidPrivateLeaks(cls)
      cls.baseClasses.foreach(_.invalidateBaseTypeCache()) // we might have looked before and found nothing
      cls.setNoInitsFlags(parentsKind(parents), untpd.bodyKind(rest))
      if (cls.isNoInitsClass) cls.primaryConstructor.setFlag(StableRealizable)
      processExports(localCtx)
    }
  }

  class SuspendCompleter extends LazyType, SymbolLoaders.SecondCompleter {

    final override def complete(denot: SymDenotation)(implicit ctx: Context): Unit =
      denot.resetFlag(Touched) // allow one more completion
      ctx.compilationUnit.suspend()
  }

  /** Typecheck `tree` during completion using `typed`, and remember result in TypedAhead map */
  def typedAhead(tree: Tree, typed: untpd.Tree => tpd.Tree)(implicit ctx: Context): tpd.Tree = {
    val xtree = expanded(tree)
    xtree.getAttachment(TypedAhead) match {
      case Some(ttree) => ttree
      case none =>
        val ttree = typed(tree)
        xtree.putAttachment(TypedAhead, ttree)
        ttree
    }
  }

  def typedAheadType(tree: Tree, pt: Type = WildcardType)(implicit ctx: Context): tpd.Tree =
    typedAhead(tree, typer.typed(_, pt)(using ctx.retractMode(Mode.PatternOrTypeBits).addMode(Mode.Type)))

  def typedAheadExpr(tree: Tree, pt: Type = WildcardType)(implicit ctx: Context): tpd.Tree =
    typedAhead(tree, typer.typed(_, pt)(using ctx.retractMode(Mode.PatternOrTypeBits)))

  def typedAheadAnnotation(tree: Tree)(implicit ctx: Context): tpd.Tree =
    typedAheadExpr(tree, defn.AnnotationClass.typeRef)

  def typedAheadAnnotationClass(tree: Tree)(implicit ctx: Context): Symbol = tree match {
    case Apply(fn, _) => typedAheadAnnotationClass(fn)
    case TypeApply(fn, _) => typedAheadAnnotationClass(fn)
    case Select(qual, nme.CONSTRUCTOR) => typedAheadAnnotationClass(qual)
    case New(tpt) => typedAheadType(tpt).tpe.classSymbol
  }

  /** Enter and typecheck parameter list */
  def completeParams(params: List[MemberDef])(implicit ctx: Context): Unit = {
    index(params)
    for (param <- params) typedAheadExpr(param)
  }

  /** The signature of a module valdef.
   *  This will compute the corresponding module class TypeRef immediately
   *  without going through the defined type of the ValDef. This is necessary
   *  to avoid cyclic references involving imports and module val defs.
   */
  def moduleValSig(sym: Symbol)(implicit ctx: Context): Type = {
    val clsName = sym.name.moduleClassName
    val cls = ctx.denotNamed(clsName).suchThat(_.is(ModuleClass))
      .orElse(ctx.newStubSymbol(ctx.owner, clsName).assertingErrorsReported)
    ctx.owner.thisType.select(clsName, cls)
  }

  /** The type signature of a ValDef or DefDef
   *  @param mdef     The definition
   *  @param sym      Its symbol
   *  @param paramFn  A wrapping function that produces the type of the
   *                  defined symbol, given its final return type
   */
  def valOrDefDefSig(mdef: ValOrDefDef, sym: Symbol, typeParams: List[Symbol], paramss: List[List[Symbol]], paramFn: Type => Type)(implicit ctx: Context): Type = {

    def inferredType = {
      /** A type for this definition that might be inherited from elsewhere:
       *  If this is a setter parameter, the corresponding getter type.
       *  If this is a class member, the conjunction of all result types
       *  of overridden methods.
       *  NoType if neither case holds.
       */
      val inherited =
        if (sym.owner.isTerm) NoType
        else {
          // TODO: Look only at member of supertype instead?
          lazy val schema = paramFn(WildcardType)
          val site = sym.owner.thisType
          sym.owner.info.baseClasses.tail.foldLeft(NoType: Type) { (tp, cls) =>
            def instantiatedResType(info: Type, tparams: List[Symbol], paramss: List[List[Symbol]]): Type = info match {
              case info: PolyType =>
                if (info.paramNames.length == typeParams.length)
                  instantiatedResType(info.instantiate(tparams.map(_.typeRef)), Nil, paramss)
                else NoType
              case info: MethodType =>
                paramss match {
                  case params :: paramss1 if info.paramNames.length == params.length =>
                    instantiatedResType(info.instantiate(params.map(_.termRef)), tparams, paramss1)
                  case _ =>
                    NoType
                }
              case _ =>
                if (tparams.isEmpty && paramss.isEmpty) info.widenExpr
                else NoType
            }
            val iRawInfo =
              cls.info.nonPrivateDecl(sym.name).matchingDenotation(site, schema).info
            val iResType = instantiatedResType(iRawInfo, typeParams, paramss).asSeenFrom(site, cls)
            if (iResType.exists)
              typr.println(i"using inherited type for ${mdef.name}; raw: $iRawInfo, inherited: $iResType")
            tp & iResType
          }
        }

      /** The proto-type to be used when inferring the result type from
       *  the right hand side. This is `WildcardType` except if the definition
       *  is a default getter. In that case, the proto-type is the type of
       *  the corresponding parameter where bound parameters are replaced by
       *  Wildcards.
       */
      def rhsProto = sym.asTerm.name collect {
        case DefaultGetterName(original, idx) =>
          val meth: Denotation =
            if (original.isConstructorName && (sym.owner.is(ModuleClass)))
              sym.owner.companionClass.info.decl(nme.CONSTRUCTOR)
            else
              ctx.defContext(sym).denotNamed(original)
          def paramProto(paramss: List[List[Type]], idx: Int): Type = paramss match {
            case params :: paramss1 =>
              if (idx < params.length) wildApprox(params(idx))
              else paramProto(paramss1, idx - params.length)
            case nil =>
              WildcardType
          }
          val defaultAlts = meth.altsWith(_.hasDefaultParams)
          if (defaultAlts.length == 1)
            paramProto(defaultAlts.head.info.widen.paramInfoss, idx)
          else
            WildcardType
      } getOrElse WildcardType

      // println(s"final inherited for $sym: ${inherited.toString}") !!!
      // println(s"owner = ${sym.owner}, decls = ${sym.owner.info.decls.show}")
      def isInlineVal = sym.isOneOf(FinalOrInline, butNot = Method | Mutable)

      // Widen rhs type and eliminate `|' but keep ConstantTypes if
      // definition is inline (i.e. final in Scala2) and keep module singleton types
      // instead of widening to the underlying module class types.
      // We also drop the @Repeated annotation here to avoid leaking it in method result types
      // (see run/inferred-repeated-result).
      def widenRhs(tp: Type): Type = {
        val tp1 = tp.widenTermRefExpr.simplified match
          case ctp: ConstantType if isInlineVal => ctp
          case ref: TypeRef if ref.symbol.is(ModuleClass) => tp
          case tp => tp.widenUnion
        tp1.dropRepeatedAnnot
      }

      // Replace aliases to Unit by Unit itself. If we leave the alias in
      // it would be erased to BoxedUnit.
      def dealiasIfUnit(tp: Type) = if (tp.isRef(defn.UnitClass)) defn.UnitType else tp

      var rhsCtx = ctx.fresh.addMode(Mode.InferringReturnType)
      if (sym.isInlineMethod) rhsCtx = rhsCtx.addMode(Mode.InlineableBody)
      if (typeParams.nonEmpty) {
        // we'll be typing an expression from a polymorphic definition's body,
        // so we must allow constraining its type parameters
        // compare with typedDefDef, see tests/pos/gadt-inference.scala
        rhsCtx.setFreshGADTBounds
        rhsCtx.gadt.addToConstraint(typeParams)
      }
      def rhsType = typedAheadExpr(mdef.rhs, (inherited orElse rhsProto).widenExpr)(rhsCtx).tpe

      // Approximate a type `tp` with a type that does not contain skolem types.
      val deskolemize = new ApproximatingTypeMap {
        def apply(tp: Type) = /*trace(i"deskolemize($tp) at $variance", show = true)*/
          tp match {
            case tp: SkolemType => range(defn.NothingType, atVariance(1)(apply(tp.info)))
            case _ => mapOver(tp)
          }
      }

      def cookedRhsType = deskolemize(dealiasIfUnit(widenRhs(rhsType)))
      def lhsType = fullyDefinedType(cookedRhsType, "right-hand side", mdef.span)
      //if (sym.name.toString == "y") println(i"rhs = $rhsType, cooked = $cookedRhsType")
      if (inherited.exists)
        if (isInlineVal) lhsType else inherited
      else {
        if (sym.is(Implicit))
          mdef match {
            case _: DefDef => missingType(sym, "result ")
            case _: ValDef if sym.owner.isType => missingType(sym, "")
            case _ =>
          }
        lhsType orElse WildcardType
      }
    }

    val tptProto = mdef.tpt match {
      case _: untpd.DerivedTypeTree =>
        WildcardType
      case TypeTree() =>
        checkMembersOK(inferredType, mdef.sourcePos)
      case DependentTypeTree(tpFun) =>
        val tpe = tpFun(paramss.head)
        if (isFullyDefined(tpe, ForceDegree.none)) tpe
        else typedAheadExpr(mdef.rhs, tpe).tpe
      case TypedSplice(tpt: TypeTree) if !isFullyDefined(tpt.tpe, ForceDegree.none) =>
        val rhsType = typedAheadExpr(mdef.rhs, tpt.tpe).tpe
        mdef match {
          case mdef: DefDef if mdef.name == nme.ANON_FUN =>
            val hygienicType = avoid(rhsType, paramss.flatten)
            if (!hygienicType.isValueType || !(hygienicType <:< tpt.tpe))
              ctx.error(i"return type ${tpt.tpe} of lambda cannot be made hygienic;\n" +
                i"it is not a supertype of the hygienic type $hygienicType", mdef.sourcePos)
            //println(i"lifting $rhsType over $paramss -> $hygienicType = ${tpt.tpe}")
            //println(TypeComparer.explained { implicit ctx => hygienicType <:< tpt.tpe })
          case _ =>
        }
        WildcardType
      case _ =>
        WildcardType
    }
    val memTpe = paramFn(checkSimpleKinded(typedAheadType(mdef.tpt, tptProto)).tpe)
    if (ctx.explicitNulls && mdef.mods.is(JavaDefined))
      JavaNullInterop.nullifyMember(sym, memTpe, mdef.mods.isAllOf(JavaEnumValue))
    else memTpe
  }

  /** The type signature of a DefDef with given symbol */
  def defDefSig(ddef: DefDef, sym: Symbol)(implicit ctx: Context): Type = {
    // Beware: ddef.name need not match sym.name if sym was freshened!
    val DefDef(_, tparams, vparamss, _, _) = ddef
    val isConstructor = sym.name == nme.CONSTRUCTOR

    // The following 3 lines replace what was previously just completeParams(tparams).
    // But that can cause bad bounds being computed, as witnessed by
    // tests/pos/paramcycle.scala. The problematic sequence is this:
    //   0. Class constructor gets completed.
    //   1. Type parameter CP of constructor gets completed
    //   2. As a first step CP's bounds are set to Nothing..Any.
    //   3. CP's real type bound demands the completion of corresponding type parameter DP
    //      of enclosing class.
    //   4. Type parameter DP has a rhs a DerivedFromParam tree, as installed by
    //      desugar.classDef
    //   5. The completion of DP then copies the current bounds of CP, which are still Nothing..Any.
    //   6. The completion of CP finishes installing the real type bounds.
    // Consequence: CP ends up with the wrong bounds!
    // To avoid this we always complete type parameters of a class before the type parameters
    // of the class constructor, but after having indexed the constructor parameters (because
    // indexing is needed to provide a symbol to copy for DP's completion.
    // With the patch, we get instead the following sequence:
    //   0. Class constructor gets completed.
    //   1. Class constructor parameter CP is indexed.
    //   2. Class parameter DP starts completion.
    //   3. Info of CP is computed (to be copied to DP).
    //   4. CP is completed.
    //   5. Info of CP is copied to DP and DP is completed.
    index(tparams)
    if (isConstructor) sym.owner.typeParams.foreach(_.ensureCompleted())
    for (tparam <- tparams) typedAheadExpr(tparam)

    vparamss foreach completeParams
    def typeParams = tparams map symbolOfTree
    val termParamss = ctx.normalizeIfConstructor(vparamss.nestedMap(symbolOfTree), isConstructor)
    sym.setParamss(typeParams, termParamss)
    def wrapMethType(restpe: Type): Type = {
      instantiateDependent(restpe, typeParams, termParamss)
      ctx.methodType(tparams map symbolOfTree, termParamss, restpe, isJava = ddef.mods.is(JavaDefined))
    }
    if (isConstructor) {
      // set result type tree to unit, but take the current class as result type of the symbol
      typedAheadType(ddef.tpt, defn.UnitType)
      wrapMethType(ctx.effectiveResultType(sym, typeParams, NoType))
    }
    else valOrDefDefSig(ddef, sym, typeParams, termParamss, wrapMethType)
  }
}
