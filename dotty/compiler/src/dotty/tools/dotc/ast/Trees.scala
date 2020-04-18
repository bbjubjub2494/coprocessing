package dotty.tools
package dotc
package ast

import core._
import Types._, Names._, NameOps._, Flags._, util.Spans._, Contexts._, Constants._
import typer.ProtoTypes
import SymDenotations._, Symbols._, Denotations._, StdNames._, Comments._
import language.higherKinds
import collection.mutable.ListBuffer
import printing.Printer
import printing.Texts.Text
import util.{Stats, Attachment, Property, SourceFile, NoSource, SourcePosition}
import config.Config
import annotation.internal.sharable
import annotation.unchecked.uncheckedVariance
import annotation.constructorOnly
import Decorators._
import dotty.tools.dotc.core.tasty.TreePickler.Hole

object Trees {

  // Note: it would be more logical to make Untyped = Nothing.
  // However, this interacts in a bad way with Scala's current type inference.
  // In fact, we cannot write something like Select(pre, name), where pre is
  // of type Tree[Nothing]; type inference will treat the Nothing as an uninstantiated
  // value and will not infer Nothing as the type parameter for Select.
  // We should come back to this issue once type inference is changed.
  type Untyped = Null

  /** The total number of created tree nodes, maintained if Stats.enabled */
  @sharable var ntrees: Int = 0

  /** Property key for trees with documentation strings attached */
  val DocComment: Property.StickyKey[Comments.Comment] = Property.StickyKey()

  /** Property key for backquoted identifiers and definitions */
  val Backquoted: Property.StickyKey[Unit] = Property.StickyKey()

  /** Trees take a parameter indicating what the type of their `tpe` field
   *  is. Two choices: `Type` or `Untyped`.
   *  Untyped trees have type `Tree[Untyped]`.
   *
   *  Tree typing uses a copy-on-write implementation:
   *
   *   - You can never observe a `tpe` which is `null` (throws an exception)
   *   - So when creating a typed tree with `withType` we can re-use
   *     the existing tree transparently, assigning its `tpe` field,
   *     provided it was `null` before.
   *   - It is impossible to embed untyped trees in typed ones.
   *   - Typed trees can be embedded in untyped ones provided they are rooted
   *     in a TypedSplice node.
   *   - Type checking an untyped tree should remove all embedded `TypedSplice`
   *     nodes.
   */
  abstract class Tree[-T >: Untyped](implicit @constructorOnly src: SourceFile)
  extends Positioned
     with Product
     with Attachment.Container
     with printing.Showable {

    if (Stats.enabled) ntrees += 1

    /** This tree, widened to `Positioned`. Used to make clear we only need the
     *  position, typically for error reporting.
     */
    final def posd: Positioned = this

    /** The type  constructor at the root of the tree */
    type ThisTree[T >: Untyped] <: Tree[T]

    protected var myTpe: T @uncheckedVariance = _

    /** Destructively set the type of the tree. This should be called only when it is known that
     *  it is safe under sharing to do so. One use-case is in the withType method below
     *  which implements copy-on-write. Another use-case is in method interpolateAndAdapt in Typer,
     *  where we overwrite with a simplified version of the type itself.
     */
    private[dotc] def overwriteType(tpe: T): Unit =
      myTpe = tpe

    /** The type of the tree. In case of an untyped tree,
     *   an UnAssignedTypeException is thrown. (Overridden by empty trees)
     */
    final def tpe: T @uncheckedVariance = {
      if (myTpe == null)
        throw UnAssignedTypeException(this)
      myTpe
    }

    /** Copy `tpe` attribute from tree `from` into this tree, independently
     *  whether it is null or not.
    final def copyAttr[U >: Untyped](from: Tree[U]): ThisTree[T] = {
      val t1 = this.withSpan(from.span)
      val t2 =
        if (from.myTpe != null) t1.withType(from.myTpe.asInstanceOf[Type])
        else t1
      t2.asInstanceOf[ThisTree[T]]
    }
     */

    /** Return a typed tree that's isomorphic to this tree, but has given
     *  type. (Overridden by empty trees)
     */
    def withType(tpe: Type)(implicit ctx: Context): ThisTree[Type] = {
      if (tpe.isInstanceOf[ErrorType])
        assert(!Config.checkUnreportedErrors ||
               ctx.reporter.errorsReported ||
               ctx.settings.YshowPrintErrors.value
                 // under -Yshow-print-errors, errors might arise during printing, but they do not count as reported
              )
      else if (Config.checkTreesConsistent)
        checkChildrenTyped(productIterator)
      withTypeUnchecked(tpe)
    }

    /** Check that typed trees don't refer to untyped ones, except if
     *   - the parent tree is an import, or
     *   - the child tree is an identifier, or
     *   - errors were reported
     */
    private def checkChildrenTyped(it: Iterator[Any])(implicit ctx: Context): Unit =
      if (!this.isInstanceOf[Import[?]])
        while (it.hasNext)
          it.next() match {
            case x: Ident[?] => // untyped idents are used in a number of places in typed trees
            case x: Tree[?] =>
              assert(x.hasType || ctx.reporter.errorsReported,
                     s"$this has untyped child $x")
            case xs: List[?] => checkChildrenTyped(xs.iterator)
            case _ =>
          }

    def withTypeUnchecked(tpe: Type): ThisTree[Type] = {
      val tree =
        (if (myTpe == null ||
          (myTpe.asInstanceOf[AnyRef] eq tpe.asInstanceOf[AnyRef])) this
         else cloneIn(source)).asInstanceOf[Tree[Type]]
      tree overwriteType tpe
      tree.asInstanceOf[ThisTree[Type]]
    }

    /** Does the tree have its type field set? Note: this operation is not
     *  referentially transparent, because it can observe the withType
     *  modifications. Should be used only in special circumstances (we
     *  need it for printing trees with optional type info).
     */
    final def hasType: Boolean = myTpe != null

    final def typeOpt: Type = myTpe match {
      case tp: Type => tp
      case _ => NoType
    }

    /** The denotation referred to by this tree.
     *  Defined for `DenotingTree`s and `ProxyTree`s, NoDenotation for other
     *  kinds of trees
     */
    def denot(implicit ctx: Context): Denotation = NoDenotation

    /** Shorthand for `denot.symbol`. */
    final def symbol(implicit ctx: Context): Symbol = denot.symbol

    /** Does this tree represent a type? */
    def isType: Boolean = false

    /** Does this tree represent a term? */
    def isTerm: Boolean = false

    /** Is this a legal part of a pattern which is not at the same time a term? */
    def isPattern: Boolean = false

    /** Does this tree define a new symbol that is not defined elsewhere? */
    def isDef: Boolean = false

    /** Is this tree either the empty tree or the empty ValDef or an empty type ident? */
    def isEmpty: Boolean = false

    /** Convert tree to a list. Gives a singleton list, except
     *  for thickets which return their element trees.
     */
    def toList: List[Tree[T]] = this :: Nil

    /** if this tree is the empty tree, the alternative, else this tree */
    def orElse[U >: Untyped <: T](that: => Tree[U]): Tree[U] =
      if (this eq genericEmptyTree) that else this

    /** The number of nodes in this tree */
    def treeSize: Int = {
      var s = 1
      def addSize(elem: Any): Unit = elem match {
        case t: Tree[?] => s += t.treeSize
        case ts: List[?] => ts foreach addSize
        case _ =>
      }
      productIterator foreach addSize
      s
    }

    /** If this is a thicket, perform `op` on each of its trees
     *  otherwise, perform `op` ion tree itself.
     */
    def foreachInThicket(op: Tree[T] => Unit): Unit = op(this)

    override def toText(printer: Printer): Text = printer.toText(this)

    def sameTree(that: Tree[?]): Boolean = {
      def isSame(x: Any, y: Any): Boolean =
        x.asInstanceOf[AnyRef].eq(y.asInstanceOf[AnyRef]) || {
          x match {
            case x: Tree[?] =>
              y match {
                case y: Tree[?] => x.sameTree(y)
                case _ => false
              }
            case x: List[?] =>
              y match {
                case y: List[?] => x.corresponds(y)(isSame)
                case _ => false
              }
            case _ =>
              false
          }
        }
      this.getClass == that.getClass && {
        val it1 = this.productIterator
        val it2 = that.productIterator
        it1.corresponds(it2)(isSame)
      }
    }

    override def hashCode(): Int = uniqueId // for debugging; was: System.identityHashCode(this)
    override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
  }

  class UnAssignedTypeException[T >: Untyped](tree: Tree[T]) extends RuntimeException {
    override def getMessage: String = s"type of $tree is not assigned"
  }

  type LazyTree[-T >: Untyped] = Tree[T] | Lazy[Tree[T]]
  type LazyTreeList[-T >: Untyped] = List[Tree[T]] | Lazy[List[Tree[T]]]

  // ------ Categories of trees -----------------------------------

