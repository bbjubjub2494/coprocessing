given foo(using x: Int) as AnyRef:
  type T = x.type

// #7859

trait Lub2[A, B]:
  type Output

given [A <: C, B <: C, C] as Lub2[A, B]:
  type Output = C

trait Lub[Union]:
  type Output

given [A] as Lub[A]:
  type Output = A

given [Left, Right](
    using lubLeft: Lub[Right], lubRight: Lub[Right])(
    using lub2: Lub2[lubLeft.Output, lubRight.Output])
as Lub[Left | Right]:
  type Output = lub2.Output
