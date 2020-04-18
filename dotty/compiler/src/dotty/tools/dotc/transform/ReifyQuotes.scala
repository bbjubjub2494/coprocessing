package dotty.tools.dotc
package transform

import core._
import Decorators._
import Flags._
import Types._
import Contexts._
import Symbols._
import Constants._
import ast.Trees._
import ast.{TreeTypeMap, untpd}
import util.Spans._
import util.SourcePosition
import tasty.TreePickler.Hole
import SymUtils._
import NameKinds._
import dotty.tools.dotc.ast.tpd
import typer.Implicits.SearchFailureType

import scala.collection.mutable
import dotty.tools.dotc.core.Annotations._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.quoted._
import dotty.tools.dotc.transform.TreeMapWithStages._
import dotty.tools.dotc.typer.Inliner
import dotty.tools.dotc.util.SourcePosition

import scala.annotation.constructorOnly


/** Translates quoted terms and types to `unpickle` method calls.
 *
 *  Transforms top level quote
 *   ```
 *   '{ ...
 *      val x1 = ???
 *      val x2 = ???
 *      ...
 *      ${ ... '{ ... x1 ... x2 ...} ... }
 *      ...
 *    }
 *    ```
 *  to
 *    ```
 *     unpickle(
 *       [[ // PICKLED TASTY
 *         ...
 *         val x1 = ???
 *         val x2 = ???
 *         ...
 *         Hole(0 | x1, x2)
 *         ...
 *       ]],
 *       List(
 *         (args: Seq[Any]) => {
 *           val x1$1 = args(0).asInstanceOf[Expr[T]]
 *           val x2$1 = args(1).asInstanceOf[Expr[T]] // can be asInstanceOf[Type[T]]
 *           ...
 *           { ... '{ ... ${x1$1} ... ${x2$1} ...} ... }
 *         }
 *       )
 *     )
 *    ```
 *  and then performs the same transformation on `'{ ... ${x1$1} ... ${x2$1} ...}`.
 *
 */
class ReifyQuotes extends MacroTransform {
  import ReifyQuotes._
  import tpd._

  override def phaseName: String = ReifyQuotes.name

  override def allowsImplicitSearch: Boolean = true

  override def checkPostCondition(tree: Tree)(implicit ctx: Context): Unit =
    tree match {
      case tree: RefTree if !ctx.inInlineMethod =>
        assert(!tree.symbol.isQuote)
        assert(!tree.symbol.isSplice)
      case _ : TypeDef =>
        assert(!tree.symbol.hasAnnotation(defn.InternalQuoted_QuoteTypeTagAnnot),
          s"${tree.symbol} should have been removed by PickledQuotes because it has a @quoteTypeTag")
      case _ =>
    }

  override def run(implicit ctx: Context): Unit =
    if (ctx.compilationUnit.needsStaging) super.run(freshStagingContext)

  protected def newTransformer(implicit ctx: Context): Transformer = new Transformer {
    override def transform(tree: tpd.Tree)(implicit ctx: Context): tpd.Tree =
      new QuoteReifier(null, new mutable.HashMap[Symbol, Tree => Tree], new Embedded, ctx.owner)(ctx).transform(tree)
  }

