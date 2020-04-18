package scala.quoted

package object matching {

  /** Find an implicit of type `T` in the current scope given by `qctx`.
   *  Return `Some` containing the expression of the implicit or
   * `None` if implicit resolution failed.
   *
   *  @tparam T type of the implicit parameter
   *  @param tpe quoted type of the implicit parameter
   *  @param qctx current context
   */
  @deprecated("use scala.quoted.Expr.summon[T] instead", "0.23.0")
  def summonExpr[T](using tpe: Type[T])(using qctx: QuoteContext): Option[Expr[T]] =
    Expr.summon[T]

  @deprecated("use scala.quoted.Const instead", "0.23.0")
  val Const: quoted.Const.type = quoted.Const

  @deprecated("use scala.quoted.Varargs instead", "0.23.0")
  val ExprSeq: quoted.Varargs.type = quoted.Varargs

  @deprecated("use scala.quoted.Lambda instead", "0.23.0")
  val Lambda: quoted.Lambda.type = quoted.Lambda

  @deprecated("use scala.quoted.Unlifted instead", "0.23.0")
  val Value: quoted.Unlifted.type = quoted.Unlifted

  @deprecated("use scala.quoted.Unlifted instead", "0.23.0")
  val ValueOfExpr: quoted.Unlifted.type = quoted.Unlifted

}
