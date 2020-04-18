class TC
object A {
  given tc as TC
  def foo(using TC) = ()
}
object B {
  import A._
  foo             // error: no implicit argument was found
  foo(using tc)   // error: not found: tc
  foo(using A.tc) // ok
}
object C {
  import A._
  import A.tc
  foo            // ok
  foo(using tc)  // ok
}
object D {
  import A.{foo, given _}
  foo            // ok
  foo(using tc)  // ok
}
object E {
  import A.{_, given _}
  foo            // ok
  foo(using tc)  // ok
}
