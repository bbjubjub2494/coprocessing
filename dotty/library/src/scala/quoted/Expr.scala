package scala.quoted

import scala.quoted.show.SyntaxHighlight

/** Quoted expression of type `T` */
class Expr[+T] private[scala] {

  /** Show a source code like representation of this expression without syntax highlight */
  def show(using qctx: QuoteContext): String =
    this.unseal.showWith(SyntaxHighlight.plain)

  /** Show a source code like representation of this expression */
  def show(syntaxHighlight: SyntaxHighlight)(using qctx: QuoteContext): String =
    this.unseal.showWith(syntaxHighlight)

  /** Return the value of this expression.
   *
   *  Returns `None` if the expression does not contain a value or contains side effects.
   *  Otherwise returns the `Some` of the value.
   */
  @deprecated("Use Expr.unlift", "0.23")
  final def getValue[U >: T](using qctx: QuoteContext, unlift: Unliftable[U]): Option[U] = unlift(this)

  /** Return the unlifted value of this expression.
   *
   *  Returns `None` if the expression does not contain a value or contains side effects.
   *  Otherwise returns the `Some` of the value.
   */
  final def unlift[U >: T](using qctx: QuoteContext, unlift: Unliftable[U]): Option[U] =
    unlift(this)

  /** Return the unlifted value of this expression.
   *
   *  Emits an error error and throws if the expression does not contain a value or contains side effects.
   *  Otherwise returns the value.
   */
  @deprecated("Use Expr.unliftOrError", "0.23")
  final def value[U >: T](using qctx: QuoteContext, unlift: Unliftable[U]): U = unliftOrError

  /** Return the unlifted value of this expression.
   *
   *  Emits an error and throws if the expression does not contain a value or contains side effects.
   *  Otherwise returns the value.
   */
  final def unliftOrError[U >: T](using qctx: QuoteContext, unlift: Unliftable[U]): U =
    unlift(this).getOrElse(qctx.throwError(s"Expected a known value. \n\nThe value of: $show\ncould not be unlifted using $unlift", this))

  /** Pattern matches `this` against `that`. Effectively performing a deep equality check.
   *  It does the equivalent of
   *  ```
   *  this match
   *    case '{...} => true // where the contents of the pattern are the contents of `that`
   *    case _ => false
   *  ```
   */
  final def matches(that: Expr[Any])(using qctx: QuoteContext): Boolean =
    !scala.internal.quoted.Expr.unapply[Unit, Unit](this)(using that, false, qctx).isEmpty

  /** Checked cast to a `quoted.Expr[U]` */
  def cast[U](using tp: scala.quoted.Type[U])(using qctx: QuoteContext): scala.quoted.Expr[U] =
    qctx.tasty.internal.QuotedExpr_cast[U](this)(using tp, qctx.tasty.rootContext)

  /** View this expression `quoted.Expr[T]` as a `Term` */
  def unseal(using qctx: QuoteContext): qctx.tasty.Term =
    qctx.tasty.internal.QuotedExpr_unseal(this)(using qctx.tasty.rootContext)

}

object Expr {

  /** Converts a tuple `(T1, ..., Tn)` to `(Expr[T1], ..., Expr[Tn])` */
  type TupleOfExpr[Tup <: Tuple] = Tuple.Map[Tup, [X] =>> QuoteContext ?=> Expr[X]]

  /** `Expr.betaReduce(f)(x1, ..., xn)` is functionally the same as `'{($f)($x1, ..., $xn)}`, however it optimizes this call
   *   by returning the result of beta-reducing `f(x1, ..., xn)` if `f` is a known lambda expression.
   *
   *  `Expr.betaReduce` distributes applications of `Expr` over function arrows
   *   ```scala
   *   Expr.betaReduce(_): Expr[(T1, ..., Tn) => R] => ((Expr[T1], ..., Expr[Tn]) => Expr[R])
   *   ```
   */
  def betaReduce[F, Args <: Tuple, R, G](f: Expr[F])(using tf: TupledFunction[F, Args => R], tg: TupledFunction[G, TupleOfExpr[Args] => Expr[R]], qctx: QuoteContext): G =
    tg.untupled(args => qctx.tasty.internal.betaReduce(f.unseal, args.toArray.toList.map(_.asInstanceOf[QuoteContext => Expr[_]](qctx).unseal)).seal.asInstanceOf[Expr[R]])

