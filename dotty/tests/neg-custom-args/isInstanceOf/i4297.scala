class Test {
  def test[X <: Option[Int]](x: X) = x.isInstanceOf[Some[Int]]
  def test1[Y <: Int, X <: Option[Y]](x: X) = x.isInstanceOf[Some[Int]]
  def test2(x: Any) = x.isInstanceOf[Function1[Nothing, _]]
  def test3a(x: Any) = x.isInstanceOf[Function1[Any, _]] // error
  def test3b(x: Any) = x.isInstanceOf[Function1[Int, _]] // error
  def test4[Y <: Int, X <: Function1[Y, Unit]](x: X) = x.isInstanceOf[Function1[Int, _]] // error
  def test5[Y <: Int, X <: Function1[Y, Unit]](x: X) = x.isInstanceOf[Function1[Int, Unit]]  // error
  def test6[Y <: Int, X <: Function1[Y, Unit]](x: X) = x.isInstanceOf[Function1[Int, Any]] // error
  def test7[Y <: Int, X <: Function1[Y, Unit]](x: X) = x.isInstanceOf[Function1[_, Unit]]
}
