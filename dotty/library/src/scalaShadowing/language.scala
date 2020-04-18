/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2015, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */
package scalaShadowing

/**
 *  The `scala.language` object controls the language features available to the programmer, as proposed in the
 *  [[https://docs.google.com/document/d/1nlkvpoIRkx7at1qJEZafJwthZ3GeIklTFhqmXMvTX9Q/edit '''SIP-18 document''']].
 *
 *  Each of these features has to be explicitly imported into the current scope to become available:
 *  {{{
 *     import language.postfixOps // or language._
 *     List(1, 2, 3) reverse
 *  }}}
 *
 *  The language features are:
 *   - [[dynamics            `dynamics`]]            enables defining calls rewriting using the [[scala.Dynamic `Dynamic`]] trait
 *   - [[postfixOps          `postfixOps`]]          enables postfix operators
 *   - [[reflectiveCalls     `reflectiveCalls`]]     enables using structural types
 *   - [[implicitConversions `implicitConversions`]] enables defining implicit methods and members
 *   - [[higherKinds         `higherKinds`]]         enables writing higher-kinded types
 *   - [[existentials        `existentials`]]        enables writing existential types
 *   - [[experimental        `experimental`]]        contains newer features that have not yet been tested in production
 *
 *  and, for dotty:
 *
 *   - [[Scala2              `Scala2`]]              backwards compatibility mode for Scala2
 *   - [[noAutoTupling       `noAutoTupling`]]       disable auto-tupling
 *   - [[strictEquality      `strictEquality`]]      enable strick equality
 *
 *  @groupname production   Language Features
 *  @groupname experimental Experimental Language Features
 *  @groupprio experimental 10
 *
 *  Dotty-specific features come at the end.
 *
 *  Note: Due to the more restricted language import mechanism in dotty (only
 *  imports count, implicits are disregarded) we don't need the constructions
 *  of the inherited language features. A simple object for each feature is
 *  sufficient.
 */
object language {

  import languageFeature._

  /** Where enabled, direct or indirect subclasses of trait scala.Dynamic can
   *  be defined. Unless dynamics is enabled, a definition of a class, trait,
   *  or object that has Dynamic as a base trait is rejected. Dynamic member
   *  selection of existing subclasses of trait Dynamic are unaffected;
   *  they can be used anywhere.
   *
   *  '''Why introduce the feature?''' To enable flexible DSLs and convenient interfacing
   *  with dynamic languages.
   *
   *  '''Why control it?''' Dynamic member selection can undermine static checkability
   *  of programs. Furthermore, dynamic member selection often relies on reflection,
   *  which is not available on all platforms.
   *
   *  @group production
   */
  implicit lazy val dynamics: dynamics = languageFeature.dynamics

  /** Only where enabled, postfix operator notation `(expr op)` will be allowed.
   *
   *  '''Why keep the feature?''' Several DSLs written in Scala need the notation.
   *
   *  '''Why control it?''' Postfix operators interact poorly with semicolon inference.
   *   Most programmers avoid them for this reason.
   *
   *  @group production
   */
  implicit lazy val postfixOps: postfixOps = languageFeature.postfixOps

  /** Only where enabled, accesses to members of structural types that need
   *  reflection are supported. Reminder: A structural type is a type of the form
   *  `Parents { Decls }` where `Decls` contains declarations of new members that do
   *  not override any member in `Parents`. To access one of these members, a
   *  reflective call is needed.
   *
   *  '''Why keep the feature?''' Structural types provide great flexibility because
   *  they avoid the need to define inheritance hierarchies a priori. Besides,
   *  their definition falls out quite naturally from Scala’s concept of type refinement.
   *
   *  '''Why control it?''' Reflection is not available on all platforms. Popular tools
   *  such as ProGuard have problems dealing with it. Even where reflection is available,
   *  reflective dispatch can lead to surprising performance degradations.
   *
   *  @group production
   */
  implicit lazy val reflectiveCalls: reflectiveCalls = languageFeature.reflectiveCalls

  /** Only where enabled, definitions of legacy implicit conversions and certain uses
   *  of implicit conversions are allowed.
   *
   *  A legacy implicit conversion is an implicit value of unary function type `A => B`,
   *  or an implicit method that has in its first parameter section a single,
   *  non-implicit parameter. Examples:
   *
   *  {{{
   *     implicit def stringToInt(s: String): Int = s.length
   *     implicit val conv = (s: String) => s.length
   *     implicit def listToX(xs: List[T])(implicit f: T => X): X = ...
   *  }}}
   *
   *  Implicit values of other types are not affected, and neither are implicit
   *  classes. In particular, given instances of the scala.Conversion class can be
   *  defined without having to import the language feature.
   *
   *  The language import is also required to enable _uses_ of implicit conversions
   *  unless the conversion in question is co-defined with the type to which it maps.
   *  Co-defined means: defined in the companion object of the class of the result type.
   *  Examples:
   *
   *  {{{
   *      class A
   *      class B
   *      object B {
   *        given a2b as Conversion[A, B] { ... }
   *      }
   *      object C {
   *        given b2a as Conversion[B, A] { ... }
   *      }
   *      import given B._
   *      import given C._
   *      val x: A = new B     // language import required
   *      val x: B = new A     // no import necessary since a2b is co-defined with B
   *  }}}
   *
   *  '''Why keep the feature?''' Implicit conversions are central to many aspects
   *  of Scala’s core libraries.
   *
   *  '''Why control it?''' Implicit conversions are known to cause many pitfalls
   *  if over-used. This holds in particular for implicit conversions defined after
   *  the fact between unrelated types.
   *
   *  @group production
   */
  implicit lazy val implicitConversions: implicitConversions = languageFeature.implicitConversions

