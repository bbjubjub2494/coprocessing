package coprocessing.kernel.primitives

import unsafe._

import cats.kernel.Eq


/** Type of scalar values */
type Scalar = Float

given Eq[Scalar] = {
  import cats.kernel.instances.float._
  summon[Eq[Scalar]]
}

def cloneA(a: IArray[Scalar]): Array[Scalar] =
  Array.copyOf(a.unfreeze, a.length)

def mulSA(s: Scalar, a: IArray[Scalar]): IArray[Scalar] =
  val r = cloneA(a)
  for i <- 0 until r.length do
    r(i) *= s
  r.freeze

def addAA(a1: IArray[Scalar], a2: IArray[Scalar]): IArray[Scalar] =
  assert(a1.length == a2.length)
  val r = cloneA(a1)
  for i <- 0 until r.length do
    r(i) += a2(i)
  r.freeze
