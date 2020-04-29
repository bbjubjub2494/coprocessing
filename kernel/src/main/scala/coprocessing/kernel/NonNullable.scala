package coprocessing.kernel

/** Typeclass to reject nullable types in type parameters.
 *
 * To be used in conjuction with [the explicit nulls feature][explicit-nulls].
 * Similar to, but more powerful than `cats.NotNull`.
 *
 * [explicit-nulls]: https://dotty.epfl.ch/docs/reference/other-new-features/explicit-nulls.html
 */

@annotation.implicitAmbiguous("${A} >: Null")
sealed trait NonNullable[+A]

object NonNullable:
  private object singleton extends NonNullable[Nothing]
  given [A] as NonNullable[A] = singleton
  given collider1 as NonNullable[Null] = throw NoSuchElementException()
  given collider2 as NonNullable[Null] = throw NoSuchElementException()
