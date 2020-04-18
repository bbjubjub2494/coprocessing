import scala.util.FromDigits
import scala.quoted._
import Even._

object EvenFromDigitsImpl:
  def apply(digits: Expr[String])(using ctx: QuoteContext): Expr[Even] = digits match {
    case Const(ds) =>
      val ev =
        try evenFromDigits(ds)
        catch {
          case ex: FromDigits.FromDigitsException =>
            ctx.error(ex.getMessage)
            Even(0)
        }
      '{Even(${Expr(ev.n)})}
    case _ =>
      '{evenFromDigits($digits)}
  }