  /** `Expr.betaReduceGiven(f)(x1, ..., xn)` is functionally the same as `'{($f)(using $x1, ..., $xn)}`, however it optimizes this call
   *   by returning the result of beta-reducing `f(using x1, ..., xn)` if `f` is a known lambda expression.
   *
   *  `Expr.betaReduceGiven` distributes applications of `Expr` over function arrows
   *   ```scala
   *   Expr.betaReduceGiven(_): Expr[(T1, ..., Tn) ?=> R] => ((Expr[T1], ..., Expr[Tn]) => Expr[R])
   *   ```
   */
  def betaReduceGiven[F, Args <: Tuple, R, G](f: Expr[F])(using tf: TupledFunction[F, Args ?=> R], tg: TupledFunction[G, TupleOfExpr[Args] => Expr[R]], qctx: QuoteContext): G =
    tg.untupled(args => qctx.tasty.internal.betaReduce(f.unseal, args.toArray.toList.map(_.asInstanceOf[QuoteContext => Expr[_]](qctx).unseal)).seal.asInstanceOf[Expr[R]])

  /** Returns a null expresssion equivalent to `'{null}` */
  def nullExpr: QuoteContext ?=> Expr[Null] = qctx ?=> {
    import qctx.tasty._
    Literal(Constant(null)).seal.asInstanceOf[Expr[Null]]
  }

  /** Returns a unit expresssion equivalent to `'{}` or `'{()}` */
  def unitExpr: QuoteContext ?=> Expr[Unit] = qctx ?=> {
    import qctx.tasty._
    Literal(Constant(())).seal.asInstanceOf[Expr[Unit]]
  }

  /** Returns an expression containing a block with the given statements and ending with the expresion
   *  Given list of statements `s1 :: s2 :: ... :: Nil` and an expression `e` the resulting expression
   *  will be equivalent to `'{ $s1; $s2; ...; $e }`.
   */
  def block[T](statements: List[Expr[_]], expr: Expr[T])(using qctx: QuoteContext): Expr[T] = {
    import qctx.tasty._
    Block(statements.map(_.unseal), expr.unseal).seal.asInstanceOf[Expr[T]]
  }

  /** Lift a value into an expression containing the construction of that value */
  def apply[T](x: T)(using qctx: QuoteContext, lift: Liftable[T]): Expr[T] = lift.toExpr(x)

  /** Lifts this sequence of expressions into an expression of a sequence
   *
   *  Transforms a sequence of expression
   *    `Seq(e1, e2, ...)` where `ei: Expr[T]`
   *  to an expression equivalent to
   *    `'{ Seq($e1, $e2, ...) }` typed as an `Expr[Seq[T]]`
   *  ```
   */
  def ofSeq[T](xs: Seq[Expr[T]])(using tp: Type[T], qctx: QuoteContext): Expr[Seq[T]] = Varargs(xs)

  /** Lifts this list of expressions into an expression of a list
   *
   *  Transforms a list of expression
   *    `List(e1, e2, ...)` where `ei: Expr[T]`
   *  to an expression equivalent to
   *    `'{ List($e1, $e2, ...) }` typed as an `Expr[List[T]]`
   */
  def  ofList[T](xs: Seq[Expr[T]])(using Type[T], QuoteContext): Expr[List[T]] =
    if (xs.isEmpty) '{ Nil } else '{ List(${Varargs(xs)}: _*) }

