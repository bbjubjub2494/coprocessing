package dotty.tools.dotc.core

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.JavaDefined
import dotty.tools.dotc.core.StdNames.{jnme, nme}
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import NullOpsDecorator._

/** This module defines methods to interpret types of Java symbols, which are implicitly nullable in Java,
 *  as Scala types, which are explicitly nullable.
 *
 *  The transformation is (conceptually) a function `n` that adheres to the following rules:
 *    (1) n(T)              = T|UncheckedNull              if T is a reference type
 *    (2) n(T)              = T                       if T is a value type
 *    (3) n(C[T])           = C[T]|UncheckedNull           if C is Java-defined
 *    (4) n(C[T])           = C[n(T)]|UncheckedNull        if C is Scala-defined
 *    (5) n(A|B)            = n(A)|n(B)|UncheckedNull
 *    (6) n(A&B)            = n(A) & n(B)
 *    (7) n((A1, ..., Am)R) = (n(A1), ..., n(Am))n(R) for a method with arguments (A1, ..., Am) and return type R
 *    (8) n(T)              = T                       otherwise
 *
 *   Treatment of generics (rules 3 and 4):
 *     - if `C` is Java-defined, then `n(C[T]) = C[T]|UncheckedNull`. That is, we don't recurse
 *       on the type argument, and only add UncheckedNull on the outside. This is because
 *       `C` itself will be nullified, and in particular so will be usages of `C`'s type argument within C's body.
 *       e.g. calling `get` on a `java.util.List[String]` already returns `String|Null` and not `String`, so
 *       we don't need to write `java.util.List[String|Null]`.
 *     - if `C` is Scala-defined, however, then we want `n(C[T]) = C[n(T)]|UncheckedNull`. This is because
 *       `C` won't be nullified, so we need to indicate that its type argument is nullable.
 *
 *   Notice that since the transformation is only applied to types attached to Java symbols, it doesn't need
 *   to handle the full spectrum of Scala types. Additionally, some kinds of symbols like constructors and
 *   enum instances get special treatment.
 */
object JavaNullInterop {

  /** Transforms the type `tp` of Java member `sym` to be explicitly nullable.
   *  `tp` is needed because the type inside `sym` might not be set when this method is called.
   *
   *  e.g. given a Java method
   *  String foo(String arg) { return arg; }
   *
   *  After calling `nullifyMember`, Scala will see the method as
   *
   *  def foo(arg: String|UncheckedNull): String|UncheckedNull
   *
   *  This nullability function uses `UncheckedNull` instead of vanilla `Null`, for usability.
   *  This means that we can select on the return of `foo`:
   *
   *  val len = foo("hello").length
   *
   *  But the selection can throw an NPE if the returned value is `null`.
   */
  def nullifyMember(sym: Symbol, tp: Type, isEnumValueDef: Boolean)(implicit ctx: Context): Type = {
    assert(ctx.explicitNulls)
    assert(sym.is(JavaDefined), "can only nullify java-defined members")

    // Some special cases when nullifying the type
    if (isEnumValueDef || sym.name == nme.TYPE_)
      // Don't nullify the `TYPE` field in every class and Java enum instances
      tp
    else if (sym.name == nme.toString_ || sym.isConstructor || hasNotNullAnnot(sym))
      // Don't nullify the return type of the `toString` method.
      // Don't nullify the return type of constructors.
      // Don't nullify the return type of methods with a not-null annotation.
      nullifyExceptReturnType(tp)
    else
      // Otherwise, nullify everything
      nullifyType(tp)
  }

  private def hasNotNullAnnot(sym: Symbol)(implicit ctx: Context): Boolean =
    ctx.definitions.NotNullAnnots.exists(nna => sym.unforcedAnnotation(nna).isDefined)

  /** If tp is a MethodType, the parameters and the inside of return type are nullified,
   *  but the result return type is not nullable.
   *  If tp is a type of a field, the inside of the type is nullified,
   *  but the result type is not nullable.
   */
  private def nullifyExceptReturnType(tp: Type)(implicit ctx: Context): Type =
    new JavaNullMap(true)(ctx)(tp)

