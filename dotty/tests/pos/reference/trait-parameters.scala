trait Greeting(val name: String) {
  def msg = s"How are you, $name"
}

trait FormalGreeting extends Greeting {
  override def msg = s"How do you do, $name"
}

class C extends Greeting("Bob") {
  println(msg)
}

class D extends C with Greeting

class E extends Greeting("Bob") with FormalGreeting

// class D2 extends C with Greeting("Bill") // error


