package dotty.tools
package dottydoc
package staticsite

import dotc.util.SourceFile
import java.io.{ BufferedWriter, OutputStreamWriter }
import io.VirtualFile
import scala.io.Codec

import model.Package

trait SourceFileOps {
  import scala.collection.JavaConverters._
  val siteRoot = new java.io.File("doc-tool/resources/")
  val site = new Site(
    siteRoot, new java.io.File(siteRoot, "_site"),
    "test-site", "v0.1", Some("http://github.com/lampepfl/dotty"), None, Map.empty, "/"
  )

  def stringToSource(path: String, sourceCode: String): SourceFile = {
    val virtualFile = new VirtualFile(path, path)
    val writer = new BufferedWriter(new OutputStreamWriter(virtualFile.output, "UTF-8"))
    writer.write(sourceCode)
    writer.close()

    new SourceFile(virtualFile, Codec.UTF8)
  }

  def markdownPage(
    sourceCode: String,
    path: String = "test-page",
    params: Map[String, AnyRef] = Map.empty,
    includes: Map[String, Include] = Map.empty,
    docs: Map[String, Package] = Map.empty
  ) = new MarkdownPage(
    path,
    stringToSource(path, sourceCode),
    params,
    includes,
    docs
  )

  def htmlPage(
    sourceCode: String,
    path: String = "test-page",
    params: Map[String, AnyRef] = Map.empty,
    includes: Map[String, Include] = Map.empty,
    docs: Map[String, Package] = Map.empty
  ) = new HtmlPage(
    path,
    stringToSource(path, sourceCode),
    params,
    includes
  )
}
