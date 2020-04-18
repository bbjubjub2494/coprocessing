import scala.annotation.tailrec
import scala.quoted._
import scala.quoted.autolift

object Macros {
  inline def unrolledForeach(seq: IndexedSeq[Int], f: => Int => Unit, inline unrollSize: Int): Unit = // or f: Int => Unit
    ${ unrolledForeachImpl('seq, 'f, 'unrollSize) }

  def unrolledForeachImpl(seq: Expr[IndexedSeq[Int]], f: Expr[Int => Unit], unrollSizeExpr: Expr[Int]) (using QuoteContext): Expr[Unit] =
    unrolledForeachImpl(seq, f, unrollSizeExpr.unliftOrError)

  def unrolledForeachImpl(seq: Expr[IndexedSeq[Int]], f: Expr[Int => Unit], unrollSize: Int)(using QuoteContext): Expr[Unit] = '{
    val size = ($seq).length
    assert(size % (${unrollSize}) == 0) // for simplicity of the implementation
    var i = 0
    while (i < size) {
      ${
        for (j <- new UnrolledRange(0, unrollSize)) '{
          val index = i + $j
          val element = ($seq)(index)
          ${ Expr.betaReduce(f)('element) } // or `($f)(element)` if `f` should not be inlined
        }
      }
      i += ${unrollSize}
    }

  }

  class UnrolledRange(start: Int, end: Int) {
    def foreach(f: Int => Expr[Unit])(using QuoteContext): Expr[Unit] = {
      @tailrec def loop(i: Int, acc: Expr[Unit]): Expr[Unit] =
        if (i >= 0) loop(i - 1, '{ ${f(i)}; $acc })
        else acc
      loop(end - 1, '{})
    }
  }
}