  /** Nullifies a Java type by adding `| UncheckedNull` in the relevant places. */
  private def nullifyType(tp: Type)(implicit ctx: Context): Type =
    new JavaNullMap(false)(ctx)(tp)

  /** A type map that implements the nullification function on types. Given a Java-sourced type, this adds `| UncheckedNull`
   *  in the right places to make the nulls explicit in Scala.
   *
   *  @param outermostLevelAlreadyNullable whether this type is already nullable at the outermost level.
   *                                       For example, `Array[String]|UncheckedNull` is already nullable at the
   *                                       outermost level, but `Array[String|UncheckedNull]` isn't.
   *                                       If this parameter is set to true, then the types of fields, and the return
   *                                       types of methods will not be nullified.
   *                                       This is useful for e.g. constructors, and also so that `A & B` is nullified
   *                                       to `(A & B) | UncheckedNull`, instead of `(A|UncheckedNull & B|UncheckedNull) | UncheckedNull`.
   */
  private class JavaNullMap(var outermostLevelAlreadyNullable: Boolean)(implicit ctx: Context) extends TypeMap {
    /** Should we nullify `tp` at the outermost level? */
    def needsNull(tp: Type): Boolean =
      !outermostLevelAlreadyNullable && (tp match {
        case tp: TypeRef =>
          // We don't modify value types because they're non-nullable even in Java.
          !tp.symbol.isValueClass &&
          // We don't modify `Any` because it's already nullable.
          !tp.isRef(defn.AnyClass) &&
          // We don't nullify Java varargs at the top level.
          // Example: if `setNames` is a Java method with signature `void setNames(String... names)`,
          // then its Scala signature will be `def setNames(names: (String|UncheckedNull)*): Unit`.
          // This is because `setNames(null)` passes as argument a single-element array containing the value `null`,
          // and not a `null` array.
          !tp.isRef(defn.RepeatedParamClass)
        case _ => true
      })

    override def apply(tp: Type): Type = tp match {
      case tp: TypeRef if needsNull(tp) => OrUncheckedNull(tp)
      case appTp @ AppliedType(tycon, targs) =>
        val oldOutermostNullable = outermostLevelAlreadyNullable
        // We don't make the outmost levels of type arguments nullable if tycon is Java-defined.
        // This is because Java classes are _all_ nullified, so both `java.util.List[String]` and
        // `java.util.List[String|Null]` contain nullable elements.
        outermostLevelAlreadyNullable = tp.classSymbol.is(JavaDefined)
        val targs2 = targs map this
        outermostLevelAlreadyNullable = oldOutermostNullable
        val appTp2 = derivedAppliedType(appTp, tycon, targs2)
        if (needsNull(tycon)) OrUncheckedNull(appTp2) else appTp2
      case ptp: PolyType =>
        derivedLambdaType(ptp)(ptp.paramInfos, this(ptp.resType))
      case mtp: MethodType =>
        val oldOutermostNullable = outermostLevelAlreadyNullable
        outermostLevelAlreadyNullable = false
        val paramInfos2 = mtp.paramInfos map this
        outermostLevelAlreadyNullable = oldOutermostNullable
        derivedLambdaType(mtp)(paramInfos2, this(mtp.resType))
      case tp: TypeAlias => mapOver(tp)
      case tp: AndType =>
        // nullify(A & B) = (nullify(A) & nullify(B)) | UncheckedNull, but take care not to add
        // duplicate `UncheckedNull`s at the outermost level inside `A` and `B`.
        outermostLevelAlreadyNullable = true
        OrUncheckedNull(derivedAndType(tp, this(tp.tp1), this(tp.tp2)))
      case tp: TypeParamRef if needsNull(tp) => OrUncheckedNull(tp)
      // In all other cases, return the type unchanged.
      // In particular, if the type is a ConstantType, then we don't nullify it because it is the
      // type of a final non-nullable field.
      case _ => tp
    }
  }
}
