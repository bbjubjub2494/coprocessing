import scala.concurrent.duration._

import scala.language.postfixOps

def test() = {
  1 second

  Seq(1, 2) filter (List(1,2) contains)
}