object Test {
  import scala.compiletime.constValue

  class Context {
    inline def givenIn[T](op: => Context ?=> T) = {
      given Context = this
      op
    }
  }

  def ctx: Context = new Context
  def g(using Context) = ()
  ctx.givenIn(g)

/* The last three statements should generate the following code:

    def ctx: Test.Context = new Test.Context()
    def g(implicit x$1: Test.Context): Unit = ()
    {
      val Context_this: Test.Context = Test.ctx
      {
        implicit def ctx: Test.Context = Context_this
        Test.g(ctx)
      }
    }
*/
}
