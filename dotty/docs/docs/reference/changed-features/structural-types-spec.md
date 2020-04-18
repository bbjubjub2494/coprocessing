---
layout: doc-page
title: "Programmatic Structural Types - More Details"
---

## Syntax

```
SimpleType    ::= ... | Refinement
Refinement    ::= ‘{’ RefineStatSeq ‘}’
RefineStatSeq ::=  RefineStat {semi RefineStat}
RefineStat    ::= ‘val’ VarDcl | ‘def’ DefDcl | ‘type’ {nl} TypeDcl
```

## Implementation of structural types

The standard library defines a trait `Selectable` in the package
`scala`, defined as follows:

```scala
trait Selectable extends Any {
  def selectDynamic(name: String): Any
  def applyDynamic(name: String, paramClasses: ClassTag[_]*)(args: Any*): Any =
    new UnsupportedOperationException("applyDynamic")
}
```

An implementation of `Selectable` that relies on Java reflection is
available in the standard library: `scala.reflect.Selectable`. Other
implementations can be envisioned for platforms where Java reflection
is not available.

`selectDynamic` takes a field name and returns the value associated
with that name in the `Selectable`. Similarly, `applyDynamic`
takes a method name, `ClassTag`s representing its parameters types and
the arguments to pass to the function. It will return the result of
calling this function with the given arguments.

Given a value `v` of type `C { Rs }`, where `C` is a class reference
and `Rs` are refinement declarations, and given `v.a` of type `U`, we
consider three distinct cases:

- If `U` is a value type, we map `v.a` to the equivalent of:
  ```scala
  v.a
     --->
  (v: Selectable).selectDynamic("a").asInstanceOf[U]
  ```

- If `U` is a method type `(T11, ..., T1n)...(TN1, ..., TNn) => R` and it is not
a dependent method type, we map `v.a(a11, ..., a1n)...(aN1, aNn)` to
  the  equivalent of:
  ```scala
  v.a(arg1, ..., argn)
     --->
  (v: Selectable).applyDynamic("a", CT11, ..., CTn, ..., CTN1, ... CTNn)
                              (a11, ..., a1n, ..., aN1, ..., aNn)
                 .asInstanceOf[R]
  ```

- If `U` is neither a value nor a method type, or a dependent method
  type, an error is emitted.

We make sure that `r` conforms to type `Selectable`, potentially by
introducing an implicit conversion, and then call either
`selectDynamic` or `applyDynamic`, passing the name of the
member to access, along with the class tags of the formal parameters
and the arguments in the case of a method call. These parameters
could be used to disambiguate one of several overload variants in the
future, but overloads are not supported in structural types at the
moment.

## Limitations of structural types

- Dependent methods cannot be called via structural call.
- Overloaded methods cannot be called via structural call.
- Refinements do not handle polymorphic methods.

## Differences with Scala 2 structural types

- Scala 2 supports structural types by means of Java reflection. Unlike
  Scala 3, structural calls do not rely on a mechanism such as
  `Selectable`, and reflection cannot be avoided.
- In Scala 2, structural calls to overloaded methods are possible.
- In Scala 2, mutable `var`s are allowed in refinements. In Scala 3,
  they are no longer allowed.

## Migration

Receivers of structural calls need to be instances of `Selectable`. A
conversion from `Any` to `Selectable` is available in the standard
library, in `scala.reflect.Selectable.reflectiveSelectable`. This is
similar to the implementation of structural types in Scala 2.

## Reference

For more info, see [Rethink Structural
Types](https://github.com/lampepfl/dotty/issues/1886).
