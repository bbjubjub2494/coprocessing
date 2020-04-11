package coprocessing.core

import processing.core.PApplet

def runSketch(s: Sketch): Unit = {
    given PApplet {
        override def settings: Unit =
            s.settings.apply
    }
    PApplet.runSketch(Array[String|Null]("coprocessing.core"), thePApplet)
}

def thePApplet(using pa: PApplet) = pa
given (using PApplet) as SizeOps {
    def size(width: Int, height: Int) = thePApplet.size(width, height)
}
