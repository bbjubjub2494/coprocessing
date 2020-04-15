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
