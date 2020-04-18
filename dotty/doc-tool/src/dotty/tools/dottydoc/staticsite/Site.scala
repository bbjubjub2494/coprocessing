package dotty.tools
package dottydoc
package staticsite

import java.nio.file.{ Files, FileSystems }
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.io.{ File => JFile, OutputStreamWriter, BufferedWriter, ByteArrayInputStream }
import java.util.{ List => JList, Arrays }
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.io.File.{ separator => sep }

import com.vladsch.flexmark.parser.{ Parser, ParserEmulationProfile }
import com.vladsch.flexmark.ext.gfm.tables.TablesExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.emoji.EmojiExtension
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.util.options.{ DataHolder, MutableDataSet }

import dotc.core.Contexts.Context
import dotc.util.SourceFile
import model.Package
import scala.io.{ Codec, Source }
import io.{ AbstractFile, VirtualFile, File }
import scala.collection.mutable.ArrayBuffer
import util.syntax._

case class Site(
  root: JFile,
  outDir: JFile,
  projectTitle: String,
  projectVersion: String,
  projectUrl: Option[String],
  projectLogo: Option[String],
  documentation: Map[String, Package],
  baseUrl: String
) extends ResourceFinder {
  /** Documentation serialized to java maps */
  private val docs: JList[_] = {
    import model.JavaConverters._
    documentation.toJavaList
  }

  private val docsFlattened: JList[_] = {
    import model.JavaConverters._
    documentation.flattened
  }

  /** All files that are considered static in this context, this can be
    * anything from CSS, JS to images and other files.
    *
    * @note files that are *not* considered static are files ending in a compilable
    *       extension.
    */
  def staticAssets(implicit ctx: Context): Array[JFile] = {
    if (_staticAssets eq null) initFiles
    _staticAssets
  }

  /** All files that are considered compilable assets in this context. This
    * is mainly markdown and html files, but could include other files in the
    * future.
    *
    * @note files that are considered compilable end in `.md` or `.html`
    */
  def compilableFiles(implicit ctx: Context): Array[JFile] = {
    if (_compilableFiles eq null) initFiles
    _compilableFiles
  }

  /** All files that are considered blogposts, currently this means that files have been placed in:
    *
    * ```
    * ./blog/_posts/year-month-day-title.ext
    * ```
    *
    * where `ext` is either markdown or html.
    */
  def blogposts(implicit ctx: Context): Array[JFile] = {
    if (_blogposts eq null) initFiles
    _blogposts
  }

  /** Sidebar created from `sidebar.yml` file in site root */
  val sidebar: Sidebar =
    root
      .listFiles
      .find(_.getName == "sidebar.yml")
      .map("---\n" + Source.fromFile(_).mkString + "\n---")
      .map(Yaml.apply)
      .flatMap(Sidebar.apply)
      .getOrElse(Sidebar.empty)

  private[this] var _blogInfo: Array[BlogPost] = _
  protected def blogInfo(implicit ctx: Context): Array[BlogPost] = {
    if (_blogInfo eq null) {
      _blogInfo =
        blogposts
        .flatMap { file =>
          val BlogPost.extract(year, month, day, name, ext) = file.getName
          val sourceFile = toSourceFile(file)
          val params = defaultParams(file).withUrl(s"/blog/$year/$month/$day/$name.html").toMap
          val page =
            if (ext == "md")
              new MarkdownPage(file.getPath, sourceFile, params, includes, documentation)
            else new HtmlPage(file.getPath, sourceFile, params, includes)
          BlogPost(file, page)
        }
        .sortBy(_.date)
        .reverse
    }

    _blogInfo
  }

  // FileSystem getter
  private[this] val fs = FileSystems.getDefault

  /** Create virtual file from string `sourceCode` */
  private def stringToSourceFile(name: String, path: String, sourceCode: String): SourceFile = {
    val virtualFile = new VirtualFile(name, path)
    val writer = new BufferedWriter(new OutputStreamWriter(virtualFile.output, "UTF-8"))
    writer.write(sourceCode)
    writer.close()

    new SourceFile(virtualFile, Codec.UTF8)
  }

  def copyStaticFiles()(implicit ctx: Context): this.type =
    createOutput {
      // Copy user-defined static assets
      staticAssets.foreach { asset =>
        val target = mkdirs(fs.getPath(outDir.getAbsolutePath, stripRoot(asset)))
        val source = mkdirs(fs.getPath(asset.getAbsolutePath))
        Files.copy(source, target, REPLACE_EXISTING)
      }

      // Copy statics included in resources
      Map(
        "css/toolbar.css" -> "/css/toolbar.css",
        "css/search.css" -> "/css/search.css",
        "css/sidebar.css" -> "/css/sidebar.css",
        "css/dottydoc.css" -> "/css/dottydoc.css",
        "css/color-brewer.css" -> "/css/color-brewer.css",
        "css/bootstrap.min.css" -> "/css/bootstrap.min.css",
        "js/toolbar.js" -> "/js/toolbar.js",
        "js/sidebar.js" -> "/js/sidebar.js",
        "js/dottydoc.js" -> "/js/dottydoc.js",
        "js/api-search.js" -> "/js/api-search.js",
        "js/bootstrap.min.js" -> "/js/bootstrap.min.js",
        "js/jquery.min.js" -> "/js/jquery.min.js",
        "js/highlight.pack.js" -> "/js/highlight.pack.js",
        "images/dotty-logo.svg" -> "/images/dotty-logo.svg",
        "images/scala-logo.svg" -> "/images/scala-logo.svg",
        "images/dotty-logo-white.svg" -> "/images/dotty-logo-white.svg",
        "images/scala-logo-white.svg" -> "/images/scala-logo-white.svg"
      )
      .transform((_, v) => getResource(v))
      .foreach { case (path, resource) =>
        val source = new ByteArrayInputStream(resource.getBytes(StandardCharsets.UTF_8))
        val target = mkdirs(fs.getPath(outDir.getAbsolutePath, path))
        Files.copy(source, target, REPLACE_EXISTING)
      }
    }

  /** Generate default params included in each page */
  private def defaultParams(pageLocation: JFile): DefaultParams =
    val pathFromRoot = stripRoot(pageLocation)
    DefaultParams(
      docs, docsFlattened, documentation, PageInfo(pathFromRoot),
      SiteInfo(
        baseUrl, projectTitle, projectVersion, projectUrl, projectLogo, Array(),
        root.toString
      ),
      sidebar
    )

  /* Creates output directories if allowed */
  private def createOutput(op: => Unit)(implicit ctx: Context): this.type = {
    if (!outDir.isDirectory) outDir.mkdirs()
    if (!outDir.isDirectory) ctx.docbase.error(s"couldn't create output folder: $outDir")
    else op
    this
  }

  /** Generate HTML for the API documentation */
  def generateApiDocs()(implicit ctx: Context): this.type =
    createOutput {
      def genDoc(e: model.Entity): Unit = {
        ctx.docbase.echo(s"Generating doc page for: ${e.path.mkString(".")}")
        // Suffix is index.html for packages and therefore the additional depth
        // is increased by 1
        val (suffix, offset) =
          if (e.kind == "package") (sep + "index.html", -1)
          else (".html", 0)

        val path = if (scala.util.Properties.isWin)
          e.path.map(_.replace("<", "_").replace(">", "_"))
        else
          e.path
        val target = mkdirs(fs.getPath(outDir.getAbsolutePath +  sep + "api" + sep + path.mkString(sep) + suffix))
        val params = defaultParams(target.toFile).withPosts(blogInfo).withEntity(Some(e)).toMap
        val page = new HtmlPage("_layouts" + sep + "api-page.html", layouts("api-page").content, params, includes)

        render(page).foreach { rendered =>
          val source = new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8))
          Files.copy(source, target, REPLACE_EXISTING)
        }

        // Generate docs for nested objects/classes:
        e.children.foreach(genDoc)
      }

      documentation.values.foreach { pkg =>
        genDoc(pkg)
        pkg.children.foreach(genDoc)
      }

      // generate search page:
      val target = mkdirs(fs.getPath(outDir.getAbsolutePath + sep + "api" + sep + "search.html"))
      val searchPageParams = defaultParams(target.toFile).withPosts(blogInfo).toMap
      val searchPage = new HtmlPage("_layouts" + sep + "search.html", layouts("search").content, searchPageParams, includes)
      render(searchPage).foreach { rendered =>
        Files.copy(
          new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)),
          target,
          REPLACE_EXISTING
        )
      }
    }

  /** Generate HTML files from markdown and .html sources */
  def generateHtmlFiles()(implicit ctx: Context): this.type =
    createOutput {
      compilableFiles.foreach { asset =>
        val pathFromRoot = stripRoot(asset)
        val sourceFile = toSourceFile(asset)
        val params = defaultParams(asset).withPosts(blogInfo).toMap
        val page =
          if (asset.getName.endsWith(".md")) new MarkdownPage(pathFromRoot, sourceFile, params, includes, documentation)
          else new HtmlPage(pathFromRoot, sourceFile, params, includes)

        render(page).foreach { renderedPage =>
          val source = new ByteArrayInputStream(renderedPage.getBytes(StandardCharsets.UTF_8))
          val target = pathFromRoot.splitAt(pathFromRoot.lastIndexOf('.'))._1 + ".html"
          val htmlTarget = mkdirs(fs.getPath(outDir.getAbsolutePath, target))
          Files.copy(source, htmlTarget, REPLACE_EXISTING)
        }
      }
    }

  /** Generate blog from files in `blog/_posts` and output in `outDir` */
  def generateBlog()(implicit ctx: Context): this.type =
    createOutput {
      blogposts.foreach { file =>
        val BlogPost.extract(year, month, day, name, ext) = file.getName
        val sourceFile = toSourceFile(file)
        val date = s"$year-$month-$day 00:00:00"
        val params = defaultParams(file).withPosts(blogInfo).withDate(date).toMap

        // Output target
        val target = mkdirs(fs.getPath(outDir.getAbsolutePath, "blog", year, month, day, name + ".html"))

        val page =
          if (ext == "md")
            new MarkdownPage(target.toString, sourceFile, params, includes, documentation)
          else
            new HtmlPage(target.toString, sourceFile, params, includes)

        render(page).map { rendered =>
          val source = new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8))
          Files.copy(source, target, REPLACE_EXISTING)
        }
      }
    }

  /** Create directories and issue an error if could not */
  private def mkdirs(path: Path)(implicit ctx: Context): path.type = {
    val parent = path.getParent.toFile

    if (!parent.isDirectory && !parent.mkdirs())
      ctx.docbase.error(s"couldn't create directory: $parent")

    path
  }

  /** This function allows the stripping of the path that leads up to root.
    *
    * ```scala
    * stripRoot(new JFile("/some/root/dir/css/index.css"))
    * // returns: dir/css/index.css
    * // given that root is: /some/root
    * ```
    */
  def stripRoot(f: JFile, root: JFile = root): String = {
    val rootLen = root.getAbsolutePath.length + 1
    f.getAbsolutePath.drop(rootLen)
  }

  // Initialization of `staticAssets` and `compilableAssets`, and `blogPosts`:
  private[this] var _staticAssets: Array[JFile] = _
  private[this] var _compilableFiles: Array[JFile] = _
  private[this] var _blogposts: Array[JFile] = _

  private[this] def initFiles(implicit ctx: Context) = {
    // Split files between compilable and static assets
    def splitFiles(f: JFile, assets: ArrayBuffer[JFile], comp: ArrayBuffer[JFile]): Unit = {
      val name = f.getName
      if (f.isDirectory) {
        val name = f.getName
        if (!name.startsWith("_") && name != "api") f.listFiles.foreach(splitFiles(_, assets, comp))
        if (f.getName == "api") ctx.docbase.warn {
          "the specified `/api` directory will not be used since it is needed for the api documentation"
        }
      }
      else if (name.endsWith(".md") || name.endsWith(".html")) comp.append(f)
      else assets.append(f)
    }

    // Collect posts from ./blog/_posts
    def collectPosts(file: JFile): Option[JFile] = file.getName match {
      case BlogPost.extract(year, month, day, name, ext) => Some(file)
      case _ => None
    }

    val assets = new ArrayBuffer[JFile]
    val comp = new ArrayBuffer[JFile]
    splitFiles(root, assets, comp)
    _staticAssets = assets.toArray
    _compilableFiles = comp.toArray

    _blogposts =
      root
      .listFiles
      .find(dir => dir.getName == "blog" && dir.isDirectory)
      .map(_.listFiles).getOrElse(Array.empty[JFile]) //FIXME: remove [JFile] once #1907 is fixed
      .find(dir => dir.getName == "_posts" && dir.isDirectory)
      .map(_.listFiles).getOrElse(Array.empty[JFile]) //FIXME: remove [JFile] once #1907 is fixed
      .flatMap(collectPosts)
  }

  /** Files that define a layout then referred to by `layout: filename-no-ext`
    * in yaml front-matter.
    *
    * The compiler will look in two locations, `<root>/_layouts/` and
    * in the bundled jar file's resources `/_layouts`.
    *
    * If the user supplies a layout that has the same name as one of the
    * defaults, the user-defined one will take precedence.
    */
  val layouts: Map[String, Layout] = {
    val userDefinedLayouts =
      root
      .listFiles.find(d => d.getName == "_layouts" && d.isDirectory)
      .map(collectFiles(_, f => f.endsWith(".md") || f.endsWith(".html")))
      .getOrElse(Array.empty[JFile])
      .map(f => (f.getName.substring(0, f.getName.lastIndexOf('.')), Layout(f.getPath, toSourceFile(f))))
      .toMap

    val defaultLayouts: Map[String, Layout] = Map(
      "base" -> "/_layouts/base.html",
      "main" -> "/_layouts/main.html",
      "search" -> "/_layouts/search.html",
      "doc-page" -> "/_layouts/doc-page.html",
      "api-page" -> "/_layouts/api-page.html",
      "blog-page" -> "/_layouts/blog-page.html",
      "index" -> "/_layouts/index.html"
    ).map {
      case (name, path) =>
        (name, Layout(path, stringToSourceFile(name, path, getResource(path))))
    }

    defaultLayouts ++ userDefinedLayouts
  }

  /** Include files are allowed under the directory `_includes`. These files
    * have to be compilable files and can be used with liquid includes:
    *
    * ```
    * {% include "some-file" %}
    * ```
    *
    * You can also use the `with` statement:
    *
    * ```
    * {% include "some-file" with { key: value } %}
    * ```
    */
  val includes: Map[String, Include] = {
    val userDefinedIncludes =
      root
      .listFiles.find(d => d.getName == "_includes" && d.isDirectory)
      .map(collectFiles(_, f => f.endsWith(".md") || f.endsWith(".html")))
      .getOrElse(Array.empty[JFile])
      .map(f => (f.getName, Include(f.getPath, toSourceFile(f))))
      .toMap

    val defaultIncludes: Map[String, Include] = Map(
      "header.html" -> "/_includes/header.html",
      "toolbar.html" -> "/_includes/toolbar.html",
      "sidebar.html" -> "/_includes/sidebar.html"
    ).map {
      case (name, path) =>
        (name, Include(path, stringToSourceFile(name, path, getResource(path))))
    }

    defaultIncludes ++ userDefinedIncludes
  }

  private def toSourceFile(f: JFile): SourceFile =
    new SourceFile(AbstractFile.getFile(new File(f.toPath)), Source.fromFile(f, "UTF-8").toArray)

  private def collectFiles(dir: JFile, includes: String => Boolean): Array[JFile] =
    dir
    .listFiles
    .filter(f => includes(f.getName))

  /** Render a page to html, the resulting string is the result of the complete
    * expansion of the template with all its layouts and includes.
    */
  def render(page: Page, params: Map[String, AnyRef] = Map.empty)(implicit ctx: Context): Option[String] =
    page.yaml.get("layout").flatMap(xs => layouts.get(xs.toString)) match {
      case Some(layout) if page.html.isDefined =>
        val newParams = page.params ++ params ++ Map("page" -> page.yaml) ++ Map("content" -> page.html.get)
        val expandedTemplate = new HtmlPage(layout.path, layout.content, newParams, includes)
        render(expandedTemplate, params)
      case _ =>
        page.html
    }
}

object Site {
  val markdownOptions: DataHolder =
    new MutableDataSet()
      .setFrom(ParserEmulationProfile.KRAMDOWN.getOptions)
      .set(Parser.INDENTED_CODE_BLOCK_PARSER, false)
      .set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX, "")
      .set(HtmlRenderer.FENCED_CODE_NO_LANGUAGE_CLASS, "nohighlight")
      .set(AnchorLinkExtension.ANCHORLINKS_WRAP_TEXT, false)
      .set(AnchorLinkExtension.ANCHORLINKS_ANCHOR_CLASS, "anchor")
      .set(EmojiExtension.ROOT_IMAGE_PATH, "https://github.global.ssl.fastly.net/images/icons/emoji/")
      .set(Parser.EXTENSIONS, Arrays.asList(
        TablesExtension.create(),
        TaskListExtension.create(),
        AutolinkExtension.create(),
        AnchorLinkExtension.create(),
        EmojiExtension.create(),
        YamlFrontMatterExtension.create(),
        StrikethroughExtension.create()
      ))
}
