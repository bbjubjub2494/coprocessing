class Foo {
  val x: Int = (1: @annot1 @annot2 @annot3 @annot4 @annot5)
}

class annot1 extends scala.annotation.Annotation
class annot2 extends scala.annotation.Annotation
class annot3 extends scala.annotation.Annotation
class annot4 extends scala.annotation.Annotation
class annot5 extends scala.annotation.Annotation
