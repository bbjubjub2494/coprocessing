package dotty.tools.dotc
package core

import Symbols._, Types._, Contexts._, Constants._, ast.tpd._
import config.ScalaVersion
import StdNames._
import dotty.tools.dotc.ast.tpd
import scala.util.Try
import util.Spans.Span

object Annotations {

  def annotClass(tree: Tree)(using Context) =
    if (tree.symbol.isConstructor) tree.symbol.owner
    else tree.tpe.typeSymbol

  abstract class Annotation {
    def tree(implicit ctx: Context): Tree

    def symbol(implicit ctx: Context): Symbol = annotClass(tree)

    def matches(cls: Symbol)(implicit ctx: Context): Boolean = symbol.derivesFrom(cls)

    def appliesToModule: Boolean = true // for now; see remark in SymDenotations

    def derivedAnnotation(tree: Tree)(implicit ctx: Context): Annotation =
      if (tree eq this.tree) this else Annotation(tree)

    def arguments(implicit ctx: Context): List[Tree] = ast.tpd.arguments(tree)

    def argument(i: Int)(implicit ctx: Context): Option[Tree] = {
      val args = arguments
      if (i < args.length) Some(args(i)) else None
    }
    def argumentConstant(i: Int)(implicit ctx: Context): Option[Constant] =
      for (ConstantType(c) <- argument(i) map (_.tpe)) yield c

    /** The tree evaluaton is in progress. */
    def isEvaluating: Boolean = false

    /** The tree evaluation has finished. */
    def isEvaluated: Boolean = true

    def ensureCompleted(implicit ctx: Context): Unit = tree

    def sameAnnotation(that: Annotation)(implicit ctx: Context): Boolean =
      symbol == that.symbol && tree.sameTree(that.tree)
  }

  case class ConcreteAnnotation(t: Tree) extends Annotation {
    def tree(implicit ctx: Context): Tree = t
  }

  /** The context to use to evaluate an annotation */
  private def annotCtx(using Context): Context =
    // We should always produce the same annotation tree, no matter when the
    // annotation is evaluated. Setting the phase to a pre-transformation phase
    // seems to be enough to ensure this (note that after erasure, `ctx.typer`
    // will be the Erasure typer, but that doesn't seem to affect the annotation
    // trees we create, so we leave it as is)
    ctx.withPhaseNoLater(ctx.picklerPhase)

  abstract class LazyAnnotation extends Annotation {
    protected var mySym: Symbol | (Context => Symbol)
    override def symbol(using parentCtx: Context): Symbol =
      assert(mySym != null)
      mySym match {
        case symFn: (Context => Symbol) @unchecked =>
          mySym = null
          mySym = symFn(annotCtx)
        case sym: Symbol if sym.defRunId != parentCtx.runId =>
          mySym = sym.denot.current.symbol
        case _ =>
      }
      mySym.asInstanceOf[Symbol]

    protected var myTree: Tree | (Context => Tree)
    def tree(using Context): Tree =
      assert(myTree != null)
      myTree match {
        case treeFn: (Context => Tree) @unchecked =>
          myTree = null
          myTree = treeFn(annotCtx)
        case _ =>
      }
      myTree.asInstanceOf[Tree]

    override def isEvaluating: Boolean = myTree == null
    override def isEvaluated: Boolean = myTree.isInstanceOf[Tree]
  }

  /** An annotation indicating the body of a right-hand side,
   *  typically of an inline method. Treated specially in
   *  pickling/unpickling and TypeTreeMaps
   */
  abstract class BodyAnnotation extends Annotation {
    override def symbol(implicit ctx: Context): ClassSymbol = defn.BodyAnnot
    override def derivedAnnotation(tree: Tree)(implicit ctx: Context): Annotation =
      if (tree eq this.tree) this else ConcreteBodyAnnotation(tree)
    override def arguments(implicit ctx: Context): List[Tree] = Nil
    override def ensureCompleted(implicit ctx: Context): Unit = ()
  }

  class ConcreteBodyAnnotation(body: Tree) extends BodyAnnotation {
    def tree(implicit ctx: Context): Tree = body
  }

  abstract class LazyBodyAnnotation extends BodyAnnotation {
    // Copy-pasted from LazyAnnotation to avoid having to turn it into a trait
    protected var myTree: Tree | (Context => Tree)
    def tree(using Context): Tree =
      assert(myTree != null)
      myTree match {
        case treeFn: (Context => Tree) @unchecked =>
          myTree = null
          myTree = treeFn(annotCtx)
        case _ =>
      }
      myTree.asInstanceOf[Tree]

    override def isEvaluating: Boolean = myTree == null
    override def isEvaluated: Boolean = myTree.isInstanceOf[Tree]
  }

  object LazyBodyAnnotation {
    def apply(bodyFn: Context ?=> Tree): LazyBodyAnnotation =
      new LazyBodyAnnotation:
        protected var myTree: Tree | (Context => Tree) = ctx => bodyFn(using ctx)
  }

