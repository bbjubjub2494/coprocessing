class C(c: C) {
  val c2 = new C(this)   // error
}
