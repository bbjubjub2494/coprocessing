package dotty.tools
package dottydoc
package core

import dotc.core.Contexts.Context
import dotc.core.Symbols.Symbol
import dotc.core.Comments.{ Comment => CompilerComment }
import transform.DocMiniPhase
import model._
import model.internal._
import model.comment._
import util.syntax._

/** Phase to add docstrings to the Dottydoc AST */
class DocstringPhase extends DocMiniPhase with CommentParser with CommentCleaner {

  private def getComment(sym: Symbol)(implicit ctx: Context): Option[CompilerComment] = {
    ctx.docbase.docstring(sym)
    .orElse {
      // If the symbol doesn't have a docstring, look for an overridden
      // ancestor with a docstring
      sym.allOverriddenSymbols.collectFirst {
        case parentSym if ctx.docbase.docstring(parentSym).isDefined =>
          parentSym
      }
      .flatMap(ctx.docbase.docstring)
    }
  }

  private def parsedComment(ent: Entity)(implicit ctx: Context): Option[Comment] = {
    for {
      comment <- getComment(ent.symbol)
      text <- comment.expandedBody
    } yield {
      val parsed = parse(ent, ctx.docbase.packages, clean(text), text, comment.span)
      if (ctx.settings.wikiSyntax.value)
        WikiComment(ent, parsed, comment.span).comment
      else
        MarkdownComment(ent, parsed, comment.span).comment
    }
  }

  override def transformPackage(implicit ctx: Context) = { case ent: PackageImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }

  override def transformClass(implicit ctx: Context) = { case ent: ClassImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }

  override def transformCaseClass(implicit ctx: Context) = { case ent: CaseClassImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }

  override def transformTrait(implicit ctx: Context) = { case ent: TraitImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }

  override def transformObject(implicit ctx: Context) = { case ent: ObjectImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }

  override def transformDef(implicit ctx: Context) = { case ent: DefImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }

  override def transformVal(implicit ctx: Context) = { case ent: ValImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }

  override def transformTypeAlias(implicit ctx: Context) = { case ent: TypeAliasImpl =>
    ent.copy(comment = parsedComment(ent)) :: Nil
  }
}
