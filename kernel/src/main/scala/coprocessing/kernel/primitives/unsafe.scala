package coprocessing.kernel.primitives

/** Unsafe casts that are sometimes needed internally
 */
private object unsafe {
  /** Obtain a immutable-typed reference to a mutable array.
   * To be used cautiously and with ownership in mind.
   */
  def [T <: Array[Scalar]](self: T).freeze: IArray[Scalar] =
    self.asInstanceOf[IArray[Scalar]]
  /** Obtain a mutable-typed reference to an immutable array.
   * To be used VERY cautiously!
   */
  def (self: IArray[Scalar]).unfreeze: Array[Scalar] =
    self.asInstanceOf[Array[Scalar]]
}
