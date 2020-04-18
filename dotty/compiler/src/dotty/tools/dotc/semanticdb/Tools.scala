package dotty.tools.dotc.semanticdb

import java.nio.file._
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.semanticdb.Scala3.{_, given _}

object Tools:

  /** Load SemanticDB TextDocument for a single Scala source file
   *
   * @param scalaAbsolutePath Absolute path to a Scala source file.
   * @param scalaRelativePath scalaAbsolutePath relativized by the sourceroot.
   * @param semanticdbAbsolutePath Absolute path to the SemanticDB file.
   */
  def loadTextDocument(
    scalaAbsolutePath: Path,
    scalaRelativePath: Path,
    semanticdbAbsolutePath: Path
  ): TextDocument =
    val reluri = scalaRelativePath.toString
    val sdocs = parseTextDocuments(semanticdbAbsolutePath)
    sdocs.documents.find(_.uri == reluri) match
    case None => throw new NoSuchElementException(reluri)
    case Some(document) =>
      val text = new String(Files.readAllBytes(scalaAbsolutePath), StandardCharsets.UTF_8)
      // Assert the SemanticDB payload is in-sync with the contents of the Scala file on disk.
      val md5FingerprintOnDisk = internal.MD5.compute(text)
      if document.md5 != md5FingerprintOnDisk
        throw new IllegalArgumentException("stale semanticdb: " + reluri)
      else
        // Update text document to include full text contents of the file.
        document.copy(text = text)
  end loadTextDocument

  /** Parses SemanticDB text documents from an absolute path to a `*.semanticdb` file. */
  private def parseTextDocuments(path: Path): TextDocuments =
    val bytes = Files.readAllBytes(path) // NOTE: a semanticdb file is a TextDocuments message, not TextDocument
    TextDocuments.parseFrom(bytes)

  def metac(doc: TextDocument, realPath: Path)(using sb: StringBuilder): StringBuilder =
    val realURI = realPath.toString
    given SourceFile = SourceFile.virtual(doc.uri, doc.text)
    sb.append(realURI).nl
    sb.append("-" * realURI.length).nl
    sb.nl
    sb.append("Summary:").nl
    sb.append("Schema => ").append(schemaString(doc.schema)).nl
    sb.append("Uri => ").append(doc.uri).nl
    sb.append("Text => empty").nl
    sb.append("Language => ").append(languageString(doc.language)).nl
    sb.append("Symbols => ").append(doc.symbols.length).append(" entries").nl
    sb.append("Occurrences => ").append(doc.occurrences.length).append(" entries").nl
    sb.nl
    sb.append("Symbols:").nl
    doc.symbols.sorted.foreach(processSymbol)
    sb.nl
    sb.append("Occurrences:").nl
    doc.occurrences.sorted.foreach(processOccurrence)
    sb.nl
  end metac

  private def schemaString(schema: Schema) =
    import Schema._
    schema match
    case SEMANTICDB3     => "SemanticDB v3"
    case SEMANTICDB4     => "SemanticDB v4"
    case LEGACY          => "SemanticDB legacy"
    case Unrecognized(_) => "unknown"
  end schemaString

  private def languageString(language: Language) =
    import Language._
    language match
    case SCALA                              => "Scala"
    case JAVA                               => "Java"
    case UNKNOWN_LANGUAGE | Unrecognized(_) => "unknown"
  end languageString

  private def processSymbol(info: SymbolInformation)(using sb: StringBuilder): Unit =
    import SymbolInformation.Kind._
    sb.append(info.symbol).append(" => ")
    if info.isAbstract then sb.append("abstract ")
    if info.isFinal then sb.append("final ")
    if info.isSealed then sb.append("sealed ")
    if info.isImplicit then sb.append("implicit ")
    if info.isLazy then sb.append("lazy ")
    if info.isCase then sb.append("case ")
    if info.isCovariant then sb.append("covariant ")
    if info.isContravariant then sb.append("contravariant ")
    if info.isVal then sb.append("val ")
    if info.isVar then sb.append("var ")
    if info.isStatic then sb.append("static ")
    if info.isPrimary then sb.append("primary ")
    if info.isEnum then sb.append("enum ")
    if info.isDefault then sb.append("default ")
    info.kind match
      case LOCAL => sb.append("local ")
      case FIELD => sb.append("field ")
      case METHOD => sb.append("method ")
      case CONSTRUCTOR => sb.append("ctor ")
      case MACRO => sb.append("macro ")
      case TYPE => sb.append("type ")
      case PARAMETER => sb.append("param ")
      case SELF_PARAMETER => sb.append("selfparam ")
      case TYPE_PARAMETER => sb.append("typeparam ")
      case OBJECT => sb.append("object ")
      case PACKAGE => sb.append("package ")
      case PACKAGE_OBJECT => sb.append("package object ")
      case CLASS => sb.append("class ")
      case TRAIT => sb.append("trait ")
      case INTERFACE => sb.append("interface ")
      case UNKNOWN_KIND | Unrecognized(_) => sb.append("unknown ")
    sb.append(info.displayName).nl
  end processSymbol

  private def processOccurrence(occ: SymbolOccurrence)(using sb: StringBuilder, sourceFile: SourceFile): Unit =
    occ.range match
    case Some(range) =>
      sb.append('[')
        .append(range.startLine).append(':').append(range.startCharacter)
        .append("..")
        .append(range.endLine).append(':').append(range.endCharacter)
        .append("):")
      if range.endLine == range.startLine
      && range.startCharacter != range.endCharacter
      && !(occ.symbol.isConstructor && occ.role.isDefinition)
        val line = sourceFile.lineContent(sourceFile.lineToOffset(range.startLine))
        assert(range.startCharacter <= line.length && range.endCharacter <= line.length,
          s"Line is only ${line.length} - start line was ${range.startLine} in source ${sourceFile.name}"
        )
        sb.append(" ").append(line.substring(range.startCharacter, range.endCharacter))
    case _ =>
      sb.append("[):")
    end match
    sb.append(if occ.role.isReference then " -> " else " <- ").append(occ.symbol).nl
  end processOccurrence

  private inline def (sb: StringBuilder) nl = sb.append(System.lineSeparator)
