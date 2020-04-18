/* Copyright 2020 Julie Bettens
 * This file is part of Coprocessing.

 * Coprocessing is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.

 * Coprocessing is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.

 * You should have received a copy of the GNU Lesser General Public License
 * along with Coprocessing.  If not, see <https://www.gnu.org/licenses/>.
 */
package coprocessing.tests.util

import minitest.laws.Checkers
import minitest.api._
import org.typelevel.discipline.Laws

/** Integration with org.typelevel.discipline */
trait Discipline { self: MinimalTestSuite & Checkers =>
  def checkAll(name: String, ruleSet: Laws#RuleSet): Unit =
    for (id, prop) <- ruleSet.all.properties do
      test(s"$name.$id") {
        check(prop)
      }
}