  object Annotation {

    def apply(tree: Tree): ConcreteAnnotation = ConcreteAnnotation(tree)

    def apply(cls: ClassSymbol)(implicit ctx: Context): Annotation =
      apply(cls, Nil)

    def apply(cls: ClassSymbol, arg: Tree)(implicit ctx: Context): Annotation =
      apply(cls, arg :: Nil)

    def apply(cls: ClassSymbol, arg1: Tree, arg2: Tree)(implicit ctx: Context): Annotation =
      apply(cls, arg1 :: arg2 :: Nil)

    def apply(cls: ClassSymbol, args: List[Tree])(implicit ctx: Context): Annotation =
      apply(cls.typeRef, args)

    def apply(atp: Type, arg: Tree)(implicit ctx: Context): Annotation =
      apply(atp, arg :: Nil)

    def apply(atp: Type, arg1: Tree, arg2: Tree)(implicit ctx: Context): Annotation =
      apply(atp, arg1 :: arg2 :: Nil)

    def apply(atp: Type, args: List[Tree])(implicit ctx: Context): Annotation =
      apply(New(atp, args))

    /** Create an annotation where the tree is computed lazily. */
    def deferred(sym: Symbol)(treeFn: Context ?=> Tree)(implicit ctx: Context): Annotation =
      new LazyAnnotation {
        protected var myTree: Tree | (Context => Tree) = ctx => treeFn(using ctx)
        protected var mySym: Symbol | (Context => Symbol) = sym
      }

    /** Create an annotation where the symbol and the tree are computed lazily. */
    def deferredSymAndTree(symFn: Context ?=> Symbol)(treeFn: Context ?=> Tree)(implicit ctx: Context): Annotation =
      new LazyAnnotation {
        protected var mySym: Symbol | (Context => Symbol) = ctx => symFn(using ctx)
        protected var myTree: Tree | (Context => Tree) = ctx => treeFn(using ctx)
      }

    def deferred(atp: Type, args: List[Tree])(implicit ctx: Context): Annotation =
      deferred(atp.classSymbol)(New(atp, args))

    def deferredResolve(atp: Type, args: List[ast.untpd.Tree])(implicit ctx: Context): Annotation =
      deferred(atp.classSymbol)(ast.untpd.resolveConstructor(atp, args))

    /** Extractor for child annotations */
    object Child {

      /** A deferred annotation to the result of a given child computation */
      def later(delayedSym: Context ?=> Symbol, span: Span)(implicit ctx: Context): Annotation = {
        def makeChildLater(using Context) = {
          val sym = delayedSym
          New(defn.ChildAnnot.typeRef.appliedTo(sym.owner.thisType.select(sym.name, sym)), Nil)
            .withSpan(span)
        }
        deferred(defn.ChildAnnot)(makeChildLater)
      }

      /** A regular, non-deferred Child annotation */
      def apply(sym: Symbol, span: Span)(implicit ctx: Context): Annotation = later(sym, span)

      def unapply(ann: Annotation)(implicit ctx: Context): Option[Symbol] =
        if (ann.symbol == defn.ChildAnnot) {
          val AppliedType(_, (arg: NamedType) :: Nil) = ann.tree.tpe
          Some(arg.symbol)
        }
        else None
    }

    def makeSourceFile(path: String)(implicit ctx: Context): Annotation =
      apply(defn.SourceFileAnnot, Literal(Constant(path)))
  }

  def ThrowsAnnotation(cls: ClassSymbol)(implicit ctx: Context): Annotation = {
    val tref = cls.typeRef
    Annotation(defn.ThrowsAnnot.typeRef.appliedTo(tref), Ident(tref))
  }

  /** A decorator that provides queries for specific annotations
   *  of a symbol.
   */
  implicit class AnnotInfo(val sym: Symbol) extends AnyVal {

    def isDeprecated(implicit ctx: Context): Boolean =
      sym.hasAnnotation(defn.DeprecatedAnnot)

    def deprecationMessage(implicit ctx: Context): Option[String] =
      for {
        annot <- sym.getAnnotation(defn.DeprecatedAnnot)
        arg <- annot.argumentConstant(0)
      }
      yield arg.stringValue

    def migrationVersion(implicit ctx: Context): Option[Try[ScalaVersion]] =
      for {
        annot <- sym.getAnnotation(defn.MigrationAnnot)
        arg <- annot.argumentConstant(1)
      }
      yield ScalaVersion.parse(arg.stringValue)

    def migrationMessage(implicit ctx: Context): Option[Try[ScalaVersion]] =
      for {
        annot <- sym.getAnnotation(defn.MigrationAnnot)
        arg <- annot.argumentConstant(0)
      }
      yield ScalaVersion.parse(arg.stringValue)
  }
}
