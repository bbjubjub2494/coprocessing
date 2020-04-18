package dotty.tools.dotc.quoted

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.tastyreflect.ReflectionImpl

object QuoteContext {

  def apply()(using Context): scala.quoted.QuoteContext =
    new scala.quoted.QuoteContext(ReflectionImpl(summon[Context]))

  type ScopeId = Int

  private[dotty] def checkScopeId(id: ScopeId)(using Context): Unit =
    if (id != scopeId)
      throw new scala.quoted.ScopeException("Cannot call `scala.quoted.staging.run(...)` within a macro or another `run(...)`")

  // TODO Explore more fine grained scope ids.
  //      This id can only differentiate scope extrusion from one compiler instance to another.
  private[dotty] def scopeId(using Context): ScopeId =
    summon[Context].outersIterator.toList.last.hashCode()
}