  /** Only where this flag is enabled, higher-kinded types can be written.
   *
   *  '''Why keep the feature?''' Higher-kinded types enable the definition of very general
   *  abstractions such as functor, monad, or arrow. A significant set of advanced
   *  libraries relies on them. Higher-kinded types are also at the core of the
   *  scala-virtualized effort to produce high-performance parallel DSLs through staging.
   *
   *  '''Why control it?''' Higher kinded types in Scala lead to a Turing-complete
   *  type system, where compiler termination is no longer guaranteed. They tend
   *  to be useful mostly for type-level computation and for highly generic design
   *  patterns. The level of abstraction implied by these design patterns is often
   *  a barrier to understanding for newcomers to a Scala codebase. Some syntactic
   *  aspects of higher-kinded types are hard to understand for the uninitiated and
   *  type inference is less effective for them than for normal types. Because we are
   *  not completely happy with them yet, it is possible that some aspects of
   *  higher-kinded types will change in future versions of Scala. So an explicit
   *  enabling also serves as a warning that code involving higher-kinded types
   *  might have to be slightly revised in the future.
   *
   *  @group production
   */
  implicit lazy val higherKinds: higherKinds = languageFeature.higherKinds

  /** Only where enabled, existential types that cannot be expressed as wildcard
   *  types can be written and are allowed in inferred types of values or return
   *  types of methods. Existential types with wildcard type syntax such as `List[_]`,
   *  or `Map[String, _]` are not affected.
   *
   *  '''Why keep the feature?''' Existential types are needed to make sense of Java’s wildcard
   *  types and raw types and the erased types of run-time values.
   *
   *  '''Why control it?''' Having complex existential types in a code base usually makes
   *  application code very brittle, with a tendency to produce type errors with
   *  obscure error messages. Therefore, going overboard with existential types
   *  is generally perceived not to be a good idea. Also, complicated existential types
   *  might be no longer supported in a future simplification of the language.
   *
   *  @group production
   */
  implicit lazy val existentials: existentials = languageFeature.existentials

  /** The experimental object contains features that have been recently added but have not
   *  been thoroughly tested in production yet.
   *
   *  Experimental features '''may undergo API changes''' in future releases, so production
   *  code should not rely on them.
   *
   *  Programmers are encouraged to try out experimental features and
   *  [[http://issues.scala-lang.org report any bugs or API inconsistencies]]
   *  they encounter so they can be improved in future releases.
   *
   *  @group experimental
   */
  object experimental {

    import languageFeature.experimental._

    /** Where enabled, macro definitions are allowed. Macro implementations and
     *  macro applications are unaffected; they can be used anywhere.
     *
     *  '''Why introduce the feature?''' Macros promise to make the language more regular,
     *  replacing ad-hoc language constructs with a general powerful abstraction
     *  capability that can express them. Macros are also a more disciplined and
     *  powerful replacement for compiler plugins.
     *
     *  '''Why control it?''' For their very power, macros can lead to code that is hard
     *  to debug and understand.
     */
    implicit lazy val macros: macros = languageFeature.experimental.macros
  }

  /** Where imported, a backwards compatibility mode for Scala2 is enabled */
  object Scala2Compat

  /** Where imported, auto-tupling is disabled */
  object noAutoTupling

  /** Where imported, loose equality using eqAny is disabled */
  object strictEquality

  /** Where imported, ad hoc extensions of non-open classes in other
   *  compilation units are allowed.
   *
   *  '''Why control the feature?''' Ad-hoc extensions should usually be avoided
   *  since they typically cannot rely on an "internal" contract between a class
   *  and its extensions. Only open classes need to specify such a contract.
   *  Ad-hoc extensions might break for future versions of the extended class,
   *  since the extended class is free to change its implementation without
   *  being constrained by an internal contract.
   *
   *  '''Why allow it?''' An ad-hoc extension can sometimes be necessary,
   *  for instance when mocking a class in a testing framework, or to work
   *  around a bug or missing feature in the original class. Nevertheless,
   *  such extensions should be limited in scope and clearly documented.
   *  That's why the language import is required for them.
   */
  object adhocExtensions

  /** Source version */
  object `3.0-migration`
  object `3.0`
  object `3.1-migration`
  object `3.1`
}