  /** The main transformer class
   *  @param  outer      the next outer reifier, null is this is the topmost transformer
   *  @param  embedded   a list of embedded quotes (if in a splice) or splices (if in a quote)
   *  @param  owner      the owner in the destination lifted lambda
   *  @param  capturers  register a reference defined in a quote but used in another quote nested in a splice.
   *                     Returns a version of the reference that needs to be used in its place.
   *                     '{
   *                       val x = ???
   *                       ${ ... '{ ... x ... } ... }
   *                     }
   *                     Eta expanding the `x` in `${ ... '{ ... x ... } ... }` will return a `${x$1}` for which the `x$1`
   *                     be created by some outer reifier.
   *                     This transformation is only applied to definitions at staging level 1.
   *                     See `isCaptured`.
   */
  private class QuoteReifier(outer: QuoteReifier, capturers: mutable.HashMap[Symbol, Tree => Tree],
                             val embedded: Embedded, val owner: Symbol)(@constructorOnly ictx: Context) extends TreeMapWithStages(ictx) { self =>

    import StagingContext._

    /** A nested reifier for a quote (if `isQuote = true`) or a splice (if not) */
    def nested(isQuote: Boolean)(implicit ctx: Context): QuoteReifier = {
      val nestedEmbedded = if (level > 1 || (level == 1 && isQuote)) embedded else new Embedded
      new QuoteReifier(this, capturers, nestedEmbedded, ctx.owner)(ctx)
    }

    /** Split `body` into a core and a list of embedded splices.
     *  Then if inside a splice, make a hole from these parts.
     *  If outside a splice, generate a call tp `scala.quoted.Unpickler.unpickleType` or
     *  `scala.quoted.Unpickler.unpickleExpr` that matches `tpe` with
     *  core and splices as arguments.
     */
    override protected def transformQuotation(body: Tree, quote: Tree)(implicit ctx: Context): Tree = {
      val isType = quote.symbol eq defn.InternalQuoted_typeQuote
      if (level > 0) {
        val body1 = nested(isQuote = true).transform(body)(quoteContext)
        super.transformQuotation(body1, quote)
      }
      else body match {
        case body: RefTree if isCaptured(body.symbol, level + 1) =>
          // Optimization: avoid the full conversion when capturing `x`
          // in '{ x } to '{ ${x$1} } and go directly to `x$1`
          capturers(body.symbol)(body)
        case _ =>
          val (body1, splices) = nested(isQuote = true).splitQuote(body)(quoteContext)
          if (level == 0) {
            val body2 =
              if (body1.isType) body1
              else Inlined(Inliner.inlineCallTrace(ctx.owner, quote.sourcePos), Nil, body1)
            pickledQuote(body2, splices, body.tpe, isType).withSpan(quote.span)
          }
          else
            body
      }
    }

    private def pickledQuote(body: Tree, splices: List[Tree], originalTp: Type, isType: Boolean)(implicit ctx: Context) = {
      def qctx: Tree = {
        val qctx = ctx.typer.inferImplicitArg(defn.QuoteContextClass.typeRef, body.span)
        if (qctx.tpe.isInstanceOf[SearchFailureType])
          ctx.error(ctx.typer.missingArgMsg(qctx, defn.QuoteContextClass.typeRef, ""), ctx.source.atSpan(body.span))
        qctx
      }

      def pickleAsLiteral(lit: Literal) = {
        def liftedValue(lifter: Symbol) =
          ref(lifter).appliedToType(originalTp).select(nme.toExpr).appliedTo(lit)
        lit.const.tag match {
          case Constants.NullTag => ref(defn.QuotedExprModule_nullExpr)
          case Constants.UnitTag => ref(defn.QuotedExprModule_unitExpr)
          case Constants.BooleanTag => liftedValue(defn.LiftableModule_BooleanIsLiftable)
          case Constants.ByteTag => liftedValue(defn.LiftableModule_ByteIsLiftable)
          case Constants.ShortTag => liftedValue(defn.LiftableModule_ShortIsLiftable)
          case Constants.IntTag => liftedValue(defn.LiftableModule_IntIsLiftable)
          case Constants.LongTag => liftedValue(defn.LiftableModule_LongIsLiftable)
          case Constants.FloatTag => liftedValue(defn.LiftableModule_FloatIsLiftable)
          case Constants.DoubleTag => liftedValue(defn.LiftableModule_DoubleIsLiftable)
          case Constants.CharTag => liftedValue(defn.LiftableModule_CharIsLiftable)
          case Constants.StringTag => liftedValue(defn.LiftableModule_StringIsLiftable)
        }
      }

      def pickleAsTasty() = {
        val meth =
          if (isType) ref(defn.Unpickler_unpickleType).appliedToType(originalTp)
          else ref(defn.Unpickler_unpickleExpr).appliedToType(originalTp.widen.dealias)
        val pickledQuoteStrings = liftList(PickledQuotes.pickleQuote(body).map(x => Literal(Constant(x))), defn.StringType)
        val splicesList = liftList(splices, defn.FunctionType(1).appliedTo(defn.SeqType.appliedTo(defn.AnyType), defn.AnyType))
        meth.appliedTo(pickledQuoteStrings, splicesList)
      }

      if (isType) {
        def tag(tagName: String) = ref(defn.QuotedTypeModule).select(tagName.toTermName).appliedTo(qctx)
        if (splices.isEmpty && body.symbol.isPrimitiveValueClass) tag(s"${body.symbol.name}Tag")
        else pickleAsTasty().select(nme.apply).appliedTo(qctx)
      }
      else getLiteral(body) match {
        case Some(lit) => pickleAsLiteral(lit)
        case _ => pickleAsTasty()
      }
    }

    /** If inside a quote, split the body of the splice into a core and a list of embedded quotes
     *  and make a hole from these parts. Otherwise issue an error, unless we
     *  are in the body of an inline method.
     */
    protected def transformSplice(body: Tree, splice: Tree)(implicit ctx: Context): Tree =
      if (level > 1) {
        val body1 = nested(isQuote = false).transform(body)(spliceContext)
        splice match {
          case splice: Apply => cpy.Apply(splice)(splice.fun, body1 :: Nil)
          case splice: Select => cpy.Select(splice)(body1, splice.name)
        }
      }
      else {
        assert(level == 1, "unexpected top splice outside quote")
        val (body1, quotes) = nested(isQuote = false).splitSplice(body)(spliceContext)
        val tpe = outer.embedded.getHoleType(body, splice)
        val hole = makeHole(splice.isTerm, body1, quotes, tpe).withSpan(splice.span)
        // We do not place add the inline marker for trees that where lifted as they come from the same file as their
        // enclosing quote. Any intemediate splice will add it's own Inlined node and cancel it before splicig the lifted tree.
        // Note that lifted trees are not necessarily expressions and that Inlined nodes are expected to be expressions.
        // For example we can have a lifted tree containing the LHS of an assignment (see tests/run-with-compiler/quote-var.scala).
        if (splice.isType || outer.embedded.isLiftedSymbol(body.symbol)) hole
        else Inlined(EmptyTree, Nil, hole).withSpan(splice.span)
      }

    /** Transforms the contents of a nested splice
     *  Assuming
     *     '{
     *        val x = ???
     *        val y = ???
     *        ${ ... '{ ... x .. y ... } ... }
     *      }
     *  then the spliced subexpression
     *     { ... '{ ... x ... y ... } ... }
     *  will be transformed to
     *     (args: Seq[Any]) => {
     *       val x$1 = args(0).asInstanceOf[Expr[Any]] // or .asInstanceOf[Type[Any]]
     *       val y$1 = args(1).asInstanceOf[Expr[Any]] // or .asInstanceOf[Type[Any]]
     *       { ... '{ ... ${x$1} ... ${y$1} ... } ... }
     *     }
     *
     *  See: `capture`
     *
     *  At the same time register embedded trees `x` and `y` to place as arguments of the hole
     *  placed in the original code.
     *     '{
     *        val x = ???
     *        val y = ???
     *        Hole(0 | x, y)
     *      }
     */
    private def makeLambda(tree: Tree)(implicit ctx: Context): Tree = {
      def body(arg: Tree)(implicit ctx: Context): Tree = {
        var i = 0
        transformWithCapturer(tree)(
          (captured: mutable.Map[Symbol, Tree]) => {
            (tree: Tree) => {
              def newCapture = {
                val tpw = tree.tpe.widen match {
                  case tpw: MethodicType => tpw.toFunctionType()
                  case tpw => tpw
                }
                assert(tpw.isInstanceOf[ValueType])
                val argTpe =
                  if (tree.isType) defn.QuotedTypeClass.typeRef.appliedTo(tpw)
                  else defn.FunctionType(1, isContextual = true).appliedTo(defn.QuoteContextClass.typeRef, defn.QuotedExprClass.typeRef.appliedTo(tpw))
                val selectArg = arg.select(nme.apply).appliedTo(Literal(Constant(i))).cast(argTpe)
                val capturedArg = SyntheticValDef(UniqueName.fresh(tree.symbol.name.toTermName).toTermName, selectArg)
                i += 1
                embedded.addTree(tree, capturedArg.symbol)
                captured.put(tree.symbol, capturedArg)
                capturedArg
              }
              val refSym = captured.getOrElseUpdate(tree.symbol, newCapture).symbol
              ref(refSym).withSpan(tree.span)
            }
          }
        )
      }
      /* Lambdas are generated outside the quote that is being reified (i.e. in outer.owner).
       * In case the case that level == -1 the code is not in a quote, it is in an inline method,
       * hence we should take that as owner directly.
       */
      val lambdaOwner = if (level == -1) ctx.owner else outer.owner

      val tpe = MethodType(defn.SeqType.appliedTo(defn.AnyType) :: Nil, tree.tpe.widen)
      val meth = ctx.newSymbol(lambdaOwner, UniqueName.fresh(nme.ANON_FUN), Synthetic | Method, tpe)
      Closure(meth, tss => body(tss.head.head)(ctx.withOwner(meth)).changeOwner(ctx.owner, meth)).withSpan(tree.span)
    }

    private def transformWithCapturer(tree: Tree)(capturer: mutable.Map[Symbol, Tree] => Tree => Tree)(implicit ctx: Context): Tree = {
      val captured = mutable.LinkedHashMap.empty[Symbol, Tree]
      val captured2 = capturer(captured)

      outer.localSymbols.foreach(sym => if (!sym.isInlineMethod) capturers.put(sym, captured2))

      val tree2 = transform(tree)
      capturers --= outer.localSymbols

      val captures = captured.result().valuesIterator.toList
      if (captures.isEmpty) tree2
      else Block(captures, tree2)
    }

    /** Returns true if this tree will be captured by `makeLambda`. Checks phase consistency and presence of capturer. */
    private def isCaptured(sym: Symbol, level: Int)(implicit ctx: Context): Boolean =
      level == 1 && levelOf(sym).contains(1) && capturers.contains(sym)

    /** Transform `tree` and return the resulting tree and all `embedded` quotes
     *  or splices as a pair.
     */
    private def splitQuote(tree: Tree)(implicit ctx: Context): (Tree, List[Tree]) = {
      val tree1 = stipTypeAnnotations(transform(tree))
      (tree1, embedded.getTrees)
    }

    private def splitSplice(tree: Tree)(implicit ctx: Context): (Tree, List[Tree]) = {
      val tree1 = makeLambda(tree)
      (tree1, embedded.getTrees)
    }

    private def stipTypeAnnotations(tree: Tree)(using Context): Tree =
      new TreeTypeMap(typeMap = _.stripAnnots).apply(tree)

    /** Register `body` as an `embedded` quote or splice
     *  and return a hole with `splices` as arguments and the given type `tpe`.
     */
    private def makeHole(isTermHole: Boolean, body: Tree, splices: List[Tree], tpe: Type)(implicit ctx: Context): Hole = {
      val idx = embedded.addTree(body, NoSymbol)

      /** Remove references to local types that will not be defined in this quote */
      def getTypeHoleType(using ctx: Context) = new TypeMap() {
        override def apply(tp: Type): Type = tp match
          case tp: TypeRef if tp.typeSymbol.isSplice =>
            apply(tp.dealias)
          case tp @ TypeRef(pre, _) if pre == NoPrefix || pre.termSymbol.isLocal =>
            val hiBound = tp.typeSymbol.info match
              case info @ ClassInfo(_, _, classParents, _, _) => classParents.reduce(_ & _)
              case info => info.hiBound
            apply(hiBound)
          case tp =>
            mapOver(tp)
      }

      /** Remove references to local types that will not be defined in this quote */
      def getTermHoleType(using ctx: Context) = new TypeMap() {
        override def apply(tp: Type): Type = tp match
          case tp @ TypeRef(NoPrefix, _) if capturers.contains(tp.symbol) =>
            // reference to term with a type defined in outer quote
            getTypeHoleType(tp)
          case tp @ TermRef(NoPrefix, _) if capturers.contains(tp.symbol) =>
            // widen term refs to terms defined in outer quote
            apply(tp.widenTermRefExpr)
          case tp =>
            mapOver(tp)
      }

      val holeType = if isTermHole then getTermHoleType(tpe) else getTypeHoleType(tpe)

      Hole(isTermHole, idx, splices).withType(holeType).asInstanceOf[Hole]
    }

    override def transform(tree: Tree)(implicit ctx: Context): Tree =
      if (tree.source != ctx.source && tree.source.exists)
        transform(tree)(ctx.withSource(tree.source))
      else reporting.trace(i"Reifier.transform $tree at $level", show = true) {
        tree match {
          case tree: RefTree if isCaptured(tree.symbol, level) =>
            val body = capturers(tree.symbol).apply(tree)
            val splice: Tree =
              if (tree.isType) body.select(tpnme.splice)
              else ref(defn.InternalQuoted_exprSplice).appliedToType(tree.tpe).appliedTo(body)

            transformSplice(body, splice)

          case tree: DefDef if tree.symbol.is(Macro) && level == 0 =>
            // Shrink size of the tree. The methods have already been inlined.
            // TODO move to FirstTransform to trigger even without quotes
            cpy.DefDef(tree)(rhs = defaultValue(tree.rhs.tpe))

          case tree: DefTree if level >= 1 =>
            val newAnnotations = tree.symbol.annotations.mapconserve { annot =>
              val newAnnotTree = transform(annot.tree)(using ctx.withOwner(tree.symbol))
              if (annot.tree == newAnnotTree) annot
              else ConcreteAnnotation(newAnnotTree)
            }
            tree.symbol.annotations = newAnnotations
            super.transform(tree)
          case _ =>
            super.transform(tree)
        }
      }

    private def liftList(list: List[Tree], tpe: Type)(implicit ctx: Context): Tree =
      list.foldRight[Tree](ref(defn.NilModule)) { (x, acc) =>
        acc.select("::".toTermName).appliedToType(tpe).appliedTo(x)
      }
  }
}


