object Test {
  def res(x: quoted.Expr[Int])(using tasty.Reflection): quoted.Expr[Int] = x match {
    case '{ 1 + (${Bind(b)}: Int) } => ??? // error: Not found: Bind
    case _ => ???
  }
}
