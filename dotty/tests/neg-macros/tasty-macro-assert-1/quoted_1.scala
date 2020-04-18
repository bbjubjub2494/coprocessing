import scala.quoted._

object Asserts {

  implicit class Ops[T](left: T) {
    def ===(right: T): Boolean = left == right
    def !==(right: T): Boolean = left != right
  }

  object Ops

  inline def macroAssert(inline cond: Boolean): Unit =
    ${impl('cond)}

  def impl(cond: Expr[Boolean])(using qctx: QuoteContext) : Expr[Unit] = {
    import qctx.tasty._

    val tree = cond.unseal

    def isOps(tpe: TypeOrBounds): Boolean = tpe match {
      case tpe: TermRef => tpe.termSymbol.isDefDef && tpe.name == "Ops"// TODO check that the parent is Asserts
      case _ => false
    }

    object OpsTree {
      def unapply(arg: Term): Option[Term] = arg match {
        case Apply(TypeApply(term, _), left :: Nil) if isOps(term.tpe) =>
          Some(left)
        case _ => None
      }
    }

    tree match {
      case Inlined(_, Nil, Apply(Select(OpsTree(left), op), right :: Nil)) =>
        '{assertTrue(${left.seal.cast[Boolean]})} // Buggy code. To generate the errors
      case _ =>
        '{assertTrue($cond)}
    }

  }

  def assertEquals[T](left: T, right: T): Unit = {
    if (left != right) {
      println(
        s"""Error left did not equal right:
           |  left  = $left
           |  right = $right""".stripMargin)
    }

  }

  def assertNotEquals[T](left: T, right: T): Unit = {
    if (left == right) {
      println(
        s"""Error left was equal to right:
           |  left  = $left
           |  right = $right""".stripMargin)
    }

  }

  def assertTrue(cond: Boolean): Unit = {
    if (!cond)
      println("Condition was false")
  }

}
