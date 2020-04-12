/*
Copyright (c) 2011-2012 Erik Osheim, Tom Switzer

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
package coprocessing.kernel

import cats.kernel.Eq

/** Zero-cost equivalent to scala.Option.
*
* Borrowed from [Spire](https://typelevel.org/spire/)
*/
object Opt {
  def apply[A](a: A): Opt[A] = new Opt(a)

  def empty[A]: Opt[A] = new Opt[A](null)

  // this is a name-based extractor. instead of returning Option[_] it
  // is free to return any type with .isEmpty and .get, i.e. Opt[_].
  //
  // https://hseeberger.wordpress.com/2013/10/04/name-based-extractors-in-scala-2-11/
  def unapply[A](n: Opt[A]): Opt[A] = n

  given [A](using ev: Eq[A]) as Eq[Opt[A]] = Eq.instance {
    (x, y) =>
      (x.ref.asInstanceOf[AnyRef] eq y.ref.asInstanceOf[AnyRef]) ||
      x.ref != null && y.ref != null && ev.eqv(x.ref, y.ref)
  }

  // Scala 3 opt-in strict equality.
  // https://dotty.epfl.ch/docs/reference/contextual/multiversal-equality.html
  given [A, B](using A Eql B) as (Opt[A] Eql Opt[B]) = Eql.derived
}

class Opt[+A](val ref: A | Null) extends AnyVal {

  def isDefined: Boolean = ref != null
  def nonEmpty: Boolean = ref != null
  def isEmpty: Boolean = ref == null

  def get: A = if (ref == null) throw new NoSuchElementException("Opt.empty.get") else ref

  override def toString: String =
    if (ref == null) "Opt.empty" else s"Opt($ref)"

  def filter(f: A => Boolean): Opt[A] =
    if (ref != null && f(ref)) this else Opt.empty

  def map[B](f: A => B): Opt[B] =
    if (ref == null) Opt.empty else Opt(f(ref))

  def flatMap[B](f: A => Opt[B]): Opt[B] =
    if (ref == null) Opt.empty else f(ref)

  def fold[B](b: => B)(f: A => B): B =
    if (ref == null) b else f(ref)


  def getOrElse[B >: A](default: => B): B = if (ref == null) default else ref

  def getOrElseFast[B >: A](default: B): B = if (ref == null) default else ref

  def toOption: Option[A] = if (ref == null) None else Some(ref)

  def toList: List[A] = if (ref == null) Nil else (ref :: Nil)

  def contains[A1 >: A](elem: A1): Boolean = if (ref == null) false else ref == elem

  def exists(p: A => Boolean): Boolean = if (ref == null) false else p(ref)

  def forall(p: A => Boolean): Boolean = if (ref == null) true else p(ref)

  def foreach[U](f: A => U): Unit = if (ref != null) f(ref)

  def iterator: Iterator[A] = if (ref == null) collection.Iterator.empty else collection.Iterator.single(ref)

  def toRight[X](left: => X): Either[X, A] =
    if (ref == null) Left(left) else Right(ref)

  def toLeft[X](right: => X): Either[A, X] =
    if (ref == null) Right(right) else Left(ref)
}
