package dotty.tools
package dotc
package reporting

import org.junit.Assert._
import org.junit.Test
import core.Contexts._

class TestMessageLaziness extends DottyTest {
  ctx = ctx.fresh.setReporter(new NonchalantReporter)

  class NonchalantReporter(implicit ctx: Context) extends Reporter
  with UniqueMessagePositions with HideNonSensicalMessages {
    def doReport(dia: Diagnostic)(implicit ctx: Context) = ???

    override def report(dia: Diagnostic)(implicit ctx: Context) = ()
  }

  case class LazyError() extends Message(ErrorMessageID.LazyErrorId) {
    val kind = "Test"
    def msg = throw new Error("Didn't stay lazy.")
    def explain = ""
  }

  @Test def assureLazy =
    ctx.error(LazyError())

  @Test def assureLazyExtendMessage =
    ctx.errorOrMigrationWarning(LazyError(), from = config.SourceVersion.`3.1`)
}
