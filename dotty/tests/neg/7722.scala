class A(i:Int){
  @scala.annotation.alpha("E") def this() = this(3) // error
}
object O{
  def main(args: Array[String]) = {
    new A()
  }
}