object ReifyQuotes {
  import tpd._

  val name: String = "reifyQuotes"

  def getLiteral(tree: tpd.Tree): Option[Literal] = tree match {
    case tree: Literal => Some(tree)
    case Block(Nil, e) => getLiteral(e)
    case Inlined(_, Nil, e) => getLiteral(e)
    case _ => None
  }

  class Embedded(trees: mutable.ListBuffer[tpd.Tree] = mutable.ListBuffer.empty, map: mutable.Map[Symbol, tpd.Tree] = mutable.Map.empty) {
    /** Adds the tree and returns it's index */
    def addTree(tree: tpd.Tree, liftedSym: Symbol): Int = {
      trees += tree
      if (liftedSym ne NoSymbol)
        map.put(liftedSym, tree)
      trees.length - 1
    }

    /** Type used for the hole that will replace this splice */
    def getHoleType(body: tpd.Tree, splice: tpd.Tree)(implicit ctx: Context): Type =
      // For most expressions the splice.tpe but there are some types that are lost by lifting
      // that can be recoverd from the original tree. Currently the cases are:
      //  * Method types: the splice represents a method reference
      map.get(body.symbol).map(_.tpe.widen).getOrElse(splice.tpe)

    def isLiftedSymbol(sym: Symbol)(implicit ctx: Context): Boolean = map.contains(sym)

    /** Get the list of embedded trees */
    def getTrees: List[tpd.Tree] = trees.toList

    override def toString: String = s"Embedded($trees, $map)"
  }
}

