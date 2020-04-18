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

 * Copyright (c) 2014-2019 by The Minitest Project Developers.
 * Some rights reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *     http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package coprocessing.tests

import scala.concurrent.ExecutionContext
import minitest.laws.Checkers
import minitest.api._
import org.typelevel.discipline.Laws
import org.scalacheck.Test.Parameters
import org.scalacheck.Prop

/** Re-implement `minitest.SimpleTestSuite` without bringing in any macros.
*/
trait MinimalTestSuite extends AbstractTestSuite {
  given SourceLocation = SourceLocation(Some("<unknown>"), None, 0)

  export Void.toVoid

  def test(name: String)(f: => Void): Unit =
    synchronized {
      if isInitialized then
        throw AssertionError(
            "Cannot define new tests after SimpleTestSuite was initialized"
        )
      propertiesSeq = propertiesSeq :+ TestSpec.sync[Unit](name, _ => f)
    }

  lazy val properties: Properties[_] =
    synchronized {
      isInitialized = true
      Properties[Unit](() => (), _ => Void.UnitRef, () => (), () => (), propertiesSeq)
    }

  private var propertiesSeq = Seq.empty[TestSpec[Unit, Unit]]
  private var isInitialized = false
  private given ExecutionContext = DefaultExecutionContext
}

trait BaseSuite extends MinimalTestSuite with Checkers {
  override lazy val checkConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(100)
      .withMaxDiscardRatio(5)

  lazy val slowCheckConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(10)
      .withMaxDiscardRatio(50)
      .withMaxSize(6)

  def checkAll(name: String, ruleSet: Laws#RuleSet, config: Parameters = checkConfig): Unit =
    for (id, prop: Prop) <- ruleSet.all.properties do
      test(name + "." + id) {
        check(prop)
      }
}
