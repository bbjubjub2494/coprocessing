import scala.quoted._

import scala.quoted.unsafe._

object Macros {


  inline def lift[R[_]](sym: Symantics { type Repr = R })(inline a: Int): R[Int] = ${impl('sym, 'a)}


  private def impl[R[_]: Type](sym: Expr[Symantics { type Repr[X] = R[X] }], expr: Expr[Int])(using QuoteContext): Expr[R[Int]] = {

    type Env = Map[Int, Any]

    given ev0 as Env = Map.empty

    def envWith[T](id: Int, ref: Expr[R[T]])(using env: Env): Env =
      env.updated(id, ref)

    object FromEnv {
      def unapply[T](e: Expr[Any])(using env: Env): Option[Expr[R[T]]] =
        e match
          case '{envVar[$t](${Const(id)})} =>
            env.get(id).asInstanceOf[Option[Expr[R[T]]]] // We can only add binds that have the same type as the refs
          case _ =>
            None
    }

    def lift[T: Type](e: Expr[T])(using env: Env): Expr[R[T]] = ((e: Expr[Any]) match {
      case Const(e: Int) => '{ $sym.int(${Expr(e)}).asInstanceOf[R[T]] }
      case Const(e: Boolean) => '{ $sym.bool(${Expr(e)}).asInstanceOf[R[T]] }

      case '{ ($x: Int) + ($y: Int) } =>
        '{ $sym.add(${lift(x)}, ${lift(y)}).asInstanceOf[R[T]] }

      case '{ ($x: Int) * ($y: Int) } =>
        '{ $sym.mult(${lift(x)}, ${lift(y)}).asInstanceOf[R[T]] }

      case '{ ($x: Int) <= ($y: Int) } =>
        '{ $sym.leq(${lift(x)}, ${lift(y)}).asInstanceOf[R[T]] }

      case '{ ($f: $t => $u)($arg) } =>
        '{ $sym.app[$t, $u](${lift(f)}, ${lift(arg)}).asInstanceOf[R[T]] }

      case '{ (if ($cond) $thenp else $elsep): $t } =>
        '{ $sym.ifThenElse[$t](${lift(cond)}, ${lift(thenp)}, ${lift(elsep)}) }.asInstanceOf[Expr[R[T]]]

      case '{ (x0: Int) => ($bodyFn: Int => Any)(x0) } =>
        val (i, nEnvVar) = freshEnvVar[Int]()
        val body2 = UnsafeExpr.open(bodyFn) { (body1, close) => close(body1)(nEnvVar) }
        '{ $sym.lam((x: R[Int]) => ${given Env = envWith(i, 'x)(using env); lift(body2)}).asInstanceOf[R[T]] }

      case '{ (x0: Boolean) => ($bodyFn: Boolean => Any)(x0) } =>
        val (i, nEnvVar) = freshEnvVar[Boolean]()
        val body2 = UnsafeExpr.open(bodyFn) { (body1, close) => close(body1)(nEnvVar) }
        '{ $sym.lam((x: R[Boolean]) => ${given Env = envWith(i, 'x)(using env); lift(body2)}).asInstanceOf[R[T]] }

      case '{ (x0: Int => Int) => ($bodyFn: (Int => Int) => Any)(x0) } =>
        val (i, nEnvVar) = freshEnvVar[Int => Int]()
        val body2 = UnsafeExpr.open(bodyFn) { (body1, close) => close(body1)(nEnvVar) }
        '{ $sym.lam((x: R[Int => Int]) => ${given Env = envWith(i, 'x)(using env); lift(body2)}).asInstanceOf[R[T]] }

      case '{ Symantics.fix[$t, $u]($f) } =>
        '{ $sym.fix[$t, $u]((x: R[$t => $u]) => $sym.app(${lift(f)}, x)).asInstanceOf[R[T]] }

      case FromEnv(expr) => expr.asInstanceOf[Expr[R[T]]]

      case _ =>
        summon[QuoteContext].error("Expected explicit value but got: " + e.show, e)
        '{ ??? }

    })

    lift(expr)
  }

}

def freshEnvVar[T: Type]()(using QuoteContext): (Int, Expr[T]) = {
  v += 1
  (v, '{envVar[T](${Expr(v)})})
}
var v = 0
def envVar[T](i: Int): T = ???

trait Symantics {
  type Repr[X]
  def int(x: Int): Repr[Int]
  def bool(x: Boolean): Repr[Boolean]
  def lam[A, B](f: Repr[A] => Repr[B]): Repr[A => B]
  def app[A, B](f: Repr[A => B], arg: Repr[A]): Repr[B]
  def fix[A, B]: (Repr[A => B] => Repr[A => B]) => Repr[A => B]
  def add(x: Repr[Int], y: Repr[Int]): Repr[Int]
  def mult(x: Repr[Int], y: Repr[Int]): Repr[Int]
  def leq(x: Repr[Int], y: Repr[Int]): Repr[Boolean]
  def ifThenElse[A](cond: Repr[Boolean], thenp: => Repr[A], elsep: => Repr[A]): Repr[A]
}

object Symantics {
  def fix[A, B](f: (A => B) => (A => B)): A => B = throw new Exception("Must be used inside of `lift`")
}
