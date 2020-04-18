package dotty.tools.languageserver

import org.junit.Test

import dotty.tools.languageserver.util.Code._

import dotty.tools.dotc.util.Signatures.{Param => P, Signature => S}

class SignatureHelpTest {

  @Test def fromJava: Unit = {
    val signature =
      S("codePointAt", Nil, List(List(P("x$0", "Int"))), Some("Int"))
    code"""object O {
             "hello".codePointAt($m1)
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
  }

  @Test def fromScala2: Unit = {
    val applySig =
      // TODO: Ideally this should say `List[A]`, not `CC[A]`
      S("apply[A]", Nil, List(List(P("elems", "A*"))), Some("CC[A]"))
    val mapSig =
      S("map[B]", Nil, List(List(P("f", "A => B"))), Some("List[B]"))
    code"""object O {
             List($m1)
             List(1, 2, 3).map($m2)
           }""".withSource
      .signatureHelp(m1, List(applySig), Some(0), 0)
      .signatureHelp(m2, List(mapSig), Some(0), 0)
  }

  /** Implicit parameter lists consisting solely of DummyImplicits are hidden. */
  @Test def hiddenDummyParams: Unit = {
    val foo1Sig =
      S("foo1", Nil, List(List(P("param0", "Int"))), Some("Int"))
    val foo2Sig =
      S("foo2", Nil, List(List(P("param0", "Int"))), Some("Int"))
    val foo3Sig =
      S("foo3", Nil, List(List(P("param0", "Int")),
        List(P("dummy", "DummyImplicit"))), Some("Int"))
    val foo4Sig =
      S("foo4", Nil, List(List(P("param0", "Int")),
        List(P("x", "Int", isImplicit = true), P("dummy", "DummyImplicit", isImplicit = true))), Some("Int"))
    code"""object O {
             def foo1(param0: Int)(implicit dummy: DummyImplicit): Int = ???
             def foo2(param0: Int)(implicit dummy1: DummyImplicit, dummy2: DummyImplicit): Int = ???
             def foo3(param0: Int)(dummy: DummyImplicit): Int = ???
             def foo4(param0: Int)(implicit x: Int, dummy: DummyImplicit): Int = ???
             foo1($m1)
             foo2($m2)
             foo3($m3)
             foo4($m4)
           }""".withSource
      .signatureHelp(m1, List(foo1Sig), Some(0), 0)
      .signatureHelp(m2, List(foo2Sig), Some(0), 0)
      .signatureHelp(m3, List(foo3Sig), Some(0), 0)
      .signatureHelp(m4, List(foo4Sig), Some(0), 0)
  }

  @Test def singleParam: Unit = {
    val signature =
      S("foo", Nil, List(List(P("param0", "Int"))), Some("Int"))
    code"""object O {
             def foo(param0: Int): Int = ???
             foo($m1)
             foo(0$m2)
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
      .signatureHelp(m2, List(signature), Some(0), 0)
  }

  @Test def twoParams: Unit = {
    val signature = S("foo", Nil, List(List(P("param0", "Int"), P("param1", "String"))), Some("Int"))
    code"""object O {
             def foo(param0: Int, param1: String): Int = ???
             foo($m1)
             foo(0, $m2)
             foo(0, "$m3")
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
      .signatureHelp(m2, List(signature), Some(0), 1)
      .signatureHelp(m3, List(signature), Some(0), 1)
  }

  @Test def noMatchingOverload: Unit = {
    val sig0 = S("foo", Nil, List(List(P("param0", "Int"))), Some("Nothing"))
    val sig1 = S("foo", Nil, List(List(P("param1", "String"))), Some("Nothing"))

    code"""object O {
             def foo(param0: Int): Nothing = ???
             def foo(param1: String): Nothing = ???
             foo($m1)
             foo(0$m2)
             foo(""$m3)
           }""".withSource
      .signatureHelp(m1, List(sig0, sig1), None, 0)
      .signatureHelp(m2, List(sig0, sig1), Some(0), 0)
      .signatureHelp(m3, List(sig0, sig1), Some(1), 0)
  }

  @Test def singleMatchingOverload: Unit = {
    val sig0 = S("foo", Nil, List(List(P("param0", "Int"), P("param1", "String"))), Some("Nothing"))
    val sig1 = S("foo", Nil, List(List(P("param0", "String"), P("param1", "Int"))), Some("Nothing"))
    code"""object O {
             def foo(param0: Int, param1: String): Nothing = ???
             def foo(param0: String, param1: Int): Nothing = ???
             foo($m1)
             foo(0$m2)
             foo(""$m3)
             foo(0, $m4)
             foo("", $m5)
           }""".withSource
      .signatureHelp(m1, List(sig0, sig1), None, 0)
      .signatureHelp(m2, List(sig0, sig1), Some(0), 0)
      .signatureHelp(m3, List(sig0, sig1), Some(1), 0)
      .signatureHelp(m4, List(sig0, sig1), Some(0), 1)
      .signatureHelp(m5, List(sig0, sig1), Some(1), 1)
  }

  @Test def multipleMatchingOverloads: Unit = {
    val sig0 = S("foo", Nil, List(List(P("param0", "Int"), P("param1", "Int"))), Some("Nothing"))
    val sig1 = S("foo", Nil, List(List(P("param0", "Int"), P("param1", "Boolean"))), Some("Nothing"))
    val sig2 = S("foo", Nil, List(List(P("param0", "String"), P("param1", "Int"))), Some("Nothing"))
    val sigs = List(sig0, sig1, sig2)
    code"""object O {
             def foo(param0: Int, param1: Int): Nothing = ???
             def foo(param0: Int, param1: Boolean): Nothing = ???
             def foo(param0: String, param1: Int): Nothing = ???
             foo($m1)
             foo(0$m2)
             foo(""$m3)
             foo(0, $m4)
             foo("", $m5)
             foo(0, 0$m6)
             foo(0, ""$m7)
             foo("", 0$m8)
           }""".withSource
      .signatureHelp(m1, sigs, None, 0)
      .signatureHelp(m2, sigs, None, 0)
      .signatureHelp(m3, sigs, Some(2), 0)
      .signatureHelp(m4, sigs, None, 1)
      .signatureHelp(m5, sigs, Some(2), 1)
      .signatureHelp(m6, sigs, Some(0), 1)
      .signatureHelp(m7, sigs, Some(1), 1)
      .signatureHelp(m8, sigs, Some(2), 1)
  }

  @Test def ambiguousOverload: Unit = {
    val sig0 = S("foo", Nil, List(List(P("param0", "String")), List(P("param1", "String"))), Some("Nothing"))
    val sig1 = S("foo", Nil, List(List(P("param0", "String"))), Some("Nothing"))
    code"""object O {
             def foo(param0: String)(param1: String): Nothing = ???
             def foo(param0: String): Nothing = ???
             foo($m1)
             foo(""$m2)
             foo("")($m3)
           }""".withSource
      .signatureHelp(m1, List(sig0, sig1), None, 0)
      .signatureHelp(m2, List(sig0, sig1), None, 0)
      .signatureHelp(m3, List(sig0, sig1), Some(1), 1)
  }

  @Test def multipleParameterLists: Unit = {
    val signature =
      S("foo",
        Nil,
        List(
          List(P("param0", "Int"), P("param1", "Int")),
          List(P("param2", "Int")),
          List(P("param3", "Int"), P("param4", "Int"))
        ),
        Some("Int"))
    code"""object O {
             def foo(param0: Int, param1: Int)(param2: Int)(param3: Int, param4: Int): Int = ???
             foo($m1)
             foo(1, $m2)
             foo(1, 2)($m3)
             foo(1, 2)(3)($m4)
             foo(1, 2)(3)(4, $m5)
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
      .signatureHelp(m2, List(signature), Some(0), 1)
      .signatureHelp(m3, List(signature), Some(0), 2)
      .signatureHelp(m4, List(signature), Some(0), 3)
      .signatureHelp(m5, List(signature), Some(0), 4)
  }

  @Test def implicitParams: Unit = {
    val signature =
      S("foo",
        Nil,
        List(
          List(P("param0", "Int"), P("param1", "Int")),
          List(P("param2", "Int", isImplicit = true))
        ),
        Some("Int"))
    code"""object O {
             def foo(param0: Int, param1: Int)(implicit param2: Int): Int = ???
             foo($m1)
             foo(1, $m2)
             foo(1, 2)($m3)
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
      .signatureHelp(m2, List(signature), Some(0), 1)
      .signatureHelp(m3, List(signature), Some(0), 2)
  }

  @Test def typeParameters: Unit = {
    val signature =
      S("foo",
        List("M[X]", "T[Z] <: M[Z]", "U >: T"),
        List(
          List(P("p0", "M[Int]"), P("p1", "T[Int]"), P("p2", "U"))
        ),
        Some("Int"))
    code"""object O {
             def foo[M[X], T[Z] <: M[Z], U >: T](p0: M[Int], p1: T[Int], p2: U): Int = ???
             foo($m1)
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
  }

  @Test def constructorCall: Unit = {
    val signature =
      S("Foo",
        Nil,
        List(
          List(P("x", "Int"), P("y", "String")),
          List(P("z", "String"))
          ),
        None)
    code"""class Foo(x: Int, y: String)(z: String)
           object O {
             new Foo($m1)
             new Foo(0, $m2)
             new Foo(0, "hello")($m3)
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
      .signatureHelp(m2, List(signature), Some(0), 1)
      .signatureHelp(m3, List(signature), Some(0), 2)
  }

  @Test def overloadedConstructorCall: Unit = {
    val sig0 =
      S("Foo",
        Nil,
        List(
          List(P("x", "Int"), P("y", "String")),
          List(P("z", "Int"))
        ),
        None)
    val sig1 =
      S("Foo",
        Nil,
        List(
          List(P("x", "Int"), P("y", "Int"))
        ),
        None)
    code"""class Foo(x: Int, y: String)(z: Int) {
             def this(x: Int, y: Int) = this(x, y.toString)(0)
           }
           object O {
             new Foo($m1)
             new Foo(0, $m2)
             new Foo(0, "")($m3)
             new Foo(0, 0$m4)
           }""".withSource
      .signatureHelp(m1, List(sig0, sig1), None, 0)
      .signatureHelp(m2, List(sig0, sig1), None, 1)
      .signatureHelp(m3, List(sig0, sig1), Some(0), 2)
      .signatureHelp(m4, List(sig0, sig1), Some(1), 1)
  }

  @Test def constructorCallDoc: Unit = {
    val signatures = List(
      S("Foo", Nil, List(List(P("x", "Int", Some("An int")), P("y", "String", Some("A string")))), None, Some("A Foo")),
      S("Foo", Nil, List(List(P("z", "Boolean", Some("A boolean")), P("foo", "Foo", Some("A Foo")))), None, Some("An alternative constructor for Foo"))
    )

    code"""/**
            * A Foo
            *
            * @param x An int
            * @param y A string
            */
           class Foo(x: Int, y: String) {
             /**
              * An alternative constructor for Foo
              *
              * @param z   A boolean
              * @param foo A Foo
              */
             def this(z: Boolean, foo: Foo) = this(0, "")
           }
           object O {
             new Foo($m1)
             new Foo(0$m2)
             new Foo(true$m3)
             new Foo(0, $m4)
             new Foo(0, ""$m5)
             new Foo(true, $m6)
             new Foo(true, ???$m7)
           }""".withSource
      .signatureHelp(m1, signatures, None, 0)
      .signatureHelp(m2, signatures, Some(0), 0)
      .signatureHelp(m3, signatures, Some(1), 0)
      .signatureHelp(m4, signatures, Some(0), 1)
      .signatureHelp(m5, signatures, Some(0), 1)
      .signatureHelp(m6, signatures, Some(1), 1)
      .signatureHelp(m7, signatures, Some(1), 1)
  }

  @Test def classTypeParameters: Unit = {
    val signature =
      S("Foo",
        List("M[X]", "T[Z] <: M[Z]", "U"),
        List(
          List(P("p0", "M[Int]"), P("p1", "T[Int]"), P("p2", "U")),
          List(P("p3", "Int"))
        ),
        None)
    code"""class Foo[M[X], T[Z] <: M[Z], U](p0: M[Int], p1: T[Int], p2: U)(p3: Int)
           object O {
             new Foo($m1)
             new Foo(???, $m2)
             new Foo(???, ???, $m3)
             new Foo(???, ???, ???)($m4)
           }""".withSource
      .signatureHelp(m1, List(signature), Some(0), 0)
      .signatureHelp(m2, List(signature), Some(0), 1)
      .signatureHelp(m3, List(signature), Some(0), 2)
      .signatureHelp(m4, List(signature), Some(0), 3)
  }

  @Test def showDoc: Unit = {
    code"""object O {
             /** Hello, world! */ def foo(param0: Int): Int = 0
             foo($m1)
           }""".withSource
      .signatureHelp(m1, List(S("foo", Nil, List(List(P("param0", "Int"))), Some("Int"), Some("Hello, world!"))), None, 0)
  }

  @Test def showParamDoc: Unit = {
    code"""object O {
          |  /**
          |   * Buzzes a fizz up to bar
          |   *
          |   * @param fizz The fizz to buzz
          |   * @param bar  Buzzing limit
          |   * @return The fizz after being buzzed up to bar
          |   */
          |  def buzz(fizz: Int, bar: Int): Int = ???
          |  buzz($m1)
          |}""".withSource
      .signatureHelp(m1, List(
        S("buzz", Nil, List(List(
          P("fizz", "Int", Some("The fizz to buzz")),
          P("bar", "Int", Some("Buzzing limit"))
          )), Some("Int"), Some("Buzzes a fizz up to bar"))
        ), None, 0)
  }
}
