package dotty.tools
package dotc
package transform

import core.Annotations.Annotation
import core.Contexts.Context
import core.Definitions
import core.Flags._
import core.Names.{DerivedName, Name, SimpleName, TypeName}
import core.Symbols._
import core.TypeApplications.TypeParamInfo
import core.TypeErasure.{erasure, isUnboundedGeneric}
import core.Types._
import core.classfile.ClassfileConstants
import ast.Trees._
import SymUtils._
import TypeUtils._
import java.lang.StringBuilder

import scala.annotation.tailrec

/** Helper object to generate generic java signatures, as defined in
 *  the Java Virtual Machine Specification, §4.3.4
 */
object GenericSignatures {

  /** Generate the signature for `sym0`, with type `info`, as defined in
   *  the Java Virtual Machine Specification, §4.3.4
   *
   *  @param sym0 The symbol for which to define the signature
   *  @param info The type of the symbol
   *  @return The signature if it could be generated, `None` otherwise.
   */
  def javaSig(sym0: Symbol, info: Type)(implicit ctx: Context): Option[String] =
    // Avoid generating a signature for local symbols.
    if (sym0.isLocal) None
    else javaSig0(sym0, info)(ctx.withPhase(ctx.erasurePhase))

  @noinline
  private final def javaSig0(sym0: Symbol, info: Type)(implicit ctx: Context): Option[String] = {
    val builder = new StringBuilder(64)
    val isTraitSignature = sym0.enclosingClass.is(Trait)

    def superSig(cls: Symbol, parents: List[Type]): Unit = {
      def isInterfaceOrTrait(sym: Symbol) = sym.is(PureInterface) || sym.is(Trait)

      // a signature should always start with a class
      def ensureClassAsFirstParent(tps: List[Type]) = tps match {
        case Nil => defn.ObjectType :: Nil
        case head :: tail if isInterfaceOrTrait(head.typeSymbol) => defn.ObjectType :: tps
        case _ => tps
      }

      val minParents = minimizeParents(cls, parents)
      val validParents =
        if (isTraitSignature)
          // java is unthrilled about seeing interfaces inherit from classes
          minParents filter (p => isInterfaceOrTrait(p.classSymbol))
        else minParents

      val ps = ensureClassAsFirstParent(validParents)
      ps.foreach(boxedSig)
    }

    def boxedSig(tp: Type): Unit = jsig(tp.widenDealias, primitiveOK = false)

    def boundsSig(bounds: List[Type]): Unit = {
      val (isTrait, isClass) = bounds partition (_.typeSymbol.is(Trait))
      isClass match {
        case Nil    => builder.append(':') // + boxedSig(ObjectTpe)
        case x :: _ => builder.append(':'); boxedSig(x)
      }
      isTrait.foreach { tp =>
        builder.append(':')
        boxedSig(tp)
      }
    }

    def paramSig(param: LambdaParam): Unit = {
      builder.append(sanitizeName(param.paramName))
      boundsSig(hiBounds(param.paramInfo.bounds))
    }

    def polyParamSig(tparams: List[LambdaParam]): Unit =
      if (tparams.nonEmpty) {
        builder.append('<')
        tparams.foreach(paramSig)
        builder.append('>')
      }

    def typeParamSig(name: Name): Unit = {
      builder.append(ClassfileConstants.TVAR_TAG)
      builder.append(sanitizeName(name))
      builder.append(';')
    }

    def methodResultSig(restpe: Type): Unit = {
      val finalType = restpe.finalResultType
      val sym = finalType.typeSymbol
      if (sym == defn.UnitClass || sym == defn.BoxedUnitModule || sym0.isConstructor)
        builder.append(ClassfileConstants.VOID_TAG)
      else
        jsig(finalType)
    }

    // This works as long as mangled names are always valid valid Java identifiers,
    // if we change our name encoding, we'll have to `throw new UnknownSig` here for
    // names which are not valid Java identifiers (see git history of this method).
    def sanitizeName(name: Name): String = name.mangledString

    // Anything which could conceivably be a module (i.e. isn't known to be
    // a type parameter or similar) must go through here or the signature is
    // likely to end up with Foo<T>.Empty where it needs Foo<T>.Empty$.
    def fullNameInSig(sym: Symbol): Unit = {
      val name = ctx.atPhase(ctx.genBCodePhase) { sanitizeName(sym.fullName).replace('.', '/') }
      builder.append('L').append(name)
    }

    def classSig(sym: Symbol, pre: Type = NoType, args: List[Type] = Nil): Unit = {
      def argSig(tp: Type): Unit =
        tp match {
          case bounds: TypeBounds =>
            if (!(defn.AnyType <:< bounds.hi)) {
              builder.append('+')
              boxedSig(bounds.hi)
            }
            else if (!(bounds.lo <:< defn.NothingType)) {
              builder.append('-')
              boxedSig(bounds.lo)
            }
            else builder.append('*')
          case PolyType(_, res) =>
            builder.append('*') // scala/bug#7932
          case _: HKTypeLambda =>
            fullNameInSig(tp.typeSymbol)
            builder.append(';')
          case _ =>
            boxedSig(tp)
        }

      if (pre.exists) {
        val preRebound = pre.baseType(sym.owner) // #2585
        if (needsJavaSig(preRebound, Nil)) {
          val i = builder.length()
          jsig(preRebound)
          if (builder.charAt(i) == 'L') {
            builder.delete(builder.length() - 1, builder.length())// delete ';'
                                                                  // If the prefix is a module, drop the '$'. Classes (or modules) nested in modules
                                                                  // are separated by a single '$' in the filename: `object o { object i }` is o$i$.
            if (preRebound.typeSymbol.is(ModuleClass))
              builder.delete(builder.length() - 1, builder.length())

            // Ensure every '.' in the generated signature immediately follows
            // a close angle bracket '>'.  Any which do not are replaced with '$'.
            // This arises due to multiply nested classes in the face of the
            // rewriting explained at rebindInnerClass.

            // TODO revisit this. Does it align with javac for code that can be expressed in both languages?
            val delimiter = if (builder.charAt(builder.length() - 1) == '>') '.' else '$'
            builder.append(delimiter).append(sanitizeName(sym.name))
          }
          else fullNameInSig(sym)
        }
        else fullNameInSig(sym)
      }
      else fullNameInSig(sym)

      if (args.nonEmpty) {
        builder.append('<')
        args foreach argSig
        builder.append('>')
      }
      builder.append(';')
    }

    @noinline
    def jsig(tp0: Type, toplevel: Boolean = false, primitiveOK: Boolean = true): Unit = {

      val tp = tp0.dealias
      tp match {

        case ref @ TypeParamRef(_: PolyType, _) =>
          typeParamSig(ref.paramName.lastPart)

        case defn.ArrayOf(elemtp) =>
          if (isUnboundedGeneric(elemtp))
            jsig(defn.ObjectType)
          else {
            builder.append(ClassfileConstants.ARRAY_TAG)
            jsig(elemtp)
          }

        case RefOrAppliedType(sym, pre, args) =>
          if (sym == defn.PairClass && tp.tupleArity > Definitions.MaxTupleArity)
            jsig(defn.TupleXXLClass.typeRef)
          else if (isTypeParameterInSig(sym, sym0)) {
            assert(!sym.isAliasType, "Unexpected alias type: " + sym)
            typeParamSig(sym.name.lastPart)
          }
          else if (defn.specialErasure.contains(sym))
            jsig(defn.specialErasure(sym).typeRef)
          else if (sym == defn.UnitClass || sym == defn.BoxedUnitModule)
            jsig(defn.BoxedUnitClass.typeRef)
          else if (sym == defn.NothingClass)
            jsig(defn.RuntimeNothingModuleRef)
          else if (sym == defn.NullClass)
            jsig(defn.RuntimeNullModuleRef)
          else if (sym.isPrimitiveValueClass)
            if (!primitiveOK) jsig(defn.ObjectType)
            else if (sym == defn.UnitClass) jsig(defn.BoxedUnitClass.typeRef)
            else builder.append(defn.typeTag(sym.info))
          else if (ValueClasses.isDerivedValueClass(sym)) {
            val unboxed     = ValueClasses.valueClassUnbox(sym.asClass).info.finalResultType
            val unboxedSeen = tp.memberInfo(ValueClasses.valueClassUnbox(sym.asClass)).finalResultType
            if (unboxedSeen.isPrimitiveValueType && !primitiveOK)
              classSig(sym, pre, args)
            else
              jsig(unboxedSeen, toplevel, primitiveOK)
          }
          else if (defn.isSyntheticFunctionClass(sym)) {
            val erasedSym = defn.erasedFunctionClass(sym)
            classSig(erasedSym, pre, if (erasedSym.typeParams.isEmpty) Nil else args)
          }
          else if (sym.isClass)
            classSig(sym, pre, args)
          else
            jsig(erasure(tp), toplevel, primitiveOK)

        case ExprType(restpe) if toplevel =>
          builder.append("()")
          methodResultSig(restpe)

        case ExprType(restpe) =>
          jsig(defn.FunctionType(0).appliedTo(restpe))

        case PolyType(tparams, mtpe: MethodType) =>
          assert(tparams.nonEmpty)
          if (toplevel) polyParamSig(tparams)
          jsig(mtpe)

        // Nullary polymorphic method
        case PolyType(tparams, restpe) =>
          assert(tparams.nonEmpty)
          if (toplevel) polyParamSig(tparams)
          builder.append("()")
          methodResultSig(restpe)

        case mtpe: MethodType =>
          // erased method parameters do not make it to the bytecode.
          def effectiveParamInfoss(t: Type)(implicit ctx: Context): List[List[Type]] = t match {
            case t: MethodType if t.isErasedMethod => effectiveParamInfoss(t.resType)
            case t: MethodType => t.paramInfos :: effectiveParamInfoss(t.resType)
            case _ => Nil
          }
          val params = effectiveParamInfoss(mtpe).flatten
          val restpe = mtpe.finalResultType
          builder.append('(')
          // TODO: Update once we support varargs
          params.foreach { tp =>
            jsig(tp)
          }
          builder.append(')')
          methodResultSig(restpe)

        case AndType(tp1, tp2) =>
          jsig(intersectionDominator(tp1 :: tp2 :: Nil), primitiveOK = primitiveOK)

        case ci: ClassInfo =>
          def polyParamSig(tparams: List[TypeParamInfo]): Unit =
            if (tparams.nonEmpty) {
              builder.append('<')
              tparams.foreach { tp =>
                builder.append(sanitizeName(tp.paramName.lastPart))
                boundsSig(hiBounds(tp.paramInfo.bounds))
              }
              builder.append('>')
            }
          val tParams = tp.typeParams
          if (toplevel) polyParamSig(tParams)
          superSig(ci.typeSymbol, ci.parents)

        case AnnotatedType(atp, _) =>
          jsig(atp, toplevel, primitiveOK)

        case hktl: HKTypeLambda =>
          jsig(hktl.finalResultType, toplevel, primitiveOK)

        case _ =>
          val etp = erasure(tp)
          if (etp eq tp) throw new UnknownSig
          else jsig(etp, toplevel, primitiveOK)
      }
    }
    val throwsArgs = sym0.annotations flatMap ThrownException.unapply
    if (needsJavaSig(info, throwsArgs))
      try {
        jsig(info, toplevel = true)
        throwsArgs.foreach { t =>
          builder.append('^')
          jsig(t, toplevel = true)
        }
        Some(builder.toString)
      }
      catch { case _: UnknownSig => None }
    else None
  }

