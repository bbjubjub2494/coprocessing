import scala.quoted._

import scala.quoted.autolift

object Macros {

  implicit inline def printTypes[T](inline x: T): Unit =
    ${impl('x)}

  def impl[T](x: Expr[T])(using qctx: QuoteContext) : Expr[Unit] = {
    import qctx.tasty._

    val buff = new StringBuilder
    val traverser = new TreeTraverser {
      override def traverseTree(tree: Tree)(implicit ctx: Context): Unit = tree match {
        case tree: TypeBoundsTree =>
          buff.append(tree.tpe.showExtractors)
          buff.append("\n\n")
          traverseTreeChildren(tree)
        case tree: TypeTree =>
          buff.append(tree.tpe.showExtractors)
          buff.append("\n\n")
          traverseTreeChildren(tree)
        case _ =>
          super.traverseTree(tree)
      }
    }

    val tree = x.unseal
    traverser.traverseTree(tree)
    '{print(${buff.result()})}
  }
}
