package dotty.tools.tasty

import java.util.UUID

import TastyFormat.{MajorVersion, MinorVersion, header}

class TastyHeaderUnpickler(reader: TastyReader) {
  import reader._

  def this(bytes: Array[Byte]) = this(new TastyReader(bytes))

  def readHeader(): UUID = {
    for (i <- 0 until header.length)
      check(readByte() == header(i), "not a TASTy file")
    val major = readNat()
    val minor = readNat()
    check(major == MajorVersion && minor <= MinorVersion,
      s"""TASTy signature has wrong version.
         | expected: $MajorVersion.$MinorVersion
         | found   : $major.$minor""".stripMargin)
    new UUID(readUncompressedLong(), readUncompressedLong())
  }

  def isAtEnd: Boolean = reader.isAtEnd

  private def check(cond: Boolean, msg: => String): Unit = {
    if (!cond) throw new UnpickleException(msg)
  }
}
