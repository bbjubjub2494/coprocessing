package a

import scala.quoted._


object A:

  inline def transform[A](inline expr: A): A = ${
    transformImplExpr('expr)
  }

  def pure[A](a:A):A = ???

  def transformImplExpr[A:Type](using qctx: QuoteContext)(expr: Expr[A]): Expr[A] = {
     import qctx.tasty._
     expr.unseal match {
         case Inlined(x,y,z) => transformImplExpr(z.seal.asInstanceOf[Expr[A]])
         case r@Apply(fun,args) =>  '{
              A.pure(${r.seal.asInstanceOf[Expr[A]]}) }
         case other => expr
     }
  }
