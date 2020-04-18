package dotty.tools
package dotc
package ast

import core._
import Names._, Types._ , Symbols._, StdNames._, Flags._, Contexts._

import org.junit.Test
import org.junit.Assert._

class DesugarTests extends DottyTest {
  import tpd._

  private def validSym(sym: Symbol)(implicit ctx: Context): Unit = {
      assert(
        // remaining symbols must be either synthetic:
        sym.is(Synthetic) ||
        // or be a constructor:
        sym.name == nme.CONSTRUCTOR,
        s"found: $sym (${sym.flagsString})"
      )
  }

  @Test def caseClassHasCorrectMembers: Unit =
    checkCompile("typer", "case class Foo(x: Int, y: String)") { (tree, context) =>
      implicit val ctx = context
      val ccTree = tree.find(tree => tree.symbol.name == typeName("Foo")).get
      val List(_, foo) = defPath(ccTree.symbol, tree).map(_.symbol.info)

      val x :: y :: rest = foo.decls.toList

      // Make sure we extracted the correct values from foo:
      assert(x.name == termName("x"))
      assert(y.name == termName("y"))

      rest.foreach(validSym)
    }

  @Test def caseClassCompanionHasCorrectMembers: Unit =
    checkCompile("typer", "case class Foo(x: Int, y: String)") { (tree, context) =>
      implicit val ctx = context
      val ccTree = tree.find(tree => tree.symbol.name == termName("Foo")).get
      val List(_, foo) = defPath(ccTree.symbol, tree).map(_.symbol.info)

      foo.decls.foreach(validSym)
    }
}