  private class UnknownSig extends Exception

  /** The intersection dominator (SLS 3.7) of a list of types is computed as follows.
    *
    *  - If the list contains one or more occurrences of scala.Array with
    *    type parameters El1, El2, ... then the dominator is scala.Array with
    *    type parameter of intersectionDominator(List(El1, El2, ...)).           <--- @PP: not yet in spec.
    *  - Otherwise, the list is reduced to a subsequence containing only types
    *    which are not subtypes of other listed types (the span.)
    *  - If the span is empty, the dominator is Object.
    *  - If the span contains a class Tc which is not a trait and which is
    *    not Object, the dominator is Tc.                                        <--- @PP: "which is not Object" not in spec.
    *  - Otherwise, the dominator is the first element of the span.
    */
  private def intersectionDominator(parents: List[Type])(implicit ctx: Context): Type =
    if (parents.isEmpty) defn.ObjectType
    else {
      val psyms = parents map (_.typeSymbol)
      if (psyms contains defn.ArrayClass)
        // treat arrays specially
        defn.ArrayType.appliedTo(intersectionDominator(parents.filter(_.typeSymbol == defn.ArrayClass).map(t => t.argInfos.head)))
      else {
        // implement new spec for erasure of refined types.
        def isUnshadowed(psym: Symbol) =
          !(psyms exists (qsym => (psym ne qsym) && (qsym isSubClass psym)))
        val cs = parents.iterator.filter { p => // isUnshadowed is a bit expensive, so try classes first
          val psym = p.classSymbol
          psym.ensureCompleted()
          psym.isClass && !psym.is(Trait) && isUnshadowed(psym)
        }
        (if (cs.hasNext) cs else parents.iterator.filter(p => isUnshadowed(p.classSymbol))).next()
      }
    }

