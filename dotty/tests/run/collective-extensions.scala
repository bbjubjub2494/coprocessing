extension on (x: String):
  def foo(y: String): String = x ++ y
  def bar(y: String): String = foo(y)
  def baz(y: String): String =
    val x = y
    bar(x)
  def bam(y: String): String = this.baz(x)(y)
  def ban(foo: String): String = x + foo
  def bao(y: String): String =
    val bam = "ABC"
    x ++ y ++ bam

  def app(n: Int, suffix: String): String =
    if n == 0 then x ++ suffix
    else app(n - 1, suffix ++ suffix)

@main def Test =
  assert("abc".bar("def") == "abcdef")
  assert("abc".baz("def") == "abcdef")
  assert("abc".bam("def") == "abcdef")
  assert("abc".ban("def") == "abcdef")
  assert("abc".bao("def") == "abcdefABC")
  assert("abc".app(3, "!") == "abc!!!!!!!!")
