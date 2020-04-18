import scala.quoted._
import scala.quoted.autolift

case class Location(owners: List[String])

object Location {

  implicit inline def location: Location = ${impl}

  def impl(using QuoteContext): Expr[Location] = {
    val list = List("a", "b", "c", "d", "e", "f")
    '{new Location(${list})}
  }

}
