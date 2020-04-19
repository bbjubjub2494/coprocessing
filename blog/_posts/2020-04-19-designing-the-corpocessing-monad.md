---
layout: blog-page
title: Designing the Coprocessing Monad
author: Julie Bettens
date: 2020-04-19
---

In traditional functional programming,
monads are pervasive.
This isn't without reason:
they are a powerful design pattern
that can be used in pure programs.
They are materialized by types, so
it makes sense that
even before we start programming,
we would design our types and our monads.
In this post, I will expose what monads I picked for the coprocessing API and why.

Note that this post assumes at least a passing familiarity with the concept of monad.

# I/O monads
In Processing, we can interact with the outside world a lot.
From a data flow perspective,
we receive input while the program is running
— keypresses, image data —,
and we can output data as well
— draw on the screen, write files —
at any point in the program.
These processes are modeled with an I/O monad in which all I/O operations can be wrapped.
This makes it possible to reason about when they are executed in relation to each other,
materialize them in the type system,
and much more.

Concretely, there are multiple implementations of the I/O monad
already existing in the Scala ecosystem.
It is known that they are [all equally expressive][only-one-io],
— meaning that any program that can be written in one can be converted to another —
and that they only differ in terms of performance.

These monads also have great support for asynchronous programming,
but for the time being, we are bound to the threading model of Processing 3
so we will not be able to use them.

[only-one-io]: https://degoes.net/articles/only-one-io

# Reader monads
A [reader monad] can be used to implement dependency injection.
It can be implemented as a function of one argument and the appropriate composition operators.
We have a need for dependency injection to pass the `PApplet` instance around in a sketch.

In Scala specifically, the reader monad has a competitor in the form of [term inference][using-clauses],
and which to use depends on the situation.
Thus, we will need to decide which one to use in Coprocessing.

[reader monad]: https://github.com/lemastero/scala_typeclassopedia#reader
[using-clauses]: https://dotty.epfl.ch/docs/reference/contextual/using-clauses.html

# The RIO monad
In a very valuable [blog post][the-rio-monad], Michael Snoyman makes the case that
a monad that combines both I/O and reader capabilities was powerful enough to
be *the* monad in complex I/O applications.
He works in Haskell so some of the details are different,
but the argument still translates well in our situation.
Using this argument,
We can conclude that a variant of the RIO monad is all we need in Coprocessing.
The question is now whether to use term inference or the proper reader monad.

Because I intend to inject dependencies narrowly to make the API more robust,
term inference is the preferable solution:
we can inject any combination of dependencies to an action
without needing to combine all of them in a big "environment" datatype.

[the-rio-monad]: https://www.fpcomplete.com/blog/2017/07/the-rio-monad

# Implementation
Firstly, our I/O monad will simply be
the function of no arguments, or the *thunk* as it is called.
There are more advanced I/O monads out there,
but this one has a more approachable syntax for the non-FP connoisseur,
and it can be made a lawful `cats.effect.SyncEffect` instance, forgoing only stack safety.
I will demonstrate so at the end of this post.

For injection, we abstract all Processing methods in their own injectable unit
so we can have a fine control of when we inject them to user code.
This also has the benefit of fully abstracting over Processing itself:
there is no compile-time dependency from a Sketch to the Processing core library.

As an example, the [size()][processing:size] function,
is materialized using the following type:
```scala
trait SizeOps {
  def size(width: Int, height: Int): Unit
  def size(width: Int, height: Int, renderer: PRenderer): Unit
}

// syntaxic sugar
def size(width: Int, height: Int)(using SizeOps): Unit
def size(width: Int, height: Int, renderer: PRenderer)(using SizeOps): Unit
```
In the back-end, when we have access to the PApplet instance,
so instanciating `SizeOps` is straightforward.

What happened to the thunk monad?
Well, since it boils down to an extra pair of parenthesis at the end of the argument list,
I decided to omit it,
But we know it's secretly there.
We do still honor the convention that side-effecting methods bear a pair of empty parentheses.

We know that it's better to call size() from the [settings()][processing:settings] hook on PApplet, so we only inject it there.
Conversely, most Processing functions shouldn't be injected in settings(), as the Processing documentation clearly states.
The whole [Sketch](processing.Sketch) API is built following this pattern.
Clients can extend it to define a pure sketch that can be interpreted by any Processing backend.

[processing:size]: https://processing.org/reference/size_.html
[processing:settings]: https://processing.org/reference/settings_.html

# Attachment

This [Ammonite] script implements the `SyncEffect` typeclass for the thunk monad and runs the Discipline test suite on it.
You should see that all properties pass except "stack-safe on repeated attempts".

[Ammonite]: https://ammonite.io

```scala
import $ivy.`org.typelevel::cats-effect-laws:2.1.2`
import $ivy.`io.monix::minitest-laws:2.7.0`

import scala.util.Try
import cats.Eq
import cats.instances.all._
import cats.kernel.laws.IsEqArrow
import cats.kernel.laws.discipline.catsLawsIsEqToProp
import cats.effect._
import cats.effect.laws.discipline._
import minitest.laws.Checkers
import org.typelevel.discipline.Laws

// The Coprocessing base monad
type P[A] = () => A

// shadow unwanted cats.instances
val catsKernelOrderForFunction0 = ()
val catsKernelPartialOrderForFunction0 = ()
val catsKernelHashForFunction0 = ()
val catsKernelEqForFunction0 = ()
val catsStdBimonadForFunction0 = ()
val function0Distributive = ()

implicit val SyncEffectP = new SyncEffect[P] {
  def pure[A](v: A) = () => v
  def flatMap[A, B](fa: P[A])(f: A => P[B]) = suspend(f(fa()))
  def tailRecM[A, B](a: A)(f: A => P[Either[A, B]]): P[B] = () => {
    @annotation.tailrec
    def rec(a: A): B = f(a)() match {
      case Left(a) => rec(a)
      case Right(b) => b
    }
    rec(a)
  }

  def handleErrorWith[A](fa: P[A])(f: Throwable => P[A]) =
    () => Try(fa()).recover({ case e => f(e)() }).get
  def raiseError[A](e: Throwable) = () => throw e

  def bracketCase[A, B](acquire: P[A])(use: A => P[B])(release: (A, ExitCase[Throwable]) => P[Unit]) = () => {
    val r = acquire()
    val t = Try(use(r)())
    release(r, t.fold(ExitCase.error, _ => ExitCase.complete))()
    t.get
  }
  def suspend[A](thunk: => P[A]) = () => thunk()
  def runSync[G[_], A](fa: P[A])(implicit G: Sync[G]): G[A] = G.delay(fa())
}

// The tests need to be able to compare P instances
implicit val EqThrowable = Eq.allEqual[Throwable]  // all failures are equal
implicit def EqP[A: Eq] = Eq.by[P[A], Try[A]](f => Try(f()))

object PSuite extends minitest.SimpleTestSuite with Checkers {
  def checkAll(name: String, ruleSet: Laws#RuleSet) =
    for ((id, prop) <- ruleSet.all.properties)
      test(s"$name.$id") {
        check(prop)
      }

  checkAll("SyncEffectLaws", SyncEffectTests[P].syncEffect[Int,Int,Int])
}

{
  implicit def ec = scala.concurrent.ExecutionContext.global
  val p = PSuite.properties
  p.setupSuite()
  for {
    prop <- p
    res <- prop()
  }
    print(res.formatted(prop.name, true))
  p.tearDownSuite()
}
```
