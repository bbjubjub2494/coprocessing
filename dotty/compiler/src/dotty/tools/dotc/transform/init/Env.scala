package dotty.tools.dotc
package transform
package init

import core._
import Contexts.Context
import Types._
import Symbols._
import Decorators._

import ast.Trees._
import ast.tpd

import reporting.trace
import config.Printers.init

import scala.collection.mutable

import Effects._, Potentials._, Summary._

implicit def theCtx(implicit env: Env): Context = env.ctx

case class Env(ctx: Context, summaryCache: mutable.Map[ClassSymbol, ClassSummary]) {
  private implicit def self: Env = this

  // Methods that should be ignored in the checking
  lazy val ignoredMethods: Set[Symbol] = Set(
    ctx.requiredClass("scala.runtime.EnumValues").requiredMethod("register"),
    defn.Any_getClass,
    defn.Any_isInstanceOf,
    defn.Object_eq,
    defn.Object_ne,
    defn.Object_synchronized
  )

  def withCtx(newCtx: Context): Env = this.copy(ctx = newCtx)

  def withOwner(owner: Symbol) = this.copy(ctx = this.ctx.withOwner(owner))

  /** Whether values of a given type is always fully initialized?
   *
   *  It's true for primitive values
   */
  def isAlwaysInitialized(tp: Type)(implicit env: Env): Boolean = {
    val sym = tp.widen.finalResultType.typeSymbol
    sym.isPrimitiveValueClass || sym == defn.StringClass
  }

  /** Summary of a method or field */
  def summaryOf(cls: ClassSymbol): ClassSummary =
    if (summaryCache.contains(cls)) summaryCache(cls)
    else trace("summary for " + cls.show, init, s => s.asInstanceOf[ClassSummary].show) {
      val summary = Summarization.classSummary(cls)
      summaryCache(cls) = summary
      summary
    }
}
