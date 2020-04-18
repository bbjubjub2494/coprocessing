---
layout: doc-page
title: "TASTy Reflect"
---

TASTy Reflect enables inspection and construction of Typed Abstract Syntax Trees
(Typed-AST). It may be used on quoted expressions (`quoted.Expr`) and quoted
types (`quoted.Type`) from [Macros](./macros.md) or on full TASTy files.

If you are writing macros, please first read [Macros](./macros.md).
You may find all you need without using TASTy Reflect.


## API: From quotes and splices to TASTy reflect trees and back

With `quoted.Expr` and `quoted.Type` we can compute code but also analyze code
by inspecting the ASTs. [Macros](./macros.md) provides the guarantee that the
generation of code will be type-correct. Using TASTy Reflect will break these
guarantees and may fail at macro expansion time, hence additional explicit
checks must be done.

To provide reflection capabilities in macros we need to add an implicit
parameter of type `scala.quoted.QuoteContext` and import `tasty._` from it in
the scope where it is used.

```scala
import scala.quoted._

inline def natConst(x: => Int): Int = ${natConstImpl('{x})}

def natConstImpl(x: Expr[Int])(using qctx: QuoteContext): Expr[Int] = {
  import qctx.tasty._
  ...
}
```

### Extractors

`import qctx.tasty._` will provide all extractors and methods on TASTy Reflect
trees. For example the `Literal(_)` extractor used below.

```scala
def natConstImpl(x: Expr[Int])(using qctx: QuoteContext): Expr[Int] = {
  import qctx.tasty._
  val xTree: Term = x.unseal
  xTree match {
    case Inlined(_, _, Literal(Constant(n: Int))) =>
      if (n <= 0) {
        qctx.error("Parameter must be natural number")
        '{0}
      } else {
        xTree.seal.cast[Int]
      }
    case _ =>
      qctx.error("Parameter must be a known constant")
      '{0}
  }
}
```

To easily know which extractors are needed, the `showExtractors` method on a
`qctx.tasty.Term` returns the string representation of the extractors.

The method `qctx.tasty.Term.seal` provides a way to go back to a
`quoted.Expr[Any]`. Note that the type is `Expr[Any]`. Consequently, the type
must be set explicitly with a checked `cast` call. If the type does not conform
to it an exception will be thrown. In the code above, we could have replaced
`Expr(n)` by `xTree.seal.cast[Int]`.

### Obtaining the underlying argument

A macro can access the tree of the actual argument passed on the call-site. The
`underlyingArgument` method on a `Term` object will give access to the tree
defining the expression passed. For example the code below matches a selection
operation expression passed while calling the `macro` below.

```scala
inline def macro(param: => Boolean): Unit = ${ macroImpl('param) }

def macroImpl(param: Expr[Boolean])(using qctx: QuoteContext): Expr[Unit] = {
  import qctx.tasty._
  import util._

  param.unseal.underlyingArgument match {
    case t @ Apply(Select(lhs, op), rhs :: Nil) => ..
  }
}

// example
macro(this.checkCondition())
```

### Positions

The tasty context provides a `rootPosition` value. For macros it corresponds to
the expansion site. The macro authors can obtain various information about that
expansion site. The example below shows how we can obtain position information
such as the start line, the end line or even the source code at the expansion
point.

```scala
def macroImpl()(qctx: QuoteContext): Expr[Unit] = {
  import qctx.tasty._
  val pos = rootPosition

  val path = pos.sourceFile.jpath.toString
  val start = pos.start
  val end = pos.end
  val startLine = pos.startLine
  val endLine = pos.endLine
  val startColumn = pos.startColumn
  val endColumn = pos.endColumn
  val sourceCode = pos.sourceCode
  ...
```

### Tree Utilities

`scala.tasty.reflect` contains three facilities for tree traversal and
transformations.

`TreeAccumulator` ties the knot of a traversal. By calling `foldOver(x, tree))`
we can dive in the `tree` node and start accumulating values of type `X` (e.g.,
of type List[Symbol] if we want to collect symbols). The code below, for
example, collects the pattern variables of a tree.

```scala
def collectPatternVariables(tree: Tree)(implicit ctx: Context): List[Symbol] = {
  val acc = new TreeAccumulator[List[Symbol]] {
    def apply(syms: List[Symbol], tree: Tree)(implicit ctx: Context) = tree match {
      case Bind(_, body) => apply(tree.symbol :: syms, body)
      case _ => foldOver(syms, tree)
    }
  }
  acc(Nil, tree)
}
```

A `TreeTraverser` extends a `TreeAccumulator` and performs the same traversal
but without returning any value. Finally a `TreeMap` performs a transformation.

#### Let

`scala.tasty.Reflection` also offers a method `let` that allows us
to bind the `rhs` to a `val` and use it in `body`. Additionally, `lets` binds
the given `terms` to names and use them in the `body`. Their type definitions
are shown below:

```scala
def let(rhs: Term)(body: Ident => Term): Term = ...

def lets(terms: List[Term])(body: List[Term] => Term): Term = ...
```

## More Examples

* Start experimenting with TASTy Reflect ([link](https://github.com/nicolasstucki/tasty-reflection-exercise))

