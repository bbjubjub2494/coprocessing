package hello

import coprocessing.core._

object Hello extends Sketch {
  override def settings() =
    size(1000, 800)
  override def setup() =
    legacy {
      thePApplet.background(255, 200, 0)
    }
}

@main def runHello = runSketch(Hello)
