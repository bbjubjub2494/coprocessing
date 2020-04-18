/*
 * Copyright (c) 2014 Contributor. All rights reserved.
 */
package dotty.tools.dotc.classpath

import java.io.File
import java.net.URL

import dotty.tools.io.{ AbstractFile, FileZipArchive }
import FileUtils.AbstractFileOps
import dotty.tools.io.{ClassPath, ClassRepresentation}

/**
 * A trait allowing to look for classpath entries of given type in zip and jar files.
 * It provides common logic for classes handling class and source files.
 * It's aware of things like e.g. META-INF directory which is correctly skipped.
 */
trait ZipArchiveFileLookup[FileEntryType <: ClassRepresentation] extends ClassPath {
  val zipFile: File

  assert(zipFile != null, "Zip file in ZipArchiveFileLookup cannot be null")

  override def asURLs: Seq[URL] = Seq(zipFile.toURI.toURL)
  override def asClassPathStrings: Seq[String] = Seq(zipFile.getPath)

  private val archive = new FileZipArchive(zipFile.toPath)

  override private[dotty] def packages(inPackage: String): Seq[PackageEntry] = {
    val prefix = PackageNameUtils.packagePrefix(inPackage)
    for {
      dirEntry <- findDirEntry(inPackage).toSeq
      entry <- dirEntry.iterator if entry.isPackage
    }
    yield PackageEntryImpl(prefix + entry.name)
  }

  protected def files(inPackage: String): Seq[FileEntryType] =
    for {
      dirEntry <- findDirEntry(inPackage).toSeq
      entry <- dirEntry.iterator if isRequiredFileType(entry)
    }
    yield createFileEntry(entry)

  protected def file(inPackage: String, name: String): Option[FileEntryType] =
    for {
      dirEntry <- findDirEntry(inPackage)
      entry <- Option(dirEntry.lookupName(name, directory = false))
      if isRequiredFileType(entry)
    }
    yield createFileEntry(entry)

  override private[dotty] def hasPackage(pkg: String): Boolean = findDirEntry(pkg).isDefined
  override private[dotty] def list(inPackage: String): ClassPathEntries = {
    val foundDirEntry = findDirEntry(inPackage)

    foundDirEntry map { dirEntry =>
      val pkgBuf = collection.mutable.ArrayBuffer.empty[PackageEntry]
      val fileBuf = collection.mutable.ArrayBuffer.empty[FileEntryType]
      val prefix = PackageNameUtils.packagePrefix(inPackage)

      for (entry <- dirEntry.iterator)
        if (entry.isPackage)
          pkgBuf += PackageEntryImpl(prefix + entry.name)
        else if (isRequiredFileType(entry))
          fileBuf += createFileEntry(entry)
      ClassPathEntries(pkgBuf, fileBuf)
    } getOrElse ClassPathEntries(Seq.empty, Seq.empty)
  }

  private def findDirEntry(pkg: String): Option[archive.DirEntry] = {
    val dirName = pkg.replace('.', '/') + "/"
    archive.allDirs.get(dirName)
  }

  protected def createFileEntry(file: FileZipArchive#Entry): FileEntryType
  protected def isRequiredFileType(file: AbstractFile): Boolean
}