  /** Lifts this sequence of expressions into an expression of a tuple
   *
   *  Transforms a sequence of expression
   *    `Seq(e1, e2, ...)` where `ei: Expr[_]`
   *  to an expression equivalent to
   *    `'{ ($e1, $e2, ...) }` typed as an `Expr[Tuple]`
   */
  def ofTuple(seq: Seq[Expr[_]])(using qctx: QuoteContext): Expr[Tuple] = {
    seq match {
      case Seq() =>
        unitExpr
      case Seq('{ $x1: $t1 }) =>
        '{ Tuple1($x1) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }) =>
        '{ Tuple2($x1, $x2) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }) =>
        '{ Tuple3($x1, $x2, $x3) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }) =>
        '{ Tuple4($x1, $x2, $x3, $x4) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }) =>
        '{ Tuple5($x1, $x2, $x3, $x4, $x5) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }) =>
        '{ Tuple6($x1, $x2, $x3, $x4, $x5, $x6) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }) =>
        '{ Tuple7($x1, $x2, $x3, $x4, $x5, $x6, $x7) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }) =>
        '{ Tuple8($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }) =>
        '{ Tuple9($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }) =>
        '{ Tuple10($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }) =>
        '{ Tuple11($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }) =>
        '{ Tuple12($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }) =>
        '{ Tuple13($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }) =>
        '{ Tuple14($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }) =>
        '{ Tuple15($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }, '{ $x16: $t16 }) =>
        '{ Tuple16($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15, $x16) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }, '{ $x16: $t16 }, '{ $x17: $t17 }) =>
        '{ Tuple17($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15, $x16, $x17) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }, '{ $x16: $t16 }, '{ $x17: $t17 }, '{ $x18: $t18 }) =>
        '{ Tuple18($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15, $x16, $x17, $x18) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }, '{ $x16: $t16 }, '{ $x17: $t17 }, '{ $x18: $t18 }, '{ $x19: $t19 }) =>
        '{ Tuple19($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15, $x16, $x17, $x18, $x19) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }, '{ $x16: $t16 }, '{ $x17: $t17 }, '{ $x18: $t18 }, '{ $x19: $t19 }, '{ $x20: $t20 }) =>
        '{ Tuple20($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15, $x16, $x17, $x18, $x19, $x20) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }, '{ $x16: $t16 }, '{ $x17: $t17 }, '{ $x18: $t18 }, '{ $x19: $t19 }, '{ $x20: $t20 }, '{ $x21: $t21 }) =>
        '{ Tuple21($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15, $x16, $x17, $x18, $x19, $x20, $x21) }
      case Seq('{ $x1: $t1 }, '{ $x2: $t2 }, '{ $x3: $t3 }, '{ $x4: $t4 }, '{ $x5: $t5 }, '{ $x6: $t6 }, '{ $x7: $t7 }, '{ $x8: $t8 }, '{ $x9: $t9 }, '{ $x10: $t10 }, '{ $x11: $t11 }, '{ $x12: $t12 }, '{ $x13: $t13 }, '{ $x14: $t14 }, '{ $x15: $t15 }, '{ $x16: $t16 }, '{ $x17: $t17 }, '{ $x18: $t18 }, '{ $x19: $t19 }, '{ $x20: $t20 }, '{ $x21: $t21 }, '{ $x22: $t22 }) =>
        '{ Tuple22($x1, $x2, $x3, $x4, $x5, $x6, $x7, $x8, $x9, $x10, $x11, $x12, $x13, $x14, $x15, $x16, $x17, $x18, $x19, $x20, $x21, $x22) }
      case _ =>
        '{ Tuple.fromIArray(IArray(${Varargs(seq)}: _*)) }
    }
  }

  /** Given a tuple of the form `(Expr[A1], ..., Expr[An])`, outputs a tuple `Expr[(A1, ..., An)]`. */
  def ofTuple[T <: Tuple: Tuple.IsMappedBy[Expr]: Type](tup: T)(using qctx: QuoteContext): Expr[Tuple.InverseMap[T, Expr]] = {
    val elems: Seq[Expr[_]] = tup.asInstanceOf[Product].productIterator.toSeq.asInstanceOf[Seq[Expr[_]]]
    ofTuple(elems).cast[Tuple.InverseMap[T, Expr]]
  }

  /** Find an implicit of type `T` in the current scope given by `qctx`.
   *  Return `Some` containing the expression of the implicit or
   * `None` if implicit resolution failed.
   *
   *  @tparam T type of the implicit parameter
   *  @param tpe quoted type of the implicit parameter
   *  @param qctx current context
   */
  def summon[T](using tpe: Type[T])(using qctx: QuoteContext): Option[Expr[T]] = {
    import qctx.tasty._
    searchImplicit(tpe.unseal.tpe) match {
      case iss: ImplicitSearchSuccess => Some(iss.tree.seal.asInstanceOf[Expr[T]])
      case isf: ImplicitSearchFailure => None
    }
  }

}
