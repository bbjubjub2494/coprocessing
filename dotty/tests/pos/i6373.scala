object Test {
  class Test  {
    class Context(val t: Boolean)

    type Contextual[T] = Context ?=> T

    inline def f(): Contextual[Boolean] = summon[Context].t

    given ctx as Context = new Context(true)

    f()
  }
}