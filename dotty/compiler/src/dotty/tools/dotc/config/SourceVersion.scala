package dotty.tools
package dotc
package config

import core.Contexts.{Context, ctx}
import core.Names.TermName
import core.StdNames.nme
import core.Decorators.{given _}
import util.Property

enum SourceVersion:
  case `3.0-migration`, `3.0`, `3.1-migration`, `3.1`

  val isMigrating: Boolean = toString.endsWith("-migration")

  def stable: SourceVersion =
    if isMigrating then SourceVersion.values(ordinal + 1) else this

  def isAtLeast(v: SourceVersion) = stable.ordinal >= v.ordinal

object SourceVersion extends Property.Key[SourceVersion]:
  def defaultSourceVersion = `3.0`

  val allSourceVersionNames = values.toList.map(_.toString.toTermName)
end SourceVersion
