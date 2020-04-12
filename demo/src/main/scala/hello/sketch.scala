package hello

import coprocessing._
import coprocessing.p3._

class Hello(using P3LegacyOps) extends Sketch {
  override def settings() =
    size(1000, 800)
  override def setup() =
    legacy {
      thePApplet.background(255, 200, 0)
    }
}

@main def runHello = runSketch(new Hello)
