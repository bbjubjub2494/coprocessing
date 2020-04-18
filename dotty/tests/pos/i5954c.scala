abstract class MatcherFactory1[A] {
  class AndNotWord
}

object MatcherFactory1 {
  import scala.quoted._

  def impl(self: Expr[MatcherFactory1[Int]#AndNotWord])(using QuoteContext) =
    '{ val a: Any = $self }


  def impl[T: Type](self: Expr[MatcherFactory1[T]#AndNotWord])(using QuoteContext) =
    '{ val a: Any = $self }

}
