
package library.x {
  class X {
    class Foo
  }
}
package library {
  package object y extends library.x.X
}
