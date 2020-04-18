package dotty.tools.dotc.tastyreflect

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core._
import dotty.tools.dotc.core.Contexts._

import scala.quoted.show.SyntaxHighlight

object ReflectionImpl {

  def apply(rootContext: Contexts.Context): scala.tasty.Reflection =
    new scala.tasty.Reflection(new ReflectionCompilerInterface(rootContext))

  def showTree(tree: tpd.Tree)(implicit ctx: Contexts.Context): String = {
    val refl = new scala.tasty.Reflection(new ReflectionCompilerInterface(MacroExpansion.context(tree)))
    val reflCtx = ctx.asInstanceOf[refl.Context]
    val reflTree = tree.asInstanceOf[refl.Tree]
    val syntaxHighlight =
      if (ctx.settings.color.value == "always") SyntaxHighlight.ANSI
      else SyntaxHighlight.plain
    new scala.tasty.reflect.SourceCodePrinter[refl.type](refl)(syntaxHighlight).showTree(reflTree)(using reflCtx)
  }
}

