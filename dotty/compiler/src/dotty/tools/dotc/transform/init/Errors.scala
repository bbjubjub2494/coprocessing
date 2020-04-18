package dotty.tools.dotc
package transform
package init

import ast.tpd._

import core._
import Decorators._, printing.SyntaxHighlighting
import Types._, Symbols._, Contexts._
import util.SourcePosition

import Effects._, Potentials._

object Errors {
  type Errors = Set[Error]
  val empty: Errors = Set.empty

  def show(errs: Errors)(implicit ctx: Context): String =
    errs.map(_.show).mkString(", ")

  sealed trait Error {
    def source: Tree
    def trace: Vector[Tree]
    def show(implicit ctx: Context): String

    def report(implicit ctx: Context): Unit =
      ctx.warning(show + stacktrace, source.sourcePos)

    def toErrors: Errors = Set(this)

    def stacktrace(implicit ctx: Context): String = if (trace.isEmpty) "" else " Calling trace:\n" + {
      var indentCount = 0
      var last: String = ""
      val sb = new StringBuilder
      trace.foreach { tree =>
        indentCount += 1
        val pos = tree.sourcePos
        val prefix = s"${ " " * indentCount }-> "
        val line =
          if pos.source.exists then
            val loc = "[ " + pos.source.file.name + ":" + (pos.line + 1) + " ]"
            val code = SyntaxHighlighting.highlight(pos.lineContent.trim)
            i"$code\t$loc"
          else
            tree.show

        if (last != line)  sb.append(prefix + line + "\n")

        last = line
      }
      sb.toString
    }

    /** Flatten UnsafePromotion errors
     */
    def flatten: Errors = this match {
      case unsafe: UnsafePromotion => unsafe.errors.flatMap(_.flatten)
      case _ => Set(this)
    }
  }

  /** Access non-initialized field */
  case class AccessNonInit(field: Symbol, trace: Vector[Tree]) extends Error {
    def source: Tree = trace.last
    def show(implicit ctx: Context): String =
      "Access non-initialized field " + field.name.show + "."

    override def report(implicit ctx: Context): Unit = ctx.error(show + stacktrace, field.sourcePos)
  }

  /** Promote `this` under initialization to fully-initialized */
  case class PromoteThis(pot: ThisRef, source: Tree, trace: Vector[Tree]) extends Error {
    def show(implicit ctx: Context): String = "Promote the value under initialization to fully-initialized."
  }

  /** Promote `this` under initialization to fully-initialized */
  case class PromoteWarm(pot: Warm, source: Tree, trace: Vector[Tree]) extends Error {
    def show(implicit ctx: Context): String =
      "Promoting the value under initialization to fully-initialized."
  }

  /** Promote a cold value under initialization to fully-initialized */
  case class PromoteCold(source: Tree, trace: Vector[Tree]) extends Error {
    def show(implicit ctx: Context): String =
      "Promoting the value " + source.show + " to fully-initialized while it is under initialization" + "."
  }

  case class AccessCold(field: Symbol, source: Tree, trace: Vector[Tree]) extends Error {
    def show(implicit ctx: Context): String =
      "Access field " + source.show + " on a value with an unknown initialization status" + "."
  }

  case class CallCold(meth: Symbol, source: Tree, trace: Vector[Tree]) extends Error {
    def show(implicit ctx: Context): String =
      "Call method " + source.show + " on a value with an unknown initialization" + "."
  }

  case class CallUnknown(meth: Symbol, source: Tree, trace: Vector[Tree]) extends Error {
    def show(implicit ctx: Context): String =
      "Calling the external method " + meth.show + " may cause initialization errors" + "."
  }

  /** Promote a value under initialization to fully-initialized */
  case class UnsafePromotion(pot: Potential, source: Tree, trace: Vector[Tree], errors: Errors) extends Error {
    assert(errors.nonEmpty)

    override def report(implicit ctx: Context): Unit = ctx.warning(show, source.sourcePos)

    def show(implicit ctx: Context): String = {
      var index = 0
      "Promoting the value to fully-initialized is unsafe.\n" + stacktrace +
        "\nThe unsafe promotion may cause the following problem(s):\n" +
        (errors.flatMap(_.flatten).map { error =>
          index += 1
          s"\n$index. " + error.show + error.stacktrace
        }.mkString)
    }
  }
}