  /** Instances of this class are trees for which isType is definitely true.
   *  Note that some trees have isType = true without being TypTrees (e.g. Ident, AnnotatedTree)
   */
  trait TypTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: TypTree[T]
    override def isType: Boolean = true
  }

  /** Instances of this class are trees for which isTerm is definitely true.
   *  Note that some trees have isTerm = true without being TermTrees (e.g. Ident, AnnotatedTree)
   */
  trait TermTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: TermTree[T]
    override def isTerm: Boolean = true
  }

  /** Instances of this class are trees which are not terms but are legal
   *  parts of patterns.
   */
  trait PatternTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: PatternTree[T]
    override def isPattern: Boolean = true
  }

  /** Tree's denotation can be derived from its type */
  abstract class DenotingTree[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends Tree[T] {
    type ThisTree[-T >: Untyped] <: DenotingTree[T]
    override def denot(implicit ctx: Context): Denotation = typeOpt match {
      case tpe: NamedType => tpe.denot
      case tpe: ThisType => tpe.cls.denot
      case tpe: AnnotatedType => tpe.stripAnnots match {
        case tpe: NamedType => tpe.denot
        case tpe: ThisType => tpe.cls.denot
        case _ => NoDenotation
      }
      case _ => NoDenotation
    }
  }

  /** Tree's denot/isType/isTerm properties come from a subtree
   *  identified by `forwardTo`.
   */
  abstract class ProxyTree[-T >: Untyped](implicit @constructorOnly src: SourceFile)  extends Tree[T] {
    type ThisTree[-T >: Untyped] <: ProxyTree[T]
    def forwardTo: Tree[T]
    override def denot(implicit ctx: Context): Denotation = forwardTo.denot
    override def isTerm: Boolean = forwardTo.isTerm
    override def isType: Boolean = forwardTo.isType
  }

  /** Tree has a name */
  abstract class NameTree[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends DenotingTree[T] {
    type ThisTree[-T >: Untyped] <: NameTree[T]
    def name: Name
  }

  /** Tree refers by name to a denotation */
  abstract class RefTree[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends NameTree[T] {
    type ThisTree[-T >: Untyped] <: RefTree[T]
    def qualifier: Tree[T]
    override def isType: Boolean = name.isTypeName
    override def isTerm: Boolean = name.isTermName
  }

  /** Tree defines a new symbol */
  trait DefTree[-T >: Untyped] extends DenotingTree[T] {
    type ThisTree[-T >: Untyped] <: DefTree[T]

    private var myMods: untpd.Modifiers = null

    private[dotc] def rawMods: untpd.Modifiers =
      if (myMods == null) untpd.EmptyModifiers else myMods

    def withAnnotations(annots: List[untpd.Tree]): ThisTree[Untyped] = withMods(rawMods.withAnnotations(annots))

    def withMods(mods: untpd.Modifiers): ThisTree[Untyped] = {
      val tree = if (myMods == null || (myMods == mods)) this else cloneIn(source)
      tree.setMods(mods)
      tree.asInstanceOf[ThisTree[Untyped]]
    }

    def withFlags(flags: FlagSet): ThisTree[Untyped] = withMods(untpd.Modifiers(flags))
    def withAddedFlags(flags: FlagSet): ThisTree[Untyped] = withMods(rawMods | flags)

    /** Destructively update modifiers. To be used with care. */
    def setMods(mods: untpd.Modifiers): Unit = myMods = mods

    override def isDef: Boolean = true
    def namedType: NamedType = tpe.asInstanceOf[NamedType]
  }

  abstract class NamedDefTree[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends NameTree[T] with DefTree[T] {
    type ThisTree[-T >: Untyped] <: NamedDefTree[T]

    /** The position of the name defined by this definition.
     *  This is a point position if the definition is synthetic, or a range position
     *  if the definition comes from source.
     *  It might also be that the definition does not have a position (for instance when synthesized by
     *  a calling chain from `viewExists`), in that case the return position is NoSpan.
     *  Overridden in Bind
     */
    def nameSpan: Span =
      if (span.exists) {
        val point = span.point
        if (rawMods.is(Synthetic) || name.toTermName == nme.ERROR) Span(point)
        else {
          val realName = name.stripModuleClassSuffix.lastPart.toString
          Span(point, point + realName.length, point)
        }
      }
      else span
  }

  /** Tree defines a new symbol and carries modifiers.
   *  The position of a MemberDef contains only the defined identifier or pattern.
   *  The envelope of a MemberDef contains the whole definition and has its point
   *  on the opening keyword (or the next token after that if keyword is missing).
   */
  abstract class MemberDef[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends NamedDefTree[T] {
    type ThisTree[-T >: Untyped] <: MemberDef[T]

    def rawComment: Option[Comment] = getAttachment(DocComment)

    def setComment(comment: Option[Comment]): this.type = {
      comment.map(putAttachment(DocComment, _))
      this
    }
  }

  /** A ValDef or DefDef tree */
  abstract class ValOrDefDef[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends MemberDef[T] with WithLazyField[Tree[T]] {
    type ThisTree[-T >: Untyped] <: ValOrDefDef[T]
    def name: TermName
    def tpt: Tree[T]
    def unforcedRhs: LazyTree[T] = unforced
    def rhs(implicit ctx: Context): Tree[T] = forceIfLazy
  }

  // ----------- Tree case classes ------------------------------------

  /** name */
  case class Ident[-T >: Untyped] private[ast] (name: Name)(implicit @constructorOnly src: SourceFile)
    extends RefTree[T] {
    type ThisTree[-T >: Untyped] = Ident[T]
    def qualifier: Tree[T] = genericEmptyTree

    def isBackquoted: Boolean = hasAttachment(Backquoted)
  }

  class SearchFailureIdent[-T >: Untyped] private[ast] (name: Name)(implicit @constructorOnly src: SourceFile)
    extends Ident[T](name) {
    override def toString: String = s"SearchFailureIdent($name)"
  }

  /** qualifier.name, or qualifier#name, if qualifier is a type */
  case class Select[-T >: Untyped] private[ast] (qualifier: Tree[T], name: Name)(implicit @constructorOnly src: SourceFile)
    extends RefTree[T] {
    type ThisTree[-T >: Untyped] = Select[T]
  }

  class SelectWithSig[-T >: Untyped] private[ast] (qualifier: Tree[T], name: Name, val sig: Signature)(implicit @constructorOnly src: SourceFile)
    extends Select[T](qualifier, name) {
    override def toString: String = s"SelectWithSig($qualifier, $name, $sig)"
  }

  /** qual.this */
  case class This[-T >: Untyped] private[ast] (qual: untpd.Ident)(implicit @constructorOnly src: SourceFile)
    extends DenotingTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = This[T]
    // Denotation of a This tree is always the underlying class; needs correction for modules.
    override def denot(implicit ctx: Context): Denotation =
      typeOpt match {
        case tpe @ TermRef(pre, _) if tpe.symbol.is(Module) =>
          tpe.symbol.moduleClass.denot.asSeenFrom(pre)
        case _ =>
          super.denot
      }
  }

  /** C.super[mix], where qual = C.this */
  case class Super[-T >: Untyped] private[ast] (qual: Tree[T], mix: untpd.Ident)(implicit @constructorOnly src: SourceFile)
    extends ProxyTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = Super[T]
    def forwardTo: Tree[T] = qual
  }

  abstract class GenericApply[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends ProxyTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] <: GenericApply[T]
    val fun: Tree[T]
    val args: List[Tree[T]]
    def forwardTo: Tree[T] = fun
  }

  /** fun(args) */
  case class Apply[-T >: Untyped] private[ast] (fun: Tree[T], args: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends GenericApply[T] {
    type ThisTree[-T >: Untyped] = Apply[T]

    def isUsingApply = hasAttachment(untpd.ApplyGiven)
    def setUsingApply() = { putAttachment(untpd.ApplyGiven, ()); this }
  }

  /** fun[args] */
  case class TypeApply[-T >: Untyped] private[ast] (fun: Tree[T], args: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends GenericApply[T] {
    type ThisTree[-T >: Untyped] = TypeApply[T]
  }

  /** const */
  case class Literal[-T >: Untyped] private[ast] (const: Constant)(implicit @constructorOnly src: SourceFile)
    extends Tree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = Literal[T]
  }

  /** new tpt, but no constructor call */
  case class New[-T >: Untyped] private[ast] (tpt: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends Tree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = New[T]
  }

  /** expr : tpt */
  case class Typed[-T >: Untyped] private[ast] (expr: Tree[T], tpt: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends ProxyTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = Typed[T]
    def forwardTo: Tree[T] = expr
  }

  /** name = arg, in a parameter list */
  case class NamedArg[-T >: Untyped] private[ast] (name: Name, arg: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = NamedArg[T]
  }

  /** name = arg, outside a parameter list */
  case class Assign[-T >: Untyped] private[ast] (lhs: Tree[T], rhs: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Assign[T]
  }

  /** { stats; expr } */
  case class Block[-T >: Untyped] private[ast] (stats: List[Tree[T]], expr: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = Block[T]
    override def isType: Boolean = expr.isType
    override def isTerm: Boolean = !isType // this will classify empty trees as terms, which is necessary
  }

  /** if cond then thenp else elsep */
  case class If[-T >: Untyped] private[ast] (cond: Tree[T], thenp: Tree[T], elsep: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = If[T]
    def isInline = false
  }
  class InlineIf[T >: Untyped] private[ast] (cond: Tree[T], thenp: Tree[T], elsep: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends If(cond, thenp, elsep) {
    override def isInline = true
    override def toString = s"InlineIf($cond, $thenp, $elsep)"
  }

  /** A closure with an environment and a reference to a method.
   *  @param env    The captured parameters of the closure
   *  @param meth   A ref tree that refers to the method of the closure.
   *                The first (env.length) parameters of that method are filled
   *                with env values.
   *  @param tpt    Either EmptyTree or a TypeTree. If tpt is EmptyTree the type
   *                of the closure is a function type, otherwise it is the type
   *                given in `tpt`, which must be a SAM type.
   */
  case class Closure[-T >: Untyped] private[ast] (env: List[Tree[T]], meth: Tree[T], tpt: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Closure[T]
  }

  /** selector match { cases } */
  case class Match[-T >: Untyped] private[ast] (selector: Tree[T], cases: List[CaseDef[T]])(implicit @constructorOnly src: SourceFile)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Match[T]
    def isInline = false
  }
  class InlineMatch[T >: Untyped] private[ast] (selector: Tree[T], cases: List[CaseDef[T]])(implicit @constructorOnly src: SourceFile)
    extends Match(selector, cases) {
    override def isInline = true
    override def toString = s"InlineMatch($selector, $cases)"
  }

  /** case pat if guard => body; only appears as child of a Match */
  case class CaseDef[-T >: Untyped] private[ast] (pat: Tree[T], guard: Tree[T], body: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = CaseDef[T]
  }

  /** label[tpt]: { expr } */
  case class Labeled[-T >: Untyped] private[ast] (bind: Bind[T], expr: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends NameTree[T] {
    type ThisTree[-T >: Untyped] = Labeled[T]
    def name: Name = bind.name
  }

  /** return expr
   *  where `from` refers to the method or label from which the return takes place
   *  After program transformations this is not necessarily the enclosing method, because
   *  closures can intervene.
   */
  case class Return[-T >: Untyped] private[ast] (expr: Tree[T], from: Tree[T] = genericEmptyTree)(implicit @constructorOnly src: SourceFile)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Return[T]
  }

  /** while (cond) { body } */
  case class WhileDo[-T >: Untyped] private[ast] (cond: Tree[T], body: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = WhileDo[T]
  }

  /** try block catch cases finally finalizer */
  case class Try[-T >: Untyped] private[ast] (expr: Tree[T], cases: List[CaseDef[T]], finalizer: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Try[T]
  }

  /** Seq(elems)
   *  @param  tpt  The element type of the sequence.
   */
  case class SeqLiteral[-T >: Untyped] private[ast] (elems: List[Tree[T]], elemtpt: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = SeqLiteral[T]
  }

  /** Array(elems) */
  class JavaSeqLiteral[T >: Untyped] private[ast] (elems: List[Tree[T]], elemtpt: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends SeqLiteral(elems, elemtpt) {
    override def toString: String = s"JavaSeqLiteral($elems, $elemtpt)"
  }

  /** A tree representing inlined code.
   *
   *  @param  call      Info about the original call that was inlined
   *                    Until PostTyper, this is the full call, afterwards only
   *                    a reference to the toplevel class from which the call was inlined.
   *  @param  bindings  Bindings for proxies to be used in the inlined code
   *  @param  expansion The inlined tree, minus bindings.
   *
   *  The full inlined code is equivalent to
   *
   *      { bindings; expansion }
   *
   *  The reason to keep `bindings` separate is because they are typed in a
   *  different context: `bindings` represent the arguments to the inlined
   *  call, whereas `expansion` represents the body of the inlined function.
   */
  case class Inlined[-T >: Untyped] private[ast] (call: tpd.Tree, bindings: List[MemberDef[T]], expansion: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = Inlined[T]
    override def isTerm = expansion.isTerm
    override def isType = expansion.isType
  }

  /** A type tree that represents an existing or inferred type */
  case class TypeTree[-T >: Untyped]()(implicit @constructorOnly src: SourceFile)
    extends DenotingTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = TypeTree[T]
    override def isEmpty: Boolean = !hasType
    override def toString: String =
      s"TypeTree${if (hasType) s"[$typeOpt]" else ""}"
  }

  /** A type tree that defines a new type variable. Its type is always a TypeVar.
   *  Every TypeVar is created as the type of one TypeVarBinder.
   */
  class TypeVarBinder[-T >: Untyped](implicit @constructorOnly src: SourceFile) extends TypeTree[T]

  /** ref.type */
  case class SingletonTypeTree[-T >: Untyped] private[ast] (ref: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends DenotingTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = SingletonTypeTree[T]
  }

  /** tpt { refinements } */
  case class RefinedTypeTree[-T >: Untyped] private[ast] (tpt: Tree[T], refinements: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends ProxyTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = RefinedTypeTree[T]
    def forwardTo: Tree[T] = tpt
  }

  /** tpt[args] */
  case class AppliedTypeTree[-T >: Untyped] private[ast] (tpt: Tree[T], args: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends ProxyTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = AppliedTypeTree[T]
    def forwardTo: Tree[T] = tpt
  }

  /** [typeparams] -> tpt
   *
   *  Note: the type of such a tree is not necessarily a `HKTypeLambda`, it can
   *  also be a `TypeBounds` where the upper bound is an `HKTypeLambda`, and the
   *  lower bound is either a reference to `Nothing` or an `HKTypeLambda`,
   *  this happens because these trees are typed by `HKTypeLambda#fromParams` which
   *  makes sure to move bounds outside of the type lambda itself to simplify their
   *  handling in the compiler.
   *
   *  You may ask: why not normalize the trees too? That way,
   *
   *      LambdaTypeTree(X, TypeBoundsTree(A, B))
   *
   *  would become,
   *
   *      TypeBoundsTree(LambdaTypeTree(X, A), LambdaTypeTree(X, B))
   *
   *  which would maintain consistency between a tree and its type. The problem
   *  with this definition is that the same tree `X` appears twice, therefore
   *  we'd have to create two symbols for it which makes it harder to relate the
   *  source code written by the user with the trees used by the compiler (for
   *  example, to make "find all references" work in the IDE).
   */
  case class LambdaTypeTree[-T >: Untyped] private[ast] (tparams: List[TypeDef[T]], body: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends TypTree[T] {
    type ThisTree[-T >: Untyped] = LambdaTypeTree[T]
  }

  /** [bound] selector match { cases } */
  case class MatchTypeTree[-T >: Untyped] private[ast] (bound: Tree[T], selector: Tree[T], cases: List[CaseDef[T]])(implicit @constructorOnly src: SourceFile)
    extends TypTree[T] {
    type ThisTree[-T >: Untyped] = MatchTypeTree[T]
  }

  /** => T */
  case class ByNameTypeTree[-T >: Untyped] private[ast] (result: Tree[T])(implicit @constructorOnly src: SourceFile)
  extends TypTree[T] {
    type ThisTree[-T >: Untyped] = ByNameTypeTree[T]
  }

  /** >: lo <: hi
   *  >: lo <: hi = alias  for RHS of bounded opaque type
   */
  case class TypeBoundsTree[-T >: Untyped] private[ast] (lo: Tree[T], hi: Tree[T], alias: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends TypTree[T] {
    type ThisTree[-T >: Untyped] = TypeBoundsTree[T]
  }

  /** name @ body */
  case class Bind[-T >: Untyped] private[ast] (name: Name, body: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends NamedDefTree[T] with PatternTree[T] {
    type ThisTree[-T >: Untyped] = Bind[T]
    override def isType: Boolean = name.isTypeName
    override def isTerm: Boolean = name.isTermName

    override def nameSpan: Span =
      if span.exists then Span(span.start, span.start + name.toString.length) else span
  }

  /** tree_1 | ... | tree_n */
  case class Alternative[-T >: Untyped] private[ast] (trees: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends PatternTree[T] {
    type ThisTree[-T >: Untyped] = Alternative[T]
  }

  /** The typed translation of `extractor(patterns)` in a pattern. The translation has the following
   *  components:
   *
   *  @param fun       is `extractor.unapply` (or, for backwards compatibility, `extractor.unapplySeq`)
   *                   possibly with type parameters
   *  @param implicits Any implicit parameters passed to the unapply after the selector
   *  @param patterns  The argument patterns in the pattern match.
   *
   *  It is typed with same type as first `fun` argument
   *  Given a match selector `sel` a pattern UnApply(fun, implicits, patterns) is roughly translated as follows
   *
   *    val result = fun(sel)(implicits)
   *    if (result.isDefined) "match patterns against result"
   */
  case class UnApply[-T >: Untyped] private[ast] (fun: Tree[T], implicits: List[Tree[T]], patterns: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends ProxyTree[T] with PatternTree[T] {
    type ThisTree[-T >: Untyped] = UnApply[T]
    def forwardTo = fun
  }

  /** mods val name: tpt = rhs */
  case class ValDef[-T >: Untyped] private[ast] (name: TermName, tpt: Tree[T], private var preRhs: LazyTree[T @uncheckedVariance])(implicit @constructorOnly src: SourceFile)
    extends ValOrDefDef[T] {
    type ThisTree[-T >: Untyped] = ValDef[T]
    assert(isEmpty || tpt != genericEmptyTree)
    def unforced: LazyTree[T] = preRhs
    protected def force(x: Tree[T @uncheckedVariance]): Unit = preRhs = x
  }

  /** mods def name[tparams](vparams_1)...(vparams_n): tpt = rhs */
  case class DefDef[-T >: Untyped] private[ast] (name: TermName, tparams: List[TypeDef[T]],
      vparamss: List[List[ValDef[T]]], tpt: Tree[T], private var preRhs: LazyTree[T @uncheckedVariance])(implicit @constructorOnly src: SourceFile)
    extends ValOrDefDef[T] {
    type ThisTree[-T >: Untyped] = DefDef[T]
    assert(tpt != genericEmptyTree)
    def unforced: LazyTree[T] = preRhs
    protected def force(x: Tree[T @uncheckedVariance]): Unit = preRhs = x

    override def disableOverlapChecks = rawMods.is(Extension)
      // disable order checks for extension methods as long as we parse
      // type parameters both before and after the leading parameter section.
      // TODO drop this once syntax of type parameters has settled.
  }

  /** mods class name template     or
   *  mods trait name template     or
   *  mods type name = rhs   or
   *  mods type name >: lo <: hi,          if rhs = TypeBoundsTree(lo, hi)      or
   *  mods type name >: lo <: hi = rhs     if rhs = TypeBoundsTree(lo, hi, alias) and opaque in mods
   */
  case class TypeDef[-T >: Untyped] private[ast] (name: TypeName, rhs: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends MemberDef[T] {
    type ThisTree[-T >: Untyped] = TypeDef[T]

    /** Is this a definition of a class? */
    def isClassDef: Boolean = rhs.isInstanceOf[Template[?]]

    def isBackquoted: Boolean = hasAttachment(Backquoted)
  }

  /** extends parents { self => body }
   *  @param parentsOrDerived   A list of parents followed by a list of derived classes,
   *                            if this is of class untpd.DerivingTemplate.
   *                            Typed templates only have parents.
   */
  case class Template[-T >: Untyped] private[ast] (constr: DefDef[T], parentsOrDerived: List[Tree[T]], self: ValDef[T], private var preBody: LazyTreeList[T @uncheckedVariance])(implicit @constructorOnly src: SourceFile)
    extends DefTree[T] with WithLazyField[List[Tree[T]]] {
    type ThisTree[-T >: Untyped] = Template[T]
    def unforcedBody: LazyTreeList[T] = unforced
    def unforced: LazyTreeList[T] = preBody
    protected def force(x: List[Tree[T @uncheckedVariance]]): Unit = preBody = x
    def body(implicit ctx: Context): List[Tree[T]] = forceIfLazy

    def parents: List[Tree[T]] = parentsOrDerived // overridden by DerivingTemplate
    def derived: List[untpd.Tree] = Nil           // overridden by DerivingTemplate
  }


  /** import expr.selectors
   *  where a selector is either an untyped `Ident`, `name` or
   *  an untyped thicket consisting of `name` and `rename`.
   */
  case class Import[-T >: Untyped] private[ast] (expr: Tree[T], selectors: List[untpd.ImportSelector])(implicit @constructorOnly src: SourceFile)
    extends DenotingTree[T] {
    type ThisTree[-T >: Untyped] = Import[T]
  }

  /** package pid { stats } */
  case class PackageDef[-T >: Untyped] private[ast] (pid: RefTree[T], stats: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends ProxyTree[T] {
    type ThisTree[-T >: Untyped] = PackageDef[T]
    def forwardTo: RefTree[T] = pid
  }

  /** arg @annot */
  case class Annotated[-T >: Untyped] private[ast] (arg: Tree[T], annot: Tree[T])(implicit @constructorOnly src: SourceFile)
    extends ProxyTree[T] {
    type ThisTree[-T >: Untyped] = Annotated[T]
    def forwardTo: Tree[T] = arg
  }

  trait WithoutTypeOrPos[-T >: Untyped] extends Tree[T] {
    override def withTypeUnchecked(tpe: Type): ThisTree[Type] = this.asInstanceOf[ThisTree[Type]]
    override def span: Span = NoSpan
    override def span_=(span: Span): Unit = {}
  }

  /** Temporary class that results from translation of ModuleDefs
   *  (and possibly other statements).
   *  The contained trees will be integrated when transformed with
   *  a `transform(List[Tree])` call.
   */
  case class Thicket[-T >: Untyped](trees: List[Tree[T]])(implicit @constructorOnly src: SourceFile)
    extends Tree[T] with WithoutTypeOrPos[T] {
    myTpe = NoType.asInstanceOf[T]
    type ThisTree[-T >: Untyped] = Thicket[T]

    def mapElems(op: Tree[T] => Tree[T] @uncheckedVariance): Thicket[T] = {
      val newTrees = trees.mapConserve(op)
      if (trees eq newTrees)
        this
      else
        Thicket[T](newTrees)(source).asInstanceOf[this.type]
    }

    override def foreachInThicket(op: Tree[T] => Unit): Unit =
      trees foreach (_.foreachInThicket(op))

    override def isEmpty: Boolean = trees.isEmpty
    override def toList: List[Tree[T]] = flatten(trees)
    override def toString: String = if (isEmpty) "EmptyTree" else "Thicket(" + trees.mkString(", ") + ")"
    override def span: Span = trees.foldLeft(NoSpan) ((span, t) => span union t.span)

    override def withSpan(span: Span): this.type =
      mapElems(_.withSpan(span)).asInstanceOf[this.type]
  }

  class EmptyTree[T >: Untyped] extends Thicket(Nil)(NoSource) {
    // assert(uniqueId != 1492)
    override def withSpan(span: Span) = throw AssertionError("Cannot change span of EmptyTree")
  }

  class EmptyValDef[T >: Untyped] extends ValDef[T](
    nme.WILDCARD, genericEmptyTree[T], genericEmptyTree[T])(NoSource) with WithoutTypeOrPos[T] {
    myTpe = NoType.asInstanceOf[T]
    setMods(untpd.Modifiers(PrivateLocal))
    override def isEmpty: Boolean = true
    override def withSpan(span: Span) = throw AssertionError("Cannot change span of EmptyValDef")
  }

  @sharable val theEmptyTree = new EmptyTree[Type]()
  @sharable val theEmptyValDef = new EmptyValDef[Type]()

  def genericEmptyValDef[T >: Untyped]: ValDef[T]       = theEmptyValDef.asInstanceOf[ValDef[T]]
  def genericEmptyTree[T >: Untyped]: Thicket[T]        = theEmptyTree.asInstanceOf[Thicket[T]]

  def flatten[T >: Untyped](trees: List[Tree[T]]): List[Tree[T]] = {
    def recur(buf: ListBuffer[Tree[T]], remaining: List[Tree[T]]): ListBuffer[Tree[T]] =
      remaining match {
        case Thicket(elems) :: remaining1 =>
          var buf1 = buf
          if (buf1 == null) {
            buf1 = new ListBuffer[Tree[T]]
            var scanned = trees
            while (scanned `ne` remaining) {
              buf1 += scanned.head
              scanned = scanned.tail
            }
          }
          recur(recur(buf1, elems), remaining1)
        case tree :: remaining1 =>
          if (buf != null) buf += tree
          recur(buf, remaining1)
        case nil =>
          buf
      }
    val buf = recur(null, trees)
    if (buf != null) buf.toList else trees
  }

  // ----- Lazy trees and tree sequences

  /** A tree that can have a lazy field
   *  The field is represented by some private `var` which is
   *  accessed by `unforced` and `force`. Forcing the field will
   *  set the `var` to the underlying value.
   */
  trait WithLazyField[+T <: AnyRef] {
    def unforced: T | Lazy[T]
    protected def force(x: T @uncheckedVariance): Unit
    def forceIfLazy(implicit ctx: Context): T = unforced match {
      case lzy: Lazy[T @unchecked] =>
        val x = lzy.complete
        force(x)
        x
      case x: T @ unchecked => x
    }
  }

  /** A base trait for lazy tree fields.
   *  These can be instantiated with Lazy instances which
   *  can delay tree construction until the field is first demanded.
   */
  trait Lazy[+T <: AnyRef] {
    def complete(implicit ctx: Context): T
  }

  // ----- Generic Tree Instances, inherited from `tpt` and `untpd`.

  abstract class Instance[T >: Untyped <: Type] { inst =>

    type Tree = Trees.Tree[T]
    type TypTree = Trees.TypTree[T]
    type TermTree = Trees.TermTree[T]
    type PatternTree = Trees.PatternTree[T]
    type DenotingTree = Trees.DenotingTree[T]
    type ProxyTree = Trees.ProxyTree[T]
    type NameTree = Trees.NameTree[T]
    type RefTree = Trees.RefTree[T]
    type DefTree = Trees.DefTree[T]
    type NamedDefTree = Trees.NamedDefTree[T]
    type MemberDef = Trees.MemberDef[T]
    type ValOrDefDef = Trees.ValOrDefDef[T]
    type LazyTree = Trees.LazyTree[T]
    type LazyTreeList = Trees.LazyTreeList[T]

    type Ident = Trees.Ident[T]
    type SearchFailureIdent = Trees.SearchFailureIdent[T]
    type Select = Trees.Select[T]
    type SelectWithSig = Trees.SelectWithSig[T]
    type This = Trees.This[T]
    type Super = Trees.Super[T]
    type Apply = Trees.Apply[T]
    type TypeApply = Trees.TypeApply[T]
    type Literal = Trees.Literal[T]
    type New = Trees.New[T]
    type Typed = Trees.Typed[T]
    type NamedArg = Trees.NamedArg[T]
    type Assign = Trees.Assign[T]
    type Block = Trees.Block[T]
    type If = Trees.If[T]
    type InlineIf = Trees.InlineIf[T]
    type Closure = Trees.Closure[T]
    type Match = Trees.Match[T]
    type InlineMatch = Trees.InlineMatch[T]
    type CaseDef = Trees.CaseDef[T]
    type Labeled = Trees.Labeled[T]
    type Return = Trees.Return[T]
    type WhileDo = Trees.WhileDo[T]
    type Try = Trees.Try[T]
    type SeqLiteral = Trees.SeqLiteral[T]
    type JavaSeqLiteral = Trees.JavaSeqLiteral[T]
    type Inlined = Trees.Inlined[T]
    type TypeTree = Trees.TypeTree[T]
    type SingletonTypeTree = Trees.SingletonTypeTree[T]
    type RefinedTypeTree = Trees.RefinedTypeTree[T]
    type AppliedTypeTree = Trees.AppliedTypeTree[T]
    type LambdaTypeTree = Trees.LambdaTypeTree[T]
    type MatchTypeTree = Trees.MatchTypeTree[T]
    type ByNameTypeTree = Trees.ByNameTypeTree[T]
    type TypeBoundsTree = Trees.TypeBoundsTree[T]
    type Bind = Trees.Bind[T]
    type Alternative = Trees.Alternative[T]
    type UnApply = Trees.UnApply[T]
    type ValDef = Trees.ValDef[T]
    type DefDef = Trees.DefDef[T]
    type TypeDef = Trees.TypeDef[T]
    type Template = Trees.Template[T]
    type Import = Trees.Import[T]
    type PackageDef = Trees.PackageDef[T]
    type Annotated = Trees.Annotated[T]
    type Thicket = Trees.Thicket[T]

    @sharable val EmptyTree: Thicket = genericEmptyTree
    @sharable val EmptyValDef: ValDef = genericEmptyValDef
    @sharable val ContextualEmptyTree: Thicket = EmptyTree() // an empty tree marking a contextual closure

    // ----- Auxiliary creation methods ------------------

    def Thicket(): Thicket = EmptyTree
    def Thicket(x1: Tree, x2: Tree)(implicit src: SourceFile): Thicket = Thicket(x1 :: x2 :: Nil)
    def Thicket(x1: Tree, x2: Tree, x3: Tree)(implicit src: SourceFile): Thicket = Thicket(x1 :: x2 :: x3 :: Nil)
    def flatTree(xs: List[Tree])(implicit src: SourceFile): Tree = flatten(xs) match {
      case x :: Nil => x
      case ys => Thicket(ys)
    }

    // ----- Helper classes for copying, transforming, accumulating -----------------

    val cpy: TreeCopier

    /** A class for copying trees. The copy methods avoid creating a new tree
     *  If all arguments stay the same.
     *
     * Note: Some of the copy methods take a context.
     * These are exactly those methods that are overridden in TypedTreeCopier
     * so that they selectively retype themselves. Retyping needs a context.
     */
    abstract class TreeCopier {
      protected def postProcess(tree: Tree, copied: untpd.Tree): copied.ThisTree[T]
      protected def postProcess(tree: Tree, copied: untpd.MemberDef): copied.ThisTree[T]

      /** Soucre of the copied tree */
      protected def sourceFile(tree: Tree): SourceFile = tree.source

      protected def finalize(tree: Tree, copied: untpd.Tree): copied.ThisTree[T] =
        postProcess(tree, copied.withSpan(tree.span).withAttachmentsFrom(tree))

      protected def finalize(tree: Tree, copied: untpd.MemberDef): copied.ThisTree[T] =
        postProcess(tree, copied.withSpan(tree.span).withAttachmentsFrom(tree))

      def Ident(tree: Tree)(name: Name)(implicit ctx: Context): Ident = tree match {
        case tree: Ident if name == tree.name => tree
        case _ => finalize(tree, untpd.Ident(name)(sourceFile(tree)))
      }
      def Select(tree: Tree)(qualifier: Tree, name: Name)(implicit ctx: Context): Select = tree match {
        case tree: SelectWithSig =>
          if ((qualifier eq tree.qualifier) && (name == tree.name)) tree
          else finalize(tree, SelectWithSig(qualifier, name, tree.sig)(sourceFile(tree)))
        case tree: Select if (qualifier eq tree.qualifier) && (name == tree.name) => tree
        case _ => finalize(tree, untpd.Select(qualifier, name)(sourceFile(tree)))
      }
      /** Copy Ident or Select trees */
      def Ref(tree: RefTree)(name: Name)(implicit ctx: Context): RefTree = tree match {
        case Ident(_) => Ident(tree)(name)
        case Select(qual, _) => Select(tree)(qual, name)
      }
      def This(tree: Tree)(qual: untpd.Ident)(implicit ctx: Context): This = tree match {
        case tree: This if (qual eq tree.qual) => tree
        case _ => finalize(tree, untpd.This(qual)(sourceFile(tree)))
      }
      def Super(tree: Tree)(qual: Tree, mix: untpd.Ident)(implicit ctx: Context): Super = tree match {
        case tree: Super if (qual eq tree.qual) && (mix eq tree.mix) => tree
        case _ => finalize(tree, untpd.Super(qual, mix)(sourceFile(tree)))
      }
      def Apply(tree: Tree)(fun: Tree, args: List[Tree])(implicit ctx: Context): Apply = tree match {
        case tree: Apply if (fun eq tree.fun) && (args eq tree.args) => tree
        case _ => finalize(tree, untpd.Apply(fun, args)(sourceFile(tree)))
      }
      def TypeApply(tree: Tree)(fun: Tree, args: List[Tree])(implicit ctx: Context): TypeApply = tree match {
        case tree: TypeApply if (fun eq tree.fun) && (args eq tree.args) => tree
        case _ => finalize(tree, untpd.TypeApply(fun, args)(sourceFile(tree)))
      }
      def Literal(tree: Tree)(const: Constant)(implicit ctx: Context): Literal = tree match {
        case tree: Literal if const == tree.const => tree
        case _ => finalize(tree, untpd.Literal(const)(sourceFile(tree)))
      }
      def New(tree: Tree)(tpt: Tree)(implicit ctx: Context): New = tree match {
        case tree: New if (tpt eq tree.tpt) => tree
        case _ => finalize(tree, untpd.New(tpt)(sourceFile(tree)))
      }
      def Typed(tree: Tree)(expr: Tree, tpt: Tree)(implicit ctx: Context): Typed = tree match {
        case tree: Typed if (expr eq tree.expr) && (tpt eq tree.tpt) => tree
        case tree => finalize(tree, untpd.Typed(expr, tpt)(sourceFile(tree)))
          //.ensuring(res => res.uniqueId != 1471, s"source = $tree, ${tree.uniqueId}")
      }
      def NamedArg(tree: Tree)(name: Name, arg: Tree)(implicit ctx: Context): NamedArg = tree match {
        case tree: NamedArg if (name == tree.name) && (arg eq tree.arg) => tree
        case _ => finalize(tree, untpd.NamedArg(name, arg)(sourceFile(tree)))
      }
      def Assign(tree: Tree)(lhs: Tree, rhs: Tree)(implicit ctx: Context): Assign = tree match {
        case tree: Assign if (lhs eq tree.lhs) && (rhs eq tree.rhs) => tree
        case _ => finalize(tree, untpd.Assign(lhs, rhs)(sourceFile(tree)))
      }
      def Block(tree: Tree)(stats: List[Tree], expr: Tree)(implicit ctx: Context): Block = tree match {
        case tree: Block if (stats eq tree.stats) && (expr eq tree.expr) => tree
        case _ => finalize(tree, untpd.Block(stats, expr)(sourceFile(tree)))
      }
      def If(tree: Tree)(cond: Tree, thenp: Tree, elsep: Tree)(implicit ctx: Context): If = tree match {
        case tree: If if (cond eq tree.cond) && (thenp eq tree.thenp) && (elsep eq tree.elsep) => tree
        case tree: InlineIf => finalize(tree, untpd.InlineIf(cond, thenp, elsep)(sourceFile(tree)))
        case _ => finalize(tree, untpd.If(cond, thenp, elsep)(sourceFile(tree)))
      }
      def Closure(tree: Tree)(env: List[Tree], meth: Tree, tpt: Tree)(implicit ctx: Context): Closure = tree match {
        case tree: Closure if (env eq tree.env) && (meth eq tree.meth) && (tpt eq tree.tpt) => tree
        case _ => finalize(tree, untpd.Closure(env, meth, tpt)(sourceFile(tree)))
      }
      def Match(tree: Tree)(selector: Tree, cases: List[CaseDef])(implicit ctx: Context): Match = tree match {
        case tree: Match if (selector eq tree.selector) && (cases eq tree.cases) => tree
        case tree: InlineMatch => finalize(tree, untpd.InlineMatch(selector, cases)(sourceFile(tree)))
        case _ => finalize(tree, untpd.Match(selector, cases)(sourceFile(tree)))
      }
      def CaseDef(tree: Tree)(pat: Tree, guard: Tree, body: Tree)(implicit ctx: Context): CaseDef = tree match {
        case tree: CaseDef if (pat eq tree.pat) && (guard eq tree.guard) && (body eq tree.body) => tree
        case _ => finalize(tree, untpd.CaseDef(pat, guard, body)(sourceFile(tree)))
      }
      def Labeled(tree: Tree)(bind: Bind, expr: Tree)(implicit ctx: Context): Labeled = tree match {
        case tree: Labeled if (bind eq tree.bind) && (expr eq tree.expr) => tree
        case _ => finalize(tree, untpd.Labeled(bind, expr)(sourceFile(tree)))
      }
      def Return(tree: Tree)(expr: Tree, from: Tree)(implicit ctx: Context): Return = tree match {
        case tree: Return if (expr eq tree.expr) && (from eq tree.from) => tree
        case _ => finalize(tree, untpd.Return(expr, from)(sourceFile(tree)))
      }
      def WhileDo(tree: Tree)(cond: Tree, body: Tree)(implicit ctx: Context): WhileDo = tree match {
        case tree: WhileDo if (cond eq tree.cond) && (body eq tree.body) => tree
        case _ => finalize(tree, untpd.WhileDo(cond, body)(sourceFile(tree)))
      }
      def Try(tree: Tree)(expr: Tree, cases: List[CaseDef], finalizer: Tree)(implicit ctx: Context): Try = tree match {
        case tree: Try if (expr eq tree.expr) && (cases eq tree.cases) && (finalizer eq tree.finalizer) => tree
        case _ => finalize(tree, untpd.Try(expr, cases, finalizer)(sourceFile(tree)))
      }
      def SeqLiteral(tree: Tree)(elems: List[Tree], elemtpt: Tree)(implicit ctx: Context): SeqLiteral = tree match {
        case tree: JavaSeqLiteral =>
          if ((elems eq tree.elems) && (elemtpt eq tree.elemtpt)) tree
          else finalize(tree, untpd.JavaSeqLiteral(elems, elemtpt))
        case tree: SeqLiteral if (elems eq tree.elems) && (elemtpt eq tree.elemtpt) => tree
        case _ => finalize(tree, untpd.SeqLiteral(elems, elemtpt)(sourceFile(tree)))
      }
      def Inlined(tree: Tree)(call: tpd.Tree, bindings: List[MemberDef], expansion: Tree)(implicit ctx: Context): Inlined = tree match {
        case tree: Inlined if (call eq tree.call) && (bindings eq tree.bindings) && (expansion eq tree.expansion) => tree
        case _ => finalize(tree, untpd.Inlined(call, bindings, expansion)(sourceFile(tree)))
      }
      def SingletonTypeTree(tree: Tree)(ref: Tree)(implicit ctx: Context): SingletonTypeTree = tree match {
        case tree: SingletonTypeTree if (ref eq tree.ref) => tree
        case _ => finalize(tree, untpd.SingletonTypeTree(ref)(sourceFile(tree)))
      }
      def RefinedTypeTree(tree: Tree)(tpt: Tree, refinements: List[Tree])(implicit ctx: Context): RefinedTypeTree = tree match {
        case tree: RefinedTypeTree if (tpt eq tree.tpt) && (refinements eq tree.refinements) => tree
        case _ => finalize(tree, untpd.RefinedTypeTree(tpt, refinements)(sourceFile(tree)))
      }
      def AppliedTypeTree(tree: Tree)(tpt: Tree, args: List[Tree])(implicit ctx: Context): AppliedTypeTree = tree match {
        case tree: AppliedTypeTree if (tpt eq tree.tpt) && (args eq tree.args) => tree
        case _ => finalize(tree, untpd.AppliedTypeTree(tpt, args)(sourceFile(tree)))
      }
      def LambdaTypeTree(tree: Tree)(tparams: List[TypeDef], body: Tree)(implicit ctx: Context): LambdaTypeTree = tree match {
        case tree: LambdaTypeTree if (tparams eq tree.tparams) && (body eq tree.body) => tree
        case _ => finalize(tree, untpd.LambdaTypeTree(tparams, body)(sourceFile(tree)))
      }
      def MatchTypeTree(tree: Tree)(bound: Tree, selector: Tree, cases: List[CaseDef])(implicit ctx: Context): MatchTypeTree = tree match {
        case tree: MatchTypeTree if (bound eq tree.bound) && (selector eq tree.selector) && (cases eq tree.cases) => tree
        case _ => finalize(tree, untpd.MatchTypeTree(bound, selector, cases)(sourceFile(tree)))
      }
      def ByNameTypeTree(tree: Tree)(result: Tree)(implicit ctx: Context): ByNameTypeTree = tree match {
        case tree: ByNameTypeTree if (result eq tree.result) => tree
        case _ => finalize(tree, untpd.ByNameTypeTree(result)(sourceFile(tree)))
      }
      def TypeBoundsTree(tree: Tree)(lo: Tree, hi: Tree, alias: Tree)(implicit ctx: Context): TypeBoundsTree = tree match {
        case tree: TypeBoundsTree if (lo eq tree.lo) && (hi eq tree.hi) && (alias eq tree.alias) => tree
        case _ => finalize(tree, untpd.TypeBoundsTree(lo, hi, alias)(sourceFile(tree)))
      }
      def Bind(tree: Tree)(name: Name, body: Tree)(implicit ctx: Context): Bind = tree match {
        case tree: Bind if (name eq tree.name) && (body eq tree.body) => tree
        case _ => finalize(tree, untpd.Bind(name, body)(sourceFile(tree)))
      }
      def Alternative(tree: Tree)(trees: List[Tree])(implicit ctx: Context): Alternative = tree match {
        case tree: Alternative if (trees eq tree.trees) => tree
        case _ => finalize(tree, untpd.Alternative(trees)(sourceFile(tree)))
      }
      def UnApply(tree: Tree)(fun: Tree, implicits: List[Tree], patterns: List[Tree])(implicit ctx: Context): UnApply = tree match {
        case tree: UnApply if (fun eq tree.fun) && (implicits eq tree.implicits) && (patterns eq tree.patterns) => tree
        case _ => finalize(tree, untpd.UnApply(fun, implicits, patterns)(sourceFile(tree)))
      }
      def ValDef(tree: Tree)(name: TermName, tpt: Tree, rhs: LazyTree)(implicit ctx: Context): ValDef = tree match {
        case tree: ValDef if (name == tree.name) && (tpt eq tree.tpt) && (rhs eq tree.unforcedRhs) => tree
        case _ => finalize(tree, untpd.ValDef(name, tpt, rhs)(sourceFile(tree)))
      }
      def DefDef(tree: Tree)(name: TermName, tparams: List[TypeDef], vparamss: List[List[ValDef]], tpt: Tree, rhs: LazyTree)(implicit ctx: Context): DefDef = tree match {
        case tree: DefDef if (name == tree.name) && (tparams eq tree.tparams) && (vparamss eq tree.vparamss) && (tpt eq tree.tpt) && (rhs eq tree.unforcedRhs) => tree
        case _ => finalize(tree, untpd.DefDef(name, tparams, vparamss, tpt, rhs)(sourceFile(tree)))
      }
      def TypeDef(tree: Tree)(name: TypeName, rhs: Tree)(implicit ctx: Context): TypeDef = tree match {
        case tree: TypeDef if (name == tree.name) && (rhs eq tree.rhs) => tree
        case _ => finalize(tree, untpd.TypeDef(name, rhs)(sourceFile(tree)))
      }
      def Template(tree: Tree)(constr: DefDef, parents: List[Tree], derived: List[untpd.Tree], self: ValDef, body: LazyTreeList)(implicit ctx: Context): Template = tree match {
        case tree: Template if (constr eq tree.constr) && (parents eq tree.parents) && (derived eq tree.derived) && (self eq tree.self) && (body eq tree.unforcedBody) => tree
        case tree => finalize(tree, untpd.Template(constr, parents, derived, self, body)(sourceFile(tree)))
      }
      def Import(tree: Tree)(expr: Tree, selectors: List[untpd.ImportSelector])(implicit ctx: Context): Import = tree match {
        case tree: Import if (expr eq tree.expr) && (selectors eq tree.selectors) => tree
        case _ => finalize(tree, untpd.Import(expr, selectors)(sourceFile(tree)))
      }
      def PackageDef(tree: Tree)(pid: RefTree, stats: List[Tree])(implicit ctx: Context): PackageDef = tree match {
        case tree: PackageDef if (pid eq tree.pid) && (stats eq tree.stats) => tree
        case _ => finalize(tree, untpd.PackageDef(pid, stats)(sourceFile(tree)))
      }
      def Annotated(tree: Tree)(arg: Tree, annot: Tree)(implicit ctx: Context): Annotated = tree match {
        case tree: Annotated if (arg eq tree.arg) && (annot eq tree.annot) => tree
        case _ => finalize(tree, untpd.Annotated(arg, annot)(sourceFile(tree)))
      }
      def Thicket(tree: Tree)(trees: List[Tree])(implicit ctx: Context): Thicket = tree match {
        case tree: Thicket if (trees eq tree.trees) => tree
        case _ => finalize(tree, untpd.Thicket(trees)(sourceFile(tree)))
      }

      // Copier methods with default arguments; these demand that the original tree
      // is of the same class as the copy. We only include trees with more than 2 elements here.
      def If(tree: If)(cond: Tree = tree.cond, thenp: Tree = tree.thenp, elsep: Tree = tree.elsep)(implicit ctx: Context): If =
        If(tree: Tree)(cond, thenp, elsep)
      def Closure(tree: Closure)(env: List[Tree] = tree.env, meth: Tree = tree.meth, tpt: Tree = tree.tpt)(implicit ctx: Context): Closure =
        Closure(tree: Tree)(env, meth, tpt)
      def CaseDef(tree: CaseDef)(pat: Tree = tree.pat, guard: Tree = tree.guard, body: Tree = tree.body)(implicit ctx: Context): CaseDef =
        CaseDef(tree: Tree)(pat, guard, body)
      def Try(tree: Try)(expr: Tree = tree.expr, cases: List[CaseDef] = tree.cases, finalizer: Tree = tree.finalizer)(implicit ctx: Context): Try =
        Try(tree: Tree)(expr, cases, finalizer)
      def UnApply(tree: UnApply)(fun: Tree = tree.fun, implicits: List[Tree] = tree.implicits, patterns: List[Tree] = tree.patterns)(implicit ctx: Context): UnApply =
        UnApply(tree: Tree)(fun, implicits, patterns)
      def ValDef(tree: ValDef)(name: TermName = tree.name, tpt: Tree = tree.tpt, rhs: LazyTree = tree.unforcedRhs)(implicit ctx: Context): ValDef =
        ValDef(tree: Tree)(name, tpt, rhs)
      def DefDef(tree: DefDef)(name: TermName = tree.name, tparams: List[TypeDef] = tree.tparams, vparamss: List[List[ValDef]] = tree.vparamss, tpt: Tree = tree.tpt, rhs: LazyTree = tree.unforcedRhs)(implicit ctx: Context): DefDef =
        DefDef(tree: Tree)(name, tparams, vparamss, tpt, rhs)
      def TypeDef(tree: TypeDef)(name: TypeName = tree.name, rhs: Tree = tree.rhs)(implicit ctx: Context): TypeDef =
        TypeDef(tree: Tree)(name, rhs)
      def Template(tree: Template)(constr: DefDef = tree.constr, parents: List[Tree] = tree.parents, derived: List[untpd.Tree] = tree.derived, self: ValDef = tree.self, body: LazyTreeList = tree.unforcedBody)(implicit ctx: Context): Template =
        Template(tree: Tree)(constr, parents, derived, self, body)
    }

    /** Hook to indicate that a transform of some subtree should be skipped */
    protected def skipTransform(tree: Tree)(implicit ctx: Context): Boolean = false

    /** For untyped trees, this is just the identity.
     *  For typed trees, a context derived form `ctx` that records `call` as the
     *  innermost enclosing call for which the inlined version is currently
     *  processed.
     */
    protected def inlineContext(call: Tree)(implicit ctx: Context): Context = ctx

    abstract class TreeMap(val cpy: TreeCopier = inst.cpy) { self =>
      def transform(tree: Tree)(using Context): Tree = {
        inContext(
          if tree.source != ctx.source && tree.source.exists
          then ctx.withSource(tree.source)
          else ctx
        ){
          Stats.record(s"TreeMap.transform/$getClass")
          def localCtx =
            if (tree.hasType && tree.symbol.exists) ctx.withOwner(tree.symbol) else ctx

          if (skipTransform(tree)) tree
          else tree match {
            case Ident(name) =>
              tree
            case Select(qualifier, name) =>
              cpy.Select(tree)(transform(qualifier), name)
            case This(qual) =>
              tree
            case Super(qual, mix) =>
              cpy.Super(tree)(transform(qual), mix)
            case Apply(fun, args) =>
              cpy.Apply(tree)(transform(fun), transform(args))
            case TypeApply(fun, args) =>
              cpy.TypeApply(tree)(transform(fun), transform(args))
            case Literal(const) =>
              tree
            case New(tpt) =>
              cpy.New(tree)(transform(tpt))
            case Typed(expr, tpt) =>
              cpy.Typed(tree)(transform(expr), transform(tpt))
            case NamedArg(name, arg) =>
              cpy.NamedArg(tree)(name, transform(arg))
            case Assign(lhs, rhs) =>
              cpy.Assign(tree)(transform(lhs), transform(rhs))
            case Block(stats, expr) =>
              cpy.Block(tree)(transformStats(stats), transform(expr))
            case If(cond, thenp, elsep) =>
              cpy.If(tree)(transform(cond), transform(thenp), transform(elsep))
            case Closure(env, meth, tpt) =>
              cpy.Closure(tree)(transform(env), transform(meth), transform(tpt))
            case Match(selector, cases) =>
              cpy.Match(tree)(transform(selector), transformSub(cases))
            case CaseDef(pat, guard, body) =>
              cpy.CaseDef(tree)(transform(pat), transform(guard), transform(body))
            case Labeled(bind, expr) =>
              cpy.Labeled(tree)(transformSub(bind), transform(expr))
            case Return(expr, from) =>
              cpy.Return(tree)(transform(expr), transformSub(from))
            case WhileDo(cond, body) =>
              cpy.WhileDo(tree)(transform(cond), transform(body))
            case Try(block, cases, finalizer) =>
              cpy.Try(tree)(transform(block), transformSub(cases), transform(finalizer))
            case SeqLiteral(elems, elemtpt) =>
              cpy.SeqLiteral(tree)(transform(elems), transform(elemtpt))
            case Inlined(call, bindings, expansion) =>
              cpy.Inlined(tree)(call, transformSub(bindings), transform(expansion)(using inlineContext(call)))
            case TypeTree() =>
              tree
            case SingletonTypeTree(ref) =>
              cpy.SingletonTypeTree(tree)(transform(ref))
            case RefinedTypeTree(tpt, refinements) =>
              cpy.RefinedTypeTree(tree)(transform(tpt), transformSub(refinements))
            case AppliedTypeTree(tpt, args) =>
              cpy.AppliedTypeTree(tree)(transform(tpt), transform(args))
            case LambdaTypeTree(tparams, body) =>
              inContext(localCtx) {
                cpy.LambdaTypeTree(tree)(transformSub(tparams), transform(body))
              }
            case MatchTypeTree(bound, selector, cases) =>
              cpy.MatchTypeTree(tree)(transform(bound), transform(selector), transformSub(cases))
            case ByNameTypeTree(result) =>
              cpy.ByNameTypeTree(tree)(transform(result))
            case TypeBoundsTree(lo, hi, alias) =>
              cpy.TypeBoundsTree(tree)(transform(lo), transform(hi), transform(alias))
            case Bind(name, body) =>
              cpy.Bind(tree)(name, transform(body))
            case Alternative(trees) =>
              cpy.Alternative(tree)(transform(trees))
            case UnApply(fun, implicits, patterns) =>
              cpy.UnApply(tree)(transform(fun), transform(implicits), transform(patterns))
            case EmptyValDef =>
              tree
            case tree @ ValDef(name, tpt, _) =>
              inContext(localCtx) {
                val tpt1 = transform(tpt)
                val rhs1 = transform(tree.rhs)
                cpy.ValDef(tree)(name, tpt1, rhs1)
              }
            case tree @ DefDef(name, tparams, vparamss, tpt, _) =>
              inContext(localCtx) {
                cpy.DefDef(tree)(name, transformSub(tparams), vparamss mapConserve (transformSub(_)), transform(tpt), transform(tree.rhs))
              }
            case tree @ TypeDef(name, rhs) =>
              inContext(localCtx) {
                cpy.TypeDef(tree)(name, transform(rhs))
              }
            case tree @ Template(constr, parents, self, _) if tree.derived.isEmpty =>
              cpy.Template(tree)(transformSub(constr), transform(tree.parents), Nil, transformSub(self), transformStats(tree.body))
            case Import(expr, selectors) =>
              cpy.Import(tree)(transform(expr), selectors)
            case PackageDef(pid, stats) =>
              cpy.PackageDef(tree)(transformSub(pid), transformStats(stats)(localCtx))
            case Annotated(arg, annot) =>
              cpy.Annotated(tree)(transform(arg), transform(annot))
            case Thicket(trees) =>
              val trees1 = transform(trees)
              if (trees1 eq trees) tree else Thicket(trees1)
            case _ =>
              transformMoreCases(tree)
          }
        }
      }

      def transformStats(trees: List[Tree])(implicit ctx: Context): List[Tree] =
        transform(trees)
      def transform(trees: List[Tree])(implicit ctx: Context): List[Tree] =
        flatten(trees mapConserve (transform(_)))
      def transformSub[Tr <: Tree](tree: Tr)(implicit ctx: Context): Tr =
        transform(tree).asInstanceOf[Tr]
      def transformSub[Tr <: Tree](trees: List[Tr])(implicit ctx: Context): List[Tr] =
        transform(trees).asInstanceOf[List[Tr]]

      protected def transformMoreCases(tree: Tree)(implicit ctx: Context): Tree = {
        assert(ctx.reporter.errorsReported)
        tree
      }
    }

    abstract class TreeAccumulator[X] { self =>
      // Ties the knot of the traversal: call `foldOver(x, tree))` to dive in the `tree` node.
      def apply(x: X, tree: Tree)(implicit ctx: Context): X

      def apply(x: X, trees: Traversable[Tree])(implicit ctx: Context): X = trees.foldLeft(x)(apply)
      def foldOver(x: X, tree: Tree)(implicit ctx: Context): X =
        if (tree.source != ctx.source && tree.source.exists)
          foldOver(x, tree)(ctx.withSource(tree.source))
        else {
          Stats.record(s"TreeAccumulator.foldOver/$getClass")
          def localCtx =
            if (tree.hasType && tree.symbol.exists) ctx.withOwner(tree.symbol) else ctx
          tree match {
            case Ident(name) =>
              x
            case Select(qualifier, name) =>
              this(x, qualifier)
            case This(qual) =>
              x
            case Super(qual, mix) =>
              this(x, qual)
            case Apply(fun, args) =>
              this(this(x, fun), args)
            case TypeApply(fun, args) =>
              this(this(x, fun), args)
            case Literal(const) =>
              x
            case New(tpt) =>
              this(x, tpt)
            case Typed(expr, tpt) =>
              this(this(x, expr), tpt)
            case NamedArg(name, arg) =>
              this(x, arg)
            case Assign(lhs, rhs) =>
              this(this(x, lhs), rhs)
            case Block(stats, expr) =>
              this(this(x, stats), expr)
            case If(cond, thenp, elsep) =>
              this(this(this(x, cond), thenp), elsep)
            case Closure(env, meth, tpt) =>
              this(this(this(x, env), meth), tpt)
            case Match(selector, cases) =>
              this(this(x, selector), cases)
            case CaseDef(pat, guard, body) =>
              this(this(this(x, pat), guard), body)
            case Labeled(bind, expr) =>
              this(this(x, bind), expr)
            case Return(expr, from) =>
              this(this(x, expr), from)
            case WhileDo(cond, body) =>
              this(this(x, cond), body)
            case Try(block, handler, finalizer) =>
              this(this(this(x, block), handler), finalizer)
            case SeqLiteral(elems, elemtpt) =>
              this(this(x, elems), elemtpt)
            case Inlined(call, bindings, expansion) =>
              this(this(x, bindings), expansion)(inlineContext(call))
            case TypeTree() =>
              x
            case SingletonTypeTree(ref) =>
              this(x, ref)
            case RefinedTypeTree(tpt, refinements) =>
              this(this(x, tpt), refinements)
            case AppliedTypeTree(tpt, args) =>
              this(this(x, tpt), args)
            case LambdaTypeTree(tparams, body) =>
              inContext(localCtx) {
                this(this(x, tparams), body)
              }
            case MatchTypeTree(bound, selector, cases) =>
              this(this(this(x, bound), selector), cases)
            case ByNameTypeTree(result) =>
              this(x, result)
            case TypeBoundsTree(lo, hi, alias) =>
              this(this(this(x, lo), hi), alias)
            case Bind(name, body) =>
              this(x, body)
            case Alternative(trees) =>
              this(x, trees)
            case UnApply(fun, implicits, patterns) =>
              this(this(this(x, fun), implicits), patterns)
            case tree @ ValDef(_, tpt, _) =>
              inContext(localCtx) {
                this(this(x, tpt), tree.rhs)
              }
            case tree @ DefDef(_, tparams, vparamss, tpt, _) =>
              inContext(localCtx) {
                this(this(vparamss.foldLeft(this(x, tparams))(apply), tpt), tree.rhs)
              }
            case TypeDef(_, rhs) =>
              inContext(localCtx) {
                this(x, rhs)
              }
            case tree @ Template(constr, parents, self, _) if tree.derived.isEmpty =>
              this(this(this(this(x, constr), parents), self), tree.body)
            case Import(expr, _) =>
              this(x, expr)
            case PackageDef(pid, stats) =>
              this(this(x, pid), stats)(localCtx)
            case Annotated(arg, annot) =>
              this(this(x, arg), annot)
            case Thicket(ts) =>
              this(x, ts)
            case Hole(_, _, args) =>
              this(x, args)
            case _ =>
              foldMoreCases(x, tree)
          }
        }

      def foldMoreCases(x: X, tree: Tree)(implicit ctx: Context): X = {
        assert(ctx.reporter.errorsReported || ctx.mode.is(Mode.Interactive), tree)
          // In interactive mode, errors might come from previous runs.
          // In case of errors it may be that typed trees point to untyped ones.
          // The IDE can still traverse inside such trees, either in the run where errors
          // are reported, or in subsequent ones.
        x
      }
    }

    abstract class TreeTraverser extends TreeAccumulator[Unit] {
      def traverse(tree: Tree)(implicit ctx: Context): Unit
      def apply(x: Unit, tree: Tree)(implicit ctx: Context): Unit = traverse(tree)
      protected def traverseChildren(tree: Tree)(implicit ctx: Context): Unit = foldOver((), tree)
    }

    /** Fold `f` over all tree nodes, in depth-first, prefix order */
    class DeepFolder[X](f: (X, Tree) => X) extends TreeAccumulator[X] {
      def apply(x: X, tree: Tree)(implicit ctx: Context): X = foldOver(f(x, tree), tree)
    }

    /** Fold `f` over all tree nodes, in depth-first, prefix order, but don't visit
     *  subtrees where `f` returns a different result for the root, i.e. `f(x, root) ne x`.
     */
    class ShallowFolder[X](f: (X, Tree) => X) extends TreeAccumulator[X] {
      def apply(x: X, tree: Tree)(implicit ctx: Context): X = {
        val x1 = f(x, tree)
        if (x1.asInstanceOf[AnyRef] ne x.asInstanceOf[AnyRef]) x1
        else foldOver(x1, tree)
      }
    }

    def rename(tree: NameTree, newName: Name)(implicit ctx: Context): tree.ThisTree[T] = {
      tree match {
        case tree: Ident => cpy.Ident(tree)(newName)
        case tree: Select => cpy.Select(tree)(tree.qualifier, newName)
        case tree: Bind => cpy.Bind(tree)(newName, tree.body)
        case tree: ValDef => cpy.ValDef(tree)(name = newName.asTermName)
        case tree: DefDef => cpy.DefDef(tree)(name = newName.asTermName)
        case tree: TypeDef => cpy.TypeDef(tree)(name = newName.asTypeName)
      }
    }.asInstanceOf[tree.ThisTree[T]]

    /** Delegate to FunProto or FunProtoTyped depending on whether the prefix is `untpd` or `tpd`. */
    protected def FunProto(args: List[Tree], resType: Type)(using Context): ProtoTypes.FunProto

    /** Construct the application `$receiver.$method[$targs]($args)` using overloading resolution
     *  to find a matching overload of `$method` if necessary.
     *  This is useful when overloading resolution needs to be performed in a phase after typer.
     *  Note that this will not perform any kind of implicit search.
     *
     *  @param expectedType  An expected type of the application used to guide overloading resolution
     */
    def applyOverloaded(
        receiver: tpd.Tree, method: TermName, args: List[Tree], targs: List[Type],
        expectedType: Type)(using parentCtx: Context): tpd.Tree = {
      given ctx as Context = parentCtx.retractMode(Mode.ImplicitsEnabled)

      val typer = ctx.typer
      val proto = FunProto(args, expectedType)
      val denot = receiver.tpe.member(method)
      assert(denot.exists, i"no member $receiver . $method, members = ${receiver.tpe.decls}")
      val selected =
        if (denot.isOverloaded) {
          def typeParamCount(tp: Type) = tp.widen match {
            case tp: PolyType => tp.paramInfos.length
            case _ => 0
          }
          val allAlts = denot.alternatives
            .map(denot => TermRef(receiver.tpe, denot.symbol))
            .filter(tr => typeParamCount(tr) == targs.length)
            .filter { _.widen match {
              case MethodTpe(_, _, x: MethodType) => !x.isImplicitMethod
              case _ => true
            }}
          val alternatives = ctx.typer.resolveOverloaded(allAlts, proto)
          assert(alternatives.size == 1,
            i"${if (alternatives.isEmpty) "no" else "multiple"} overloads available for " +
              i"$method on ${receiver.tpe.widenDealiasKeepAnnots} with targs: $targs%, %; args: $args%, %; expectedType: $expectedType." +
              i"all alternatives: ${allAlts.map(_.symbol.showDcl).mkString(", ")}\n" +
              i"matching alternatives: ${alternatives.map(_.symbol.showDcl).mkString(", ")}.") // this is parsed from bytecode tree. there's nothing user can do about it
            alternatives.head
        }
        else TermRef(receiver.tpe, denot.symbol)
      val fun = receiver.select(selected).appliedToTypes(targs)

      val apply = untpd.Apply(fun, args)
      typer.ApplyTo(apply, fun, selected, proto, expectedType)
    }


    def resolveConstructor(atp: Type, args: List[Tree])(implicit ctx: Context): tpd.Tree = {
      val targs = atp.argTypes
      applyOverloaded(tpd.New(atp.typeConstructor), nme.CONSTRUCTOR, args, targs, atp)
    }
  }
}