  /* Drop redundant types (ones which are implemented by some other parent) from the immediate parents.
   * This is important on Android because there is otherwise an interface explosion.
   */
  private def minimizeParents(cls: Symbol, parents: List[Type])(implicit ctx: Context): List[Type] = if (parents.isEmpty) parents else {
    // val requiredDirect: Symbol => Boolean = requiredDirectInterfaces.getOrElse(cls, Set.empty)
    var rest   = parents.tail
    var leaves = collection.mutable.ListBuffer.empty[Type] += parents.head
    while (rest.nonEmpty) {
      val candidate = rest.head
      val candidateSym = candidate.typeSymbol
      // val required = requiredDirect(candidateSym) || !leaves.exists(t => t.typeSymbol isSubClass candidateSym)
      val required = !leaves.exists(t => t.typeSymbol.isSubClass(candidateSym))
      if (required) {
        leaves = leaves filter { t =>
          val ts = t.typeSymbol
          !(ts.is(Trait) || ts.is(PureInterface)) || !candidateSym.isSubClass(ts)
          // requiredDirect(ts) || !ts.isTraitOrInterface || !candidateSym.isSubClass(ts)
        }
        leaves += candidate
      }
      rest = rest.tail
    }
    leaves.toList
  }

  private def hiBounds(bounds: TypeBounds)(implicit ctx: Context): List[Type] = bounds.hi.widenDealias match {
    case AndType(tp1, tp2) => hiBounds(tp1.bounds) ::: hiBounds(tp2.bounds)
    case tp => tp :: Nil
  }


