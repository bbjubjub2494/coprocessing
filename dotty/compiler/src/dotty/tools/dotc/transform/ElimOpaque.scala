package dotty.tools.dotc
package transform

import core._
import dotty.tools.dotc.transform.MegaPhase._
import Flags._
import Types._
import Contexts.Context
import Symbols._
import Decorators._
import Denotations.{SingleDenotation, NonSymSingleDenotation}
import SymDenotations.SymDenotation
import DenotTransformers._
import TypeUtils._

object ElimOpaque {
  val name: String = "elimOpaque"
}

/** Rewrites opaque type aliases to normal alias types */
class ElimOpaque extends MiniPhase with DenotTransformer {

  override def phaseName: String = ElimOpaque.name

  // Override checks need to take place before treating opaque types as aliases
  override def runsAfterGroupsOf: Set[String] = Set(typer.RefChecks.name)

  // base types of opaque aliases change
  override def changesBaseTypes = true

  def transform(ref: SingleDenotation)(implicit ctx: Context): SingleDenotation = {
    val sym = ref.symbol
    ref match {
      case ref: SymDenotation if sym.isOpaqueAlias =>
        ref.copySymDenotation(
          info = TypeAlias(ref.opaqueAlias),
          initFlags = ref.flags &~ (Opaque | Deferred))
      case ref: SymDenotation if sym.containsOpaques =>
        def stripOpaqueRefinements(tp: Type): Type = tp match {
          case RefinedType(parent, rname, TypeAlias(_))
          if ref.info.decl(rname).symbol.isOpaqueAlias => stripOpaqueRefinements(parent)
          case _ => tp
        }
        val cinfo = sym.asClass.classInfo
        val strippedSelfType = stripOpaqueRefinements(cinfo.selfType)
        ref.copySymDenotation(
          info = cinfo.derivedClassInfo(selfInfo = strippedSelfType),
          initFlags = ref.flags &~ Opaque)
      case ref: NonSymSingleDenotation if sym.isOpaqueAlias =>
        ref.derivedSingleDenotation(sym, TypeAlias(sym.opaqueAlias.asSeenFrom(ref.prefix, sym.owner)))
      case _ =>
        ref
    }
  }
}