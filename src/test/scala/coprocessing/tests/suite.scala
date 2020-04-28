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
package coprocessing.tests

import minitest.SimpleTestSuite
import minitest.api._
import minitest.laws.Checkers
import org.scalacheck.Test.Parameters
import org.typelevel.discipline.Laws

trait BaseSuite extends SimpleTestSuite with Checkers {
  export Void.toVoid, SourceLocation.fromContext

  override lazy val checkConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(100)
      .withMaxDiscardRatio(5)

  lazy val slowCheckConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(10)
      .withMaxDiscardRatio(50)
      .withMaxSize(6)

  def checkAll(name: String, ruleSet: Laws#RuleSet): Unit =
    for (id, prop) <- ruleSet.all.properties do
      test(s"$name.$id") {
        check(prop)
      }
}
