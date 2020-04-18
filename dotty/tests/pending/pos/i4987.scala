object Foo {

  class Expr[T]

  abstract class Liftable[T] {
    def toExpr(x: T): Expr[T]
  }

  implicit class LiftExprOps[T](val x: T) extends AnyVal {
    def toExpr(implicit ev: Liftable[T]): Expr[T] = ev.toExpr(x)
  }

  implicit def NilIsLiftable: Liftable[Nil.type] = ???

  Expr(Nil)(using NilIsLiftable)
  Expr(Nil)
}