  // only refer to type params that will actually make it into the sig, this excludes:
  // * higher-order type parameters
  // * type parameters appearing in method parameters
  // * type members not visible in an enclosing template
  private def isTypeParameterInSig(sym: Symbol, initialSymbol: Symbol)(implicit ctx: Context) =
    !sym.maybeOwner.isTypeParam &&
      sym.isTypeParam && (
      sym.isContainedIn(initialSymbol.topLevelClass) ||
        (initialSymbol.is(Method) && initialSymbol.typeParams.contains(sym))
      )

  /** Extracts the type of the thrown exception from an AnnotationInfo.
    *
    * Supports both “old-style” `@throws(classOf[Exception])`
    * as well as “new-style” `@throws[Exception]("cause")` annotations.
    */
  private object ThrownException {
    def unapply(ann: Annotation)(implicit ctx: Context): Option[Type] =
      ann.tree match {
        case Apply(TypeApply(fun, List(tpe)), _) if tpe.isType && fun.symbol.owner == defn.ThrowsAnnot && fun.symbol.isConstructor =>
          Some(tpe.typeOpt)
        case _ =>
          None
      }
  }

  // @M #2585 when generating a java generic signature that includes
  // a selection of an inner class p.I, (p = `pre`, I = `cls`) must
  // rewrite to p'.I, where p' refers to the class that directly defines
  // the nested class I.
  //
  // See also #2585 marker in javaSig: there, type arguments must be
  // included (use pre.baseType(cls.owner)).
  //
  // This requires that cls.isClass.
  private def rebindInnerClass(pre: Type, cls: Symbol)(implicit ctx: Context): Type = {
    val owner = cls.owner
    if (owner.is(PackageClass) || owner.isTerm) pre else cls.owner.info /* .tpe_* */
  }

  private object RefOrAppliedType {
    def unapply(tp: Type)(implicit ctx: Context): Option[(Symbol, Type, List[Type])] = tp match {
      case TypeParamRef(_, _) =>
        Some((tp.typeSymbol, tp, Nil))
      case TermParamRef(_, _) =>
        Some((tp.termSymbol, tp, Nil))
      case TypeRef(pre, _) if !tp.typeSymbol.isAliasType =>
        val sym = tp.typeSymbol
        Some((sym, pre, Nil))
      case AppliedType(pre, args) =>
        Some((pre.typeSymbol, pre, args))
      case _ =>
        None
    }
  }

  private def needsJavaSig(tp: Type, throwsArgs: List[Type])(implicit ctx: Context): Boolean = !ctx.settings.YnoGenericSig.value && {
      def needs(tp: Type) = (new NeedsSigCollector).apply(false, tp)
      needs(tp) || throwsArgs.exists(needs)
  }

  private class NeedsSigCollector(implicit ctx: Context) extends TypeAccumulator[Boolean] {
    override def apply(x: Boolean, tp: Type): Boolean =
      if (!x)
        tp match {
          case RefinedType(parent, refinedName, refinedInfo) =>
            val sym = parent.typeSymbol
            if (sym == defn.ArrayClass) foldOver(x, refinedInfo)
            else true
          case tref @ TypeRef(pre, name) =>
            val sym = tref.typeSymbol
            if (sym.is(TypeParam) || sym.typeParams.nonEmpty) true
            else if (sym.isClass) foldOver(x, rebindInnerClass(pre, sym)) // #2585
            else foldOver(x, pre)
          case PolyType(_, _) =>
            true
          case ClassInfo(_, _, parents, _, _) =>
            foldOver(tp.typeParams.nonEmpty, parents)
          case AnnotatedType(tpe, _) =>
            foldOver(x, tpe)
          case proxy: TypeProxy =>
            foldOver(x, proxy)
          case _ =>
            foldOver(x, tp)
        }
      else x
  }
}
