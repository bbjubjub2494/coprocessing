implicit def f[X <: Undefined](implicit a: Int): X = ??? // error
def g(arg: h.NonExistent): Int = ???
val h: Int = ???