---
layout: doc-page
title: Inline
---

## Inline Definitions

`inline` is a new [soft modifier](../soft-modifier.md) that guarantees that a
definition will be inlined at the point of use. Example:

```scala
object Config {
  inline val logging = false
}

object Logger {

  private var indent = 0

  inline def log[T](msg: String, indentMargin: =>Int)(op: => T): T =
    if (Config.logging) {
      println(s"${"  " * indent}start $msg")
      indent += indentMargin
      val result = op
      indent -= indentMargin
      println(s"${"  " * indent}$msg = $result")
      result
    }
    else op
}
```

The `Config` object contains a definition of the **inline value** `logging`.
This means that `logging` is treated as a _constant value_, equivalent to its
right-hand side `false`. The right-hand side of such an `inline val` must itself
be a [constant expression](https://scala-lang.org/files/archive/spec/2.13/06-expressions.html#constant-expressions).
Used in this way, `inline` is equivalent to Java and Scala 2's `final`. Note that `final`, meaning
_inlined constant_, is still supported in Dotty, but will be phased out.

The `Logger` object contains a definition of the **inline method** `log`. This
method will always be inlined at the point of call.

In the inlined code, an `if-then-else` with a constant condition will be rewritten
to its `then`- or `else`-part. Consequently, in the `log` method above the
`if (Config.logging)` with `Config.logging == true` will get rewritten into its
`then`-part.

Here's an example:

```scala
var indentSetting = 2

def factorial(n: BigInt): BigInt = {
  log(s"factorial($n)", indentSetting) {
    if (n == 0) 1
    else n * factorial(n - 1)
  }
}
```

If `Config.logging == false`, this will be rewritten (simplified) to:

```scala
def factorial(n: BigInt): BigInt = {
  if (n == 0) 1
  else n * factorial(n - 1)
}
```

As you notice, since neither `msg` or `indentMargin` were used, they do not
appear in the generated code for `factorial`. Also note the body of our `log`
method: the `else-` part reduces to just an `op`. In the generated code we do
not generate any closures because we only refer to a by-name parameter *once*.
Consequently, the code was inlined directly and the call was beta-reduced.

In the `true` case the code will be rewritten to:

```scala
def factorial(n: BigInt): BigInt = {
  val msg = s"factorial($n)"
  println(s"${"  " * indent}start $msg")
  Logger.inline$indent_=(indent.+(indentSetting))
  val result =
    if (n == 0) 1
    else n * factorial(n - 1)
  Logger.inline$indent_=(indent.-(indentSetting))
  println(s"${"  " * indent}$msg = $result")
  result
}
```

Note, that the by-value parameter `msg` is evaluated only once, per the usual Scala
semantics, by binding the value and reusing the `msg` through the body of
`factorial`. Also, note the special handling of the assignment to the private var
`indent`. It is achieved by generating a setter method `def inline$indent_=` and calling it instead.

### Recursive Inline Methods

Inline methods can be recursive. For instance, when called with a constant
exponent `n`, the following method for `power` will be implemented by
straight inline code without any loop or recursion.

```scala
inline def power(x: Double, n: Int): Double = {
  if (n == 0) 1.0
  else if (n == 1) x
  else {
    val y = power(x, n / 2)
    if (n % 2 == 0) y * y else y * y * x
  }
}

power(expr, 10)
// translates to
//
//    val x = expr
//    val y1 = x * x   // ^2
//    val y2 = y1 * y1 // ^4
//    val y3 = y2 * x  // ^5
//    y3 * y3          // ^10
```

Parameters of inline methods can have an `inline` modifier as well. This means
that actual arguments to these parameters will be inlined in the body of the
`inline def`. `inline` parameters have call semantics equivalent to by-name parameters
but allow for duplication of the code in the argument. It is usually useful when constant
values need to be propagated to allow further optimizations/reductions.

The following example shows the difference in translation between by-value, by-name and `inline`
parameters:

```scala
inline def funkyAssertEquals(actual: Double, expected: =>Double, inline delta: Double): Unit =
  if (actual - expected).abs > delta then
    throw new AssertionError(s"difference between ${expected} and ${actual} was larger than ${delta}")

funkyAssertEquals(computeActual(), computeExpected(), computeDelta())
// translates to
//
//    val actual = computeActual()
//    def expected = computeExpected()
//    if (actual - expected).abs > computeDelta() then
//      throw new AssertionError(s"difference between ${expected} and ${actual} was larger than ${computeDelta()}")
```
### Rules for Overriding

Inline methods can override other non-inline methods. The rules are as follows:

1. If an inline method `f` implements or overrides another, non-inline method, the inline method can also be invoked at runtime. For instance, consider the scenario:
```scala
abstract class A {
  def f(): Int
  def g(): Int = f()
}
class B extends A {
  inline def f() = 22
  override inline def g() = f() + 11
}
val b = B()
val a: A = b
// inlined invocatons
assert(b.f() == 22)
assert(b.g() == 33)
// dynamic invocations
assert(a.f() == 22)
assert(a.g() == 33)
```
The inlined invocations and the dynamically dispatched invocations give the same results.

2. Inline methods are effectively final.

3. Inline methods can also be abstract. An abstract inline method can be implemented only by other inline methods. It cannot be invoked directly:
```scala
abstract class A {
  inline def f(): Int
}
object B extends A {
  inline def f(): Int = 22
}
B.f()         // OK
val a: A = B
a.f()         // error: cannot inline f() in A.
```

### Relationship to @inline

Scala also defines a `@inline` annotation which is used as a hint
for the backend to inline. The `inline` modifier is a more powerful
option: Expansion is guaranteed instead of best effort,
it happens in the frontend instead of in the backend, and it also applies
to recursive methods.

To cross compile between both Dotty and Scalac, we introduce a new `@forceInline`
annotation which is equivalent to the new `inline` modifier. Note that
Scala 2 ignores the `@forceInline` annotation, so one must use both
annotations to guarantee inlining for Dotty and at the same time hint inlining
for Scala 2 (i.e. `@forceInline @inline`).

<!--- (Commented out since the docs and implementation differ)

### Evaluation Rules

As you noticed by the examples above a lambda of the form

`((x_1, ..., x_n) => B)(E_1, ..., E_n)` is rewritten to:

```
{ val/def x_1 = E_1
  ...
  val/def x_n = E_n
  B
}
```

where vals are used for value parameter bindings and defs are used for by-name
parameter bindings. If an argument `E_i` is a simple variable reference `y`, the
corresponding binding is omitted and `y` is used instead of `x_i` in `B`.

If a `inline` modifier is given for parameters, corresponding arguments must be
pure expressions of constant type.
-->

#### The definition of constant expression

Right-hand sides of inline values and of arguments for inline parameters must be
constant expressions in the sense defined by the [SLS §
6.24](https://www.scala-lang.org/files/archive/spec/2.12/06-expressions.html#constant-expressions),
including _platform-specific_ extensions such as constant folding of pure
numeric computations.

## Transparent Inline Methods

Inline methods can additionally be declared `transparent`.
This means that the return type of the inline method can be
specialized to a more precise type upon expansion. Example:

```scala
class A
class B extends A {
  def m() = true
}

transparent inline def choose(b: Boolean): A =
  if b then A() else B

val obj1 = choose(true)  // static type is A
val obj2 = choose(false) // static type is B

// obj1.m() // compile-time error: `m` is not defined on `A`
obj2.m()    // OK
```
Here, the inline method `choose` returns an object of either of the two types `A` and `B`. If `choose` had been declared with a normal return type `: A`, the result
of its expansion would always be of type `A`, even though the computed value might be of the subtype `B`. The inline method is a "blackbox" in the sense that details of its implementation do not leak out. But if a `transparent` modifier is given,
the expansion is the type of the expanded body. If the argument `b`
is `true`, that type is `A`, otherwise it is `B`. Consequently, calling `meth` on `obj2`
type-checks since `obj2` has the same type as the expansion of `choose(false)`, which is `B`.
Transparent inline methods are "whitebox" in the sense that the type
of an application of such a method can be more specialized than its declared
return type, depending on how the method expands.

In the following example, we see how the return type of `zero` is specialized to
the singleton type `0` permitting the addition to be ascribed with the correct
type `1`.

```scala
transparent inline def zero(): Int = 0

val one: 1 = zero() + 1
```

## Inline Conditionals

If the condition of an if-then-else expressions is a constant expression then it simplifies to
the selected branch. Prefixing an if-then-else expression with `inline` enforces that
the condition has to be a constant expression, and thus guarantees that the conditional will always
simplify.

Example:

```scala
inline def update(delta: Int) =
  inline if (delta >= 0) increaseBy(delta)
  else decreaseBy(-delta)
```
A call `update(22)` would rewrite to `increaseBy(22)`. But if `update` was called with
a value that was not a compile-time constant, we would get a compile time error like the one
below:

```scala
   |  inline if (delta >= 0) ???
   |  ^
   |  cannot reduce inline if
   |   its condition
   |     delta >= 0
   |   is not a constant value
   | This location is in code that was inlined at ...
```

## Inline Matches

A `match` expression in the body of an `inline` method definition may be
prefixed by the `inline` modifier. If there is enough static information to
unambiguously take a branch, the expression is reduced to that branch and the
type of the result is taken. If not, a compile-time error is raised that
reports that the match cannot be reduced.

The example below defines an inline method with a
single inline match expression that picks a case based on its static type:

```scala
transparent inline def g(x: Any): Any = inline x match {
  case x: String => (x, x) // Tuple2[String, String](x, x)
  case x: Double => x
}

g(1.0d) // Has type 1.0d which is a subtype of Double
g("test") // Has type (String, String)
```

The scrutinee `x` is examined statically and the inline match is reduced
accordingly returning the corresponding value (with the type specialized because `g` is declared `transparent`). This example performs a simple type test over the
scrutinee. The type can have a richer structure like the simple ADT below.
`toInt` matches the structure of a number in [Church-encoding](https://en.wikipedia.org/wiki/Church_encoding)
and _computes_ the corresponding integer.

```scala
trait Nat
case object Zero extends Nat
case class Succ[N <: Nat](n: N) extends Nat

transparent inline def toInt(n: Nat): Int = inline n match {
  case Zero => 0
  case Succ(n1) => toInt(n1) + 1
}

final val natTwo = toInt(Succ(Succ(Zero)))
val intTwo: 2 = natTwo
```

`natTwo` is inferred to have the singleton type 2.

## The `scala.compiletime` Package

The `scala.compiletime` package contains helper definitions that provide support for compile time operations over values. They are described in the following.

### `constValue`, `constValueOpt`, and the `S` combinator

`constValue` is a function that produces the constant value represented by a
type.

```scala
import scala.compiletime.{constValue, S}

transparent inline def toIntC[N]: Int =
  inline constValue[N] match {
    case 0 => 0
    case _: S[n1] => 1 + toIntC[n1]
  }

final val ctwo = toIntC[2]
```

`constValueOpt` is the same as `constValue`, however returning an `Option[T]`
enabling us to handle situations where a value is not present. Note that `S` is
the type of the successor of some singleton type. For example the type `S[1]` is
the singleton type `2`.

### `erasedValue`

So far we have seen inline methods that take terms (tuples and integers) as
parameters. What if we want to base case distinctions on types instead? For
instance, one would like to be able to write a function `defaultValue`, that,
given a type `T`, returns optionally the default value of `T`, if it exists.
We can already express this using rewrite match expressions and a simple
helper function, `scala.compiletime.erasedValue`, which is defined as follows:

```scala
erased def erasedValue[T]: T = ???
```

The `erasedValue` function _pretends_ to return a value of its type argument
`T`. In fact, it would always raise a `NotImplementedError` exception when
called. But the function can in fact never be called, since it is declared
`erased`, so can only be used at compile-time during type checking.

Using `erasedValue`, we can then define `defaultValue` as follows:

```scala
import scala.compiletime.erasedValue

inline def defaultValue[T] = inline erasedValue[T] match {
  case _: Byte => Some(0: Byte)
  case _: Char => Some(0: Char)
  case _: Short => Some(0: Short)
  case _: Int => Some(0)
  case _: Long => Some(0L)
  case _: Float => Some(0.0f)
  case _: Double => Some(0.0d)
  case _: Boolean => Some(false)
  case _: Unit => Some(())
  case _ => None
}
```

Then:
```scala
  val dInt: Some[Int] = defaultValue[Int]
  val dDouble: Some[Double] = defaultValue[Double]
  val dBoolean: Some[Boolean] = defaultValue[Boolean]
  val dAny: None.type = defaultValue[Any]
```

As another example, consider the type-level version of `toInt` below:
given a _type_ representing a Peano number,
return the integer _value_ corresponding to it.
Consider the definitions of numbers as in the _Inline
Match_ section above. Here is how `toIntT` can be defined:

```scala
transparent inline def toIntT[N <: Nat]: Int =
  inline scala.compiletime.erasedValue[N] match {
    case _: Zero.type => 0
    case _: Succ[n] => toIntT[n] + 1
  }

final val two = toIntT[Succ[Succ[Zero.type]]]
```

`erasedValue` is an `erased` method so it cannot be used and has no runtime
behavior. Since `toIntT` performs static checks over the static type of `N` we
can safely use it to scrutinize its return type (`S[S[Z]]` in this case).

### `error`

The `error` method is used to produce user-defined compile errors during inline expansion.
It has the following signature:

```scala
inline def error(inline msg: String): Nothing
```

If an inline expansion results in a call `error(msgStr)` the compiler
produces an error message containing the given `msgStr`.

```scala
import scala.compiletime.{error, code}

inline def fail() = {
  error("failed for a reason")
}
fail() // error: failed for a reason
```

or

```scala
inline def fail(p1: => Any) = {
  error(code"failed on: $p1")
}
fail(identity("foo")) // error: failed on: identity("foo")
```

### The `scala.compiletime.ops` package

The `scala.compiletime.ops` package contains types that provide support for
primitive operations on singleton types. For example,
`scala.compiletime.ops.int.*` provides support for multiplying two singleton
`Int` types, and `scala.compiletime.ops.boolean.&&` for the conjunction of two
`Boolean` types. When all arguments to a type in `scala.compiletime.ops` are
singleton types, the compiler can evaluate the result of the operation.

```scala
import scala.compiletime.ops.int._
import scala.compiletime.ops.boolean._

val conjunction: true && true = true
val multiplication: 3 * 5 = 15
```

Many of these singleton operation types are meant to be used infix (as in [SLS §
3.2.8](https://www.scala-lang.org/files/archive/spec/2.12/03-types.html#infix-types)),
and are annotated with [`@infix`](scala.annotation.infix) accordingly.

Since type aliases have the same precedence rules as their term-level
equivalents, the operations compose with the expected precedence rules:

```scala
import scala.compiletime.ops.int._
val x: 1 + 2 * 3 = 7
```

The operation types are located in packages named after the type of the
left-hand side parameter: for instance, `scala.compiletime.ops.int.+` represents
addition of two numbers, while `scala.compiletime.ops.string.+` represents string
concatenation. To use both and distinguish the two types from each other, a
match type can dispatch to the correct implementation:

```scala
import scala.compiletime.ops._
import scala.annotation.infix

@infix type +[X <: Int | String, Y <: Int | String] = (X, Y) match {
  case (Int, Int) => int.+[X, Y]
  case (String, String) => string.+[X, Y]
}

val concat: "a" + "b" = "ab"
val addition: 1 + 1 = 2
```

## Summoning Implicits Selectively

It is foreseen that many areas of typelevel programming can be done with rewrite
methods instead of implicits. But sometimes implicits are unavoidable. The
problem so far was that the Prolog-like programming style of implicit search
becomes viral: Once some construct depends on implicit search it has to be
written as a logic program itself. Consider for instance the problem of creating
a `TreeSet[T]` or a `HashSet[T]` depending on whether `T` has an `Ordering` or
not. We can create a set of implicit definitions like this:

```scala
trait SetFor[T, S <: Set[T]]
class LowPriority {
  implicit def hashSetFor[T]: SetFor[T, HashSet[T]] = ...
}
object SetsFor extends LowPriority {
  implicit def treeSetFor[T: Ordering]: SetFor[T, TreeSet[T]] = ...
}
```

Clearly, this is not pretty. Besides all the usual indirection of implicit
search, we face the problem of rule prioritization where we have to ensure that
`treeSetFor` takes priority over `hashSetFor` if the element type has an
ordering. This is solved (clumsily) by putting `hashSetFor` in a superclass
`LowPriority` of the object `SetsFor` where `treeSetFor` is defined. Maybe the
boilerplate would still be acceptable if the crufty code could be contained.
However, this is not the case. Every user of the abstraction has to be
parameterized itself with a `SetFor` implicit. Considering the simple task _"I
want a `TreeSet[T]` if `T` has an ordering and a `HashSet[T]` otherwise"_, this
seems like a lot of ceremony.

There are some proposals to improve the situation in specific areas, for
instance by allowing more elaborate schemes to specify priorities. But they all
keep the viral nature of implicit search programs based on logic programming.

By contrast, the new `summonFrom` construct makes implicit search available
in a functional context. To solve the problem of creating the right set, one
would use it as follows:
```scala
import scala.compiletime.summonFrom

inline def setFor[T]: Set[T] = summonFrom {
  case given ord: Ordering[T] => new TreeSet[T]
  case _                      => new HashSet[T]
}
```
A `summonFrom` call takes a pattern matching closure as argument. All patterns
in the closure are type ascriptions of the form `identifier : Type`.

Patterns are tried in sequence. The first case with a pattern `x: T` such that
an implicit value of type `T` can be summoned is chosen. If the pattern is prefixed
with `given`, the variable `x` is bound to the implicit value for the remainder of the case. It can in turn be used as an implicit in the right hand side of the case. It is an error if one of the tested patterns gives rise to an ambiguous implicit search.

`summonFrom` applications must be reduced at compile time.

Consequently, if we summon an `Ordering[String]` the code above will return a
new instance of `TreeSet[String]`.

```scala
summon[Ordering[String]]

println(setFor[String].getClass) // prints class scala.collection.immutable.TreeSet
```

**Note** `summonFrom` applications can raise ambiguity errors. Consider the following
code with two implicit values in scope of type `A`. The pattern match in `f` will raise
an ambiguity error of `f` is applied.

```scala
class A
implicit val a1: A = new A
implicit val a2: A = new A

inline def f: Any = summonFrom {
  case given _: A => ???  // error: ambiguous implicits
}
```

## `summonInline`

The shorthand `summonInline` provides a simple way to write a `summon` that is delayed until the call is inlined.
```scala
transparent inline def summonInline[T]: T = summonFrom {
  case t: T => t
}
```

### Reference

For more info, see [PR #4768](https://github.com/lampepfl/dotty/pull/4768),
which explains how `summonFrom`'s predecessor (implicit matches) can be used for typelevel programming and code specialization and [PR #7201](https://github.com/lampepfl/dotty/pull/7201) which explains the new `summonFrom` syntax.
