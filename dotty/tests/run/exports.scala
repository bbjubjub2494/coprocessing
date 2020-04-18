object Test extends App {

  case class Config() {
    println("config")
  }

  class Printer {
    def print() = println("printing")
    object cfg extends Config
    given config as Config
  }

  class Scanner {
    def scan() = println("scanning")
    def (x: Any).scanned = scan()
  }
  object Scanner extends Scanner

  object Copier {
    val printer = new Printer
    export printer.{given _, _}
    export Scanner.{scan => scanIt, _}

    val config2 = summon[Config]
  }

  Copier.print()
  Copier.scanIt()
  Copier.cfg
  Copier.config
  Copier.config2

  def test() = {
    import Copier._
    print()
    scanIt()
    val x = config2
    val y = cfg
    1.scanned
  }
  test()

  val _: Int = B.x
}

final class Foo {
  lazy val foo : Foo = new Foo
  export foo._ // nothing is exported
}

class A:
  val x: Int = 1
class B(a: A):
  export a.x
object B extends B(A())