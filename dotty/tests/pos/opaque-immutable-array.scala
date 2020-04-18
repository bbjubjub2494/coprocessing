object ia {
  import scala.reflect.ClassTag
  import java.util.Arrays

  opaque type IArray[A1] = Array[A1]

  object IArray {
    def apply[A: ClassTag](xs: A*) = initialize(Array(xs: _*))

    def initialize[A](body: => Array[A]): IArray[A] = body
    def size[A](ia: IArray[A]): Int = ia.length
    def get[A](ia: IArray[A], i: Int): A = ia(i)

    // return a sorted copy of the array
    def sorted[A <: AnyRef : math.Ordering](ia: IArray[A]): IArray[A] = {
      val arr = Arrays.copyOf(ia, ia.length)
      scala.util.Sorting.quickSort(arr)
      arr
    }

    // use a standard java method to search a sorted IArray.
    // (note that this doesn't mutate the array).
    def binarySearch(ia: IArray[Long], elem: Long): Int =
      Arrays.binarySearch(ia, elem)

    assert(size(apply(1, 2, 3)) == 3)
    assert(size(IArray(1, 2, 3)) == 3)
  }

  // same as IArray.binarySearch but implemented by-hand.
  //
  // given a sorted IArray, returns index of `elem`,
  // or a negative value if not found.
  def binaryIndexOf(ia: IArray[Long], elem: Long): Int = {
    var lower: Int = 0
    var upper: Int = IArray.size(ia)
    while (lower <= upper) {
      val middle = (lower + upper) >>> 1
      val n = IArray.get(ia, middle)

      if (n == elem) return middle
      else if (n < elem) lower = middle + 1
      else upper = middle - 1
    }
    -lower - 1
  }
}