object AnonymousF {
  val f = {
    case l @ List(1) => // error: missing parameter type
      Some(l)
  }
}
