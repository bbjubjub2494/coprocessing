package dotty.tools
package dotc
package parsing

import scala.annotation.internal.sharable
import scala.collection.mutable.ListBuffer
import scala.collection.immutable.BitSet
import util.{ SourceFile, SourcePosition, NoSourcePosition }
import Tokens._
import Scanners._
import xml.MarkupParsers.MarkupParser
import core._
import Flags._
import Contexts._
import Names._
import NameKinds.WildcardParamName
import NameOps._
import ast.{Positioned, Trees}
import ast.Trees._
import StdNames._
import util.Spans._
import Constants._
import Symbols.defn
import ScriptParsers._
import Decorators._
import scala.internal.Chars
import scala.annotation.{tailrec, switch}
import rewrites.Rewrites.{patch, overlapsPatch}
import reporting.Message
import reporting.messages._
import config.Feature.{sourceVersion, migrateTo3}
import config.SourceVersion._
import config.SourceVersion

object Parsers {

  import ast.untpd._

  val AllowOldWhiteboxSyntax = true

  case class OpInfo(operand: Tree, operator: Ident, offset: Offset)

  class ParensCounters {
    private var parCounts = new Array[Int](lastParen - firstParen)

    def count(tok: Token): Int = parCounts(tok - firstParen)
    def change(tok: Token, delta: Int): Unit = parCounts(tok - firstParen) += delta
    def nonePositive: Boolean = parCounts forall (_ <= 0)
  }

  @sharable object Location extends Enumeration {
    val InParens, InBlock, InPattern, ElseWhere: Value = Value
  }

  @sharable object ParamOwner extends Enumeration {
    val Class, Type, TypeParam, Def: Value = Value
  }

  type StageKind = Int
  object StageKind {
    val None = 0
    val Quoted = 1
    val Spliced = 2
  }

  private implicit class AddDeco(val buf: ListBuffer[Tree]) extends AnyVal {
    def +++=(x: Tree) = x match {
      case x: Thicket => buf ++= x.trees
      case x => buf += x
    }
  }

  /** The parse starting point depends on whether the source file is self-contained:
   *  if not, the AST will be supplemented.
   */
  def parser(source: SourceFile)(implicit ctx: Context): Parser =
    if source.isSelfContained then new ScriptParser(source)
    else new Parser(source)

  private val InCase: Region => Region = Scanners.InCase
  private val InCond: Region => Region = Scanners.InBraces

  abstract class ParserCommon(val source: SourceFile)(implicit ctx: Context) {

    val in: ScannerCommon

    /* ------------- POSITIONS ------------------------------------------- */

    /** Positions tree.
     *  If `t` does not have a span yet, set its span to the given one.
     */
    def atSpan[T <: Positioned](span: Span)(t: T): T =
      if (t.span.isSourceDerived) t else t.withSpan(span.union(t.span))

    def atSpan[T <: Positioned](start: Offset, point: Offset, end: Offset)(t: T): T =
      atSpan(Span(start, end, point))(t)

    /** If the last read offset is strictly greater than `start`, assign tree
     *  the span from `start` to last read offset, with given point.
     *  If the last offset is less than or equal to start, the tree `t` did not
     *  consume any source for its construction. In this case, don't assign a span yet,
     *  but wait for its span to be determined by `setChildSpans` when the
     *  parent node is positioned.
     */
    def atSpan[T <: Positioned](start: Offset, point: Offset)(t: T): T =
      if (in.lastOffset > start) atSpan(start, point, in.lastOffset)(t) else t

    def atSpan[T <: Positioned](start: Offset)(t: T): T =
      atSpan(start, start)(t)

    def startOffset(t: Positioned): Int =
      if (t.span.exists) t.span.start else in.offset

    def pointOffset(t: Positioned): Int =
      if (t.span.exists) t.span.point else in.offset

    def endOffset(t: Positioned): Int =
      if (t.span.exists) t.span.end else in.lastOffset

    def nameStart: Offset =
      if (in.token == BACKQUOTED_IDENT) in.offset + 1 else in.offset

    /** in.offset, except if this is at a new line, in which case `lastOffset` is preferred. */
    def expectedOffset: Int = {
      val current = in.sourcePos()
      val last = in.sourcePos(in.lastOffset)
      if (current.line != last.line) in.lastOffset else in.offset
    }

    /* ------------- ERROR HANDLING ------------------------------------------- */
    /** The offset where the last syntax error was reported, or if a skip to a
     *  safepoint occurred afterwards, the offset of the safe point.
     */
    protected var lastErrorOffset : Int = -1

    /** Issue an error at given offset if beyond last error offset
     *  and update lastErrorOffset.
     */
    def syntaxError(msg: Message, offset: Int = in.offset): Unit =
      if (offset > lastErrorOffset) {
        val length = if (offset == in.offset && in.name != null) in.name.show.length else 0
        syntaxError(msg, Span(offset, offset + length))
        lastErrorOffset = in.offset
      }

    /** Unconditionally issue an error at given span, without
     *  updating lastErrorOffset.
     */
    def syntaxError(msg: Message, span: Span): Unit =
      ctx.error(msg, source.atSpan(span))

    def unimplementedExpr(implicit ctx: Context): Select =
      Select(Select(rootDot(nme.scala), nme.Predef), nme.???)
  }

  trait OutlineParserCommon extends ParserCommon {
    def accept(token: Int): Int

    def skipBracesHook(): Option[Tree]
    def skipBraces(): Unit = {
      accept(if (in.token == INDENT) INDENT else LBRACE)
      var openBraces = 1
      val savedCheckEndMarker = in.checkEndMarker
      try
        in.checkEndMarker = false
        while (in.token != EOF && openBraces > 0)
          skipBracesHook() getOrElse {
            if (in.token == LBRACE || in.token == INDENT) openBraces += 1
            else if (in.token == RBRACE || in.token == OUTDENT) openBraces -= 1
            in.nextToken()
          }
      finally
        in.checkEndMarker = savedCheckEndMarker
    }
  }

  class Parser(source: SourceFile)(implicit ctx: Context) extends ParserCommon(source) {

    val in: Scanner = new Scanner(source)

    val openParens: ParensCounters = new ParensCounters

    /** This is the general parse entry point.
     *  Overridden by ScriptParser
     */
    def parse(): Tree = {
      val t = compilationUnit()
      accept(EOF)
      t
    }

/* -------------- TOKEN CLASSES ------------------------------------------- */

    def isIdent = in.isIdent
    def isIdent(name: Name) = in.isIdent(name)
    def isSimpleLiteral = simpleLiteralTokens contains in.token
    def isLiteral = literalTokens contains in.token
    def isNumericLit = numericLitTokens contains in.token
    def isTemplateIntro = templateIntroTokens contains in.token
    def isDclIntro = dclIntroTokens contains in.token
    def isStatSeqEnd = in.isNestedEnd || in.token == EOF || in.token == RPAREN
    def mustStartStat = mustStartStatTokens contains in.token

    /** Is current token a hard or soft modifier (in modifier position or not)? */
    def isModifier: Boolean = modifierTokens.contains(in.token) || in.isSoftModifier

    def isBindingIntro: Boolean = {
      in.token match {
        case USCORE => true
        case IDENTIFIER | BACKQUOTED_IDENT => in.lookaheadIn(BitSet(ARROW, CTXARROW))
        case LPAREN =>
          val lookahead = in.LookaheadScanner()
          lookahead.skipParens()
          lookahead.token == ARROW || lookahead.token == CTXARROW
        case _ => false
      }
    } && !in.isSoftModifierInModifierPosition

    def isExprIntro: Boolean =
      in.canStartExprTokens.contains(in.token)
      && !in.isSoftModifierInModifierPosition
      && !(isIdent(nme.extension) && followingIsExtension())

    def isDefIntro(allowedMods: BitSet, excludedSoftModifiers: Set[TermName] = Set.empty): Boolean =
      in.token == AT
      || defIntroTokens.contains(in.token)
      || allowedMods.contains(in.token)
      || in.isSoftModifierInModifierPosition && !excludedSoftModifiers.contains(in.name)
      || isIdent(nme.extension) && followingIsExtension()

    def isStatSep: Boolean = in.isNewLine || in.token == SEMI

    /** A '$' identifier is treated as a splice if followed by a `{`.
     *  A longer identifier starting with `$` is treated as a splice/id combination
     *  in a quoted block '{...'
     */
    def isSplice: Boolean =
      in.token == IDENTIFIER && in.name(0) == '$' && {
        if (in.name.length == 1) in.lookaheadIn(BitSet(LBRACE))
        else (staged & StageKind.Quoted) != 0
      }

/* ------------- ERROR HANDLING ------------------------------------------- */

    /** The offset of the last time when a statement on a new line was definitely
     *  encountered in the current scope or an outer scope.
     */
    private var lastStatOffset = -1

    def setLastStatOffset(): Unit =
      if (mustStartStat && in.isAfterLineEnd)
        lastStatOffset = in.offset

    /** Is offset1 less or equally indented than offset2?
     *  This is the case if the characters between the preceding end-of-line and offset1
     *  are a prefix of the characters between the preceding end-of-line and offset2.
     */
    def isLeqIndented(offset1: Int, offset2: Int): Boolean = {
      def recur(idx1: Int, idx2: Int): Boolean =
        idx1 == offset1 ||
        idx2 < offset2 && source(idx1) == source(idx2) && recur(idx1 + 1, idx2 + 1)
      recur(source.startOfLine(offset1), source.startOfLine(offset2))
    }

    /** Skip on error to next safe point.
     *  Safe points are:
     *   - Closing braces, provided they match an opening brace before the error point.
     *   - Closing parens and brackets, provided they match an opening parent or bracket
     *     before the error point and there are no intervening other kinds of parens.
     *   - Semicolons and newlines, provided there are no intervening braces.
     *   - Definite statement starts on new lines, provided they are not more indented
     *     than the last known statement start before the error point.
     */
    protected def skip(): Unit = {
      val skippedParens = new ParensCounters
      while (true) {
        (in.token: @switch) match {
          case EOF =>
            return
          case SEMI | NEWLINE | NEWLINES =>
            if (skippedParens.count(LBRACE) == 0) return
          case RBRACE =>
            if (openParens.count(LBRACE) > 0 && skippedParens.count(LBRACE) == 0)
              return
            skippedParens.change(LBRACE, -1)
          case RPAREN =>
            if (openParens.count(LPAREN) > 0 && skippedParens.nonePositive)
              return
            skippedParens.change(LPAREN, -1)
          case RBRACKET =>
            if (openParens.count(LBRACKET) > 0 && skippedParens.nonePositive)
              return
            skippedParens.change(LBRACKET, -1)
          case OUTDENT =>
            if (openParens.count(INDENT) > 0 && skippedParens.count(INDENT) == 0)
              return
            skippedParens.change(INDENT, -1)
          case LBRACE =>
            skippedParens.change(LBRACE, +1)
          case LPAREN =>
            skippedParens.change(LPAREN, +1)
          case LBRACKET=>
            skippedParens.change(LBRACKET, +1)
          case INDENT =>
            skippedParens.change(INDENT, +1)
          case _ =>
            if (mustStartStat &&
                in.isAfterLineEnd &&
                isLeqIndented(in.offset, lastStatOffset max 0))
              return
        }
        in.nextToken()
      }
    }

    def warning(msg: Message, sourcePos: SourcePosition): Unit =
      ctx.warning(msg, sourcePos)

    def warning(msg: Message, offset: Int = in.offset): Unit =
      ctx.warning(msg, source.atSpan(Span(offset)))

    def deprecationWarning(msg: Message, offset: Int = in.offset): Unit =
      ctx.deprecationWarning(msg, source.atSpan(Span(offset)))

    /** Issue an error at current offset that input is incomplete */
    def incompleteInputError(msg: Message): Unit =
      ctx.incompleteInputError(msg, source.atSpan(Span(in.offset)))

    /** If at end of file, issue an incompleteInputError.
     *  Otherwise issue a syntax error and skip to next safe point.
     */
    def syntaxErrorOrIncomplete(msg: Message, offset: Int = in.offset): Unit =
      if (in.token == EOF) incompleteInputError(msg)
      else {
        syntaxError(msg, offset)
        skip()
        lastErrorOffset = in.offset
      }

    /** Consume one token of the specified type, or
      * signal an error if it is not there.
      *
      * @return The offset at the start of the token to accept
      */
    def accept(token: Int): Int = {
      val offset = in.offset
      if (in.token != token)
        syntaxErrorOrIncomplete(ExpectedTokenButFound(token, in.token))
      if (in.token == token) in.nextToken()
      offset
    }

    def accept(name: Name): Int = {
      val offset = in.offset
      if !isIdent(name) then
        syntaxErrorOrIncomplete(em"`$name` expected")
      if isIdent(name) then
        in.nextToken()
      offset
    }

    def reportMissing(expected: Token): Unit =
      syntaxError(ExpectedTokenButFound(expected, in.token))

    /** semi = nl {nl} | `;'
     *  nl  = `\n' // where allowed
     */
    def acceptStatSep(): Unit =
      if in.isNewLine then in.nextToken() else accept(SEMI)

    def acceptStatSepUnlessAtEnd(altEnd: Token = EOF): Unit =
      in.observeOutdented()
      if (!isStatSeqEnd)
        in.token match {
          case EOF =>
          case `altEnd` =>
          case NEWLINE | NEWLINES => in.nextToken()
          case SEMI => in.nextToken()
          case _ =>
            syntaxError(i"end of statement expected but ${showToken(in.token)} found")
            in.nextToken() // needed to ensure progress; otherwise we might cycle forever
            accept(SEMI)
        }

    def rewriteNotice(additionalOption: String = "") = {
      val optionStr = if (additionalOption.isEmpty) "" else " " ++ additionalOption
      i"\nThis construct can be rewritten automatically under$optionStr -rewrite."
    }

    def syntaxVersionError(option: String, span: Span) =
      syntaxError(em"""This construct is not allowed under $option.${rewriteNotice(option)}""", span)

    def rewriteToNewSyntax(span: Span = Span(in.offset)): Boolean = {
      if (in.newSyntax) {
        if (in.rewrite) return true
        syntaxVersionError("-new-syntax", span)
      }
      false
    }

    def rewriteToOldSyntax(span: Span = Span(in.offset)): Boolean = {
      if (in.oldSyntax) {
        if (in.rewrite) return true
        syntaxVersionError("-old-syntax", span)
      }
      false
    }

    def errorTermTree: Literal = atSpan(in.offset) { Literal(Constant(null)) }

    private var inFunReturnType = false
    private def fromWithinReturnType[T](body: => T): T = {
      val saved = inFunReturnType
      try {
        inFunReturnType = true
        body
      }
      finally inFunReturnType = saved
    }

    /** A flag indicating we are parsing in the annotations of a primary
     *  class constructor
     */
    private var inClassConstrAnnots = false
    private def fromWithinClassConstr[T](body: => T): T = {
      val saved = inClassConstrAnnots
      inClassConstrAnnots = true
      try body
      finally inClassConstrAnnots = saved
    }

    private var inEnum = false
    private def withinEnum[T](body: => T): T = {
      val saved = inEnum
      inEnum = true
      try body
      finally inEnum = saved
    }

    private var staged = StageKind.None
    def withinStaged[T](kind: StageKind)(op: => T): T = {
      val saved = staged
      staged |= kind
      try op
      finally staged = saved
    }

/* ---------- TREE CONSTRUCTION ------------------------------------------- */

    /** Convert tree to formal parameter list
    */
    def convertToParams(tree: Tree, mods: Modifiers): List[ValDef] = tree match {
      case Parens(t) =>
        convertToParam(t, mods) :: Nil
      case Tuple(ts) =>
        ts.map(convertToParam(_, mods))
      case t: Typed =>
        ctx.errorOrMigrationWarning(
          em"parentheses are required around the parameter of a lambda${rewriteNotice()}",
          in.sourcePos())
        if migrateTo3 then
          patch(source, t.span.startPos, "(")
          patch(source, t.span.endPos, ")")
        convertToParam(t, mods) :: Nil
      case t =>
        convertToParam(t, mods) :: Nil
    }

    /** Convert tree to formal parameter
    */
    def convertToParam(tree: Tree, mods: Modifiers, expected: String = "formal parameter"): ValDef = tree match {
      case id @ Ident(name) =>
        makeParameter(name.asTermName, TypeTree(), mods, isBackquoted = isBackquoted(id)).withSpan(tree.span)
      case Typed(id @ Ident(name), tpt) =>
        makeParameter(name.asTermName, tpt, mods, isBackquoted = isBackquoted(id)).withSpan(tree.span)
      case Typed(Splice(Ident(name)), tpt) =>
        makeParameter(("$" + name).toTermName, tpt, mods).withSpan(tree.span)
      case _ =>
        syntaxError(s"not a legal $expected", tree.span)
        makeParameter(nme.ERROR, tree, mods)
    }

    /** Convert (qual)ident to type identifier
     */
    def convertToTypeId(tree: Tree): Tree = tree match {
      case id @ Ident(name) =>
        cpy.Ident(id)(name.toTypeName)
      case id @ Select(qual, name) =>
        cpy.Select(id)(qual, name.toTypeName)
      case _ =>
        syntaxError(IdentifierExpected(tree.show), tree.span)
        tree
    }

/* --------------- PLACEHOLDERS ------------------------------------------- */

    /** The implicit parameters introduced by `_` in the current expression.
     *  Parameters appear in reverse order.
     */
    var placeholderParams: List[ValDef] = Nil

    def checkNoEscapingPlaceholders[T](op: => T): T = {
      val savedPlaceholderParams = placeholderParams
      placeholderParams = Nil

      try op
      finally {
        placeholderParams match {
          case vd :: _ => syntaxError(UnboundPlaceholderParameter(), vd.span)
          case _ =>
        }
        placeholderParams = savedPlaceholderParams
      }
    }

    def isWildcard(t: Tree): Boolean = t match {
      case Ident(name1) => placeholderParams.nonEmpty && name1 == placeholderParams.head.name
      case Typed(t1, _) => isWildcard(t1)
      case Annotated(t1, _) => isWildcard(t1)
      case Parens(t1) => isWildcard(t1)
      case _ => false
    }

    def isWildcardType(t: Tree): Boolean = t match {
      case t: TypeBoundsTree => true
      case Parens(t1) => isWildcardType(t1)
      case _ => false
    }

    def rejectWildcardType(t: Tree, fallbackTree: Tree = scalaAny): Tree =
      if (isWildcardType(t)) {
        syntaxError(UnboundWildcardType(), t.span)
        fallbackTree
      }
      else t

/* -------------- XML ---------------------------------------------------- */

    /** The markup parser.
     *  The first time this lazy val is accessed, we assume we were trying to parse an XML literal.
     *  The current position is recorded for later error reporting if it turns out
     *  that we don't have scala-xml on the compilation classpath.
     */
    lazy val xmlp: xml.MarkupParsers.MarkupParser = {
      myFirstXmlPos = source.atSpan(Span(in.offset))
      new MarkupParser(this, true)
    }

    /** The position of the first XML literal encountered while parsing,
     *  NoSourcePosition if there were no XML literals.
     */
    def firstXmlPos: SourcePosition = myFirstXmlPos
    private var myFirstXmlPos: SourcePosition = NoSourcePosition

    object symbXMLBuilder extends xml.SymbolicXMLBuilder(this, true) // DEBUG choices

    def xmlLiteral() : Tree = xmlp.xLiteral
    def xmlLiteralPattern() : Tree = xmlp.xLiteralPattern

/* -------- COMBINATORS -------------------------------------------------------- */

    def enclosed[T](tok: Token, body: => T): T = {
      accept(tok)
      openParens.change(tok, 1)
      try body
      finally {
        accept(tok + 1)
        openParens.change(tok, -1)
      }
    }

    def inParens[T](body: => T): T = enclosed(LPAREN, body)
    def inBraces[T](body: => T): T = enclosed(LBRACE, body)
    def inBrackets[T](body: => T): T = enclosed(LBRACKET, body)

    def inBracesOrIndented[T](body: => T): T =
      if (in.token == INDENT) {
        val rewriteToBraces =
          in.rewriteNoIndent &&
          !testChars(in.lastOffset - 3, " =>") // braces are always optional after `=>` so none should be inserted
        if (rewriteToBraces) indentedToBraces(body)
        else enclosed(INDENT, body)
      }
      else
        if (in.rewriteToIndent) bracesToIndented(body)
        else inBraces(body)

    def inDefScopeBraces[T](body: => T): T = {
      val saved = lastStatOffset
      try inBracesOrIndented(body)
      finally lastStatOffset = saved
    }

    /** part { `separator` part }
     */
    def tokenSeparated[T](separator: Int, part: () => T): List[T] = {
      val ts = new ListBuffer[T] += part()
      while (in.token == separator) {
        in.nextToken()
        ts += part()
      }
      ts.toList
    }

    def commaSeparated[T](part: () => T): List[T] = tokenSeparated(COMMA, part)

    def inSepRegion[T](f: Region => Region)(op: => T): T =
      val cur = in.currentRegion
      in.currentRegion = f(cur)
      try op finally in.currentRegion = cur

    /** Parse `body` while checking (under -noindent) that a `{` is not missing before it.
     *  This is done as follows:
     *    If the next token S is indented relative to the current region,
     *    and the end of `body` is followed by a new line and another statement,
     *    check that that other statement is indented less than S
     */
    def subPart[T](body: () => T): T = in.currentRegion match
      case r: InBraces if in.isAfterLineEnd =>
        val startIndentWidth = in.indentWidth(in.offset)
        if r.indentWidth < startIndentWidth then
          // Note: we can get here only if indentation is not significant
          // If indentation is significant, we would see an <indent> as current token
          // and the indent region would be Indented instead of InBraces.
          //
          // If indentation would be significant, an <indent> would be inserted here.
          val t = body()
          // Therefore, make sure there would be a matching <outdent>
          def nextIndentWidth = in.indentWidth(in.next.offset)
          if in.isNewLine && !(nextIndentWidth < startIndentWidth) then
            warning(
              if startIndentWidth <= nextIndentWidth then
                i"""Line is indented too far to the right, or a `{` is missing before:
                   |
                   |$t"""
              else
                in.spaceTabMismatchMsg(startIndentWidth, nextIndentWidth),
              in.next.offset
            )
          t
        else body()
      case _ => body()

    /** Check that this is not the start of a statement that's indented relative to the current region.
     */
    def checkNextNotIndented(): Unit =
      if in.isNewLine then
        val nextIndentWidth = in.indentWidth(in.next.offset)
        if in.currentRegion.indentWidth < nextIndentWidth then
          warning(i"Line is indented too far to the right, or a `{` or `:` is missing", in.next.offset)

/* -------- REWRITES ----------------------------------------------------------- */

    /** The last offset where a colon at the end of line would be required if a subsequent { ... }
     *  block would be converted to an indentation region.
     */
    var possibleColonOffset: Int = -1

    def testChar(idx: Int, p: Char => Boolean): Boolean = {
      val txt = source.content
      idx < txt.length && p(txt(idx))
    }

    def testChar(idx: Int, c: Char): Boolean = {
      val txt = source.content
      idx < txt.length && txt(idx) == c
    }

    def testChars(from: Int, str: String): Boolean =
      str.isEmpty ||
      testChar(from, str.head) && testChars(from + 1, str.tail)

    def skipBlanks(idx: Int, step: Int = 1): Int =
      if (testChar(idx, c => c == ' ' || c == '\t' || c == Chars.CR)) skipBlanks(idx + step, step)
      else idx

    /** Parse indentation region `body` and rewrite it to be in braces instead */
    def indentedToBraces[T](body: => T): T = {
      val enclRegion = in.currentRegion.enclosing
      def indentWidth = enclRegion.indentWidth
      val followsColon = testChar(in.lastOffset - 1, ':')
      val startOpening =
        if (followsColon)
          if (testChar(in.lastOffset - 2, ' ')) in.lastOffset - 2
          else in.lastOffset - 1
        else in.lastOffset
      val endOpening = in.lastOffset

      val t = enclosed(INDENT, body)

      /** Is `expr` a tree that lacks a final `else`? Put such trees in `{...}` to make
       *  sure we don't accidentally merge them with a following `else`.
       */
      def isPartialIf(expr: Tree): Boolean = expr match {
        case If(_, _, EmptyTree) => true
        case If(_, _, e) => isPartialIf(e)
        case _ => false
      }

      /** Is `expr` a (possibly curried) function that has a multi-statement block
       *  as body? Put such trees in `{...}` since we don't enclose statements following
       *  a `=>` in braces.
       */
      def isBlockFunction[T](expr: T): Boolean = expr match {
        case Function(_, body) => isBlockFunction(body)
        case Block(stats, expr) => stats.nonEmpty || isBlockFunction(expr)
        case _ => false
      }

      /** Start of first line after in.lastOffset that does not have a comment
       *  at indent width greater than the indent width of the closing brace.
       */
      def closingOffset(lineStart: Offset): Offset =
        if (in.lineOffset >= 0 && lineStart >= in.lineOffset) in.lineOffset
        else {
          val candidate = source.nextLine(lineStart)
          val commentStart = skipBlanks(lineStart)
          if (testChar(commentStart, '/') && indentWidth < in.indentWidth(commentStart))
            closingOffset(source.nextLine(lineStart))
          else
            lineStart
        }

      def needsBraces(t: Any): Boolean = t match {
        case Match(EmptyTree, _) => true
        case Block(stats, expr) =>
          stats.nonEmpty || needsBraces(expr)
        case expr: Tree =>
          followsColon ||
          isPartialIf(expr) && in.token == ELSE ||
          isBlockFunction(expr)
        case _ => true
      }
      if (needsBraces(t)) {
        patch(source, Span(startOpening, endOpening), " {")
        patch(source, Span(closingOffset(source.nextLine(in.lastOffset))), indentWidth.toPrefix ++ "}\n")
      }
      t
    }

    /** The region to eliminate when replacing an opening `(` or `{` that ends a line.
     *  The `(` or `{` is at in.offset.
     */
    def startingElimRegion(colonRequired: Boolean): (Offset, Offset) = {
      val skipped = skipBlanks(in.offset + 1)
      if (in.isAfterLineEnd)
        if (testChar(skipped, Chars.LF) && !colonRequired)
          (in.lineOffset, skipped + 1) // skip the whole line
        else
          (in.offset, skipped)
      else if (testChar(in.offset - 1, ' ')) (in.offset - 1, in.offset + 1)
      else (in.offset, in.offset + 1)
    }

    /** The region to eliminate when replacing a closing `)` or `}` that starts a new line
     *  The `)` or `}` precedes in.lastOffset.
     */
    def closingElimRegion(): (Offset, Offset) = {
      val skipped = skipBlanks(in.lastOffset)
      if (testChar(skipped, Chars.LF))                    // if `)` or `}` is on a line by itself
        (source.startOfLine(in.lastOffset), skipped + 1)  //   skip the whole line
      else                                                // else
        (in.lastOffset - 1, skipped)                      //   move the following text up to where the `)` or `}` was
    }

    /** Parse brace-enclosed `body` and rewrite it to be an indentation region instead, if possible.
     *  If possible means:
     *   1. not inside (...), [...], case ... =>
     *   2. opening brace `{` is at end of line
     *   3. closing brace `}` is at start of line
     *   4. there is at least one token between the braces
     *   5. the closing brace is also at the end of the line, or it is followed by one of
     *      `then`, `else`, `do`, `catch`, `finally`, `yield`, or `match`.
     *   6. the opening brace does not follow a `=>`. The reason for this condition is that
     *      rewriting back to braces does not work after `=>` (since in most cases braces are omitted
     *      after a `=>` it would be annoying if braces were inserted).
     */
    def bracesToIndented[T](body: => T): T = {
      val colonRequired = possibleColonOffset == in.lastOffset
      val (startOpening, endOpening) = startingElimRegion(colonRequired)
      val isOutermost = in.currentRegion.isOutermost
      def allBraces(r: Region): Boolean = r match {
        case r: Indented => r.isOutermost || allBraces(r.enclosing)
        case r: InBraces => allBraces(r.enclosing)
        case _ => false
      }
      var canRewrite = allBraces(in.currentRegion) && // test (1)
        !testChars(in.lastOffset - 3, " =>") // test(6)
      val t = enclosed(LBRACE, {
        canRewrite &= in.isAfterLineEnd // test (2)
        val curOffset = in.offset
        try body
        finally {
          canRewrite &= in.isAfterLineEnd && in.offset != curOffset // test (3)(4)
        }
      })
      canRewrite &= (in.isAfterLineEnd || statCtdTokens.contains(in.token)) // test (5)
      if (canRewrite && (!colonRequired || in.colonSyntax)) {
        val openingPatchStr =
          if (!colonRequired) ""
          else if (testChar(startOpening - 1, Chars.isOperatorPart(_))) " :"
          else ":"
        val (startClosing, endClosing) = closingElimRegion()
        patch(source, Span(startOpening, endOpening), openingPatchStr)
        patch(source, Span(startClosing, endClosing), "")
      }
      t
    }

    /** Drop (...) or { ... }, replacing the closing element with `endStr` */
    def dropParensOrBraces(start: Offset, endStr: String): Unit = {
      if (testChar(start + 1, Chars.isLineBreakChar))
        patch(source, Span(if (testChar(start - 1, ' ')) start - 1 else start, start + 1), "")
      else
        patch(source, Span(start, start + 1),
          if (testChar(start - 1, Chars.isIdentifierPart)) " " else "")
      val closingStartsLine = testChar(skipBlanks(in.lastOffset - 2, -1), Chars.LF)
      val preFill = if (closingStartsLine || endStr.isEmpty) "" else " "
      val postFill = if (in.lastOffset == in.offset) " " else ""
      val (startClosing, endClosing) =
        if (closingStartsLine && endStr.isEmpty) closingElimRegion()
        else (in.lastOffset - 1, in.lastOffset)
      patch(source, Span(startClosing, endClosing), s"$preFill$endStr$postFill")
    }

    /** If all other characters on the same line as `span` are blanks, widen to
     *  the whole line.
     */
    def widenIfWholeLine(span: Span): Span = {
      val start = skipBlanks(span.start - 1, -1)
      val end = skipBlanks(span.end, 1)
      if (testChar(start, Chars.LF) && testChar(end, Chars.LF)) Span(start, end)
      else span
    }

    /** Drop current token, if it is a `then` or `do`. */
    def dropTerminator(): Unit =
      if in.token == THEN || in.token == DO then
        var startOffset = in.offset
        var endOffset = in.lastCharOffset
        if (in.isAfterLineEnd) {
          if (testChar(endOffset, ' '))
            endOffset += 1
        }
        else
          if (testChar(startOffset - 1, ' ') &&
              !overlapsPatch(source, Span(startOffset - 1, endOffset)))
            startOffset -= 1
        patch(source, widenIfWholeLine(Span(startOffset, endOffset)), "")

    /** rewrite code with (...) around the source code of `t` */
    def revertToParens(t: Tree): Unit =
      if (t.span.exists) {
        patch(source, t.span.startPos, "(")
        patch(source, t.span.endPos, ")")
        dropTerminator()
      }

/* --------- LOOKAHEAD --------------------------------------- */

    /** In the tokens following the current one, does `query` precede any of the tokens that
     *   - must start a statement, or
     *   - separate two statements, or
     *   - continue a statement (e.g. `else`, catch`), or
     *   - terminate the current scope?
     */
    def followedByToken(query: Token): Boolean = {
      val lookahead = in.LookaheadScanner()
      var braces = 0
      while (true) {
        val token = lookahead.token
        if (braces == 0) {
          if (token == query) return true
          if (stopScanTokens.contains(token) || lookahead.isNestedEnd) return false
        }
        else if (token == EOF)
          return false
        else if (lookahead.isNestedEnd)
          braces -= 1
        if (lookahead.isNestedStart) braces += 1
        lookahead.nextToken()
      }
      false
    }

    /** Is the following sequence the generators of a for-expression enclosed in (...)? */
    def followingIsEnclosedGenerators(): Boolean = {
      val lookahead = in.LookaheadScanner()
      var parens = 1
      lookahead.nextToken()
      while (parens != 0 && lookahead.token != EOF) {
        val token = lookahead.token
        if (token == LPAREN) parens += 1
        else if (token == RPAREN) parens -= 1
        lookahead.nextToken()
      }
      if (lookahead.token == LARROW)
        false // it's a pattern
      else if (lookahead.isIdent)
        true // it's not a pattern since token cannot be an infix operator
      else
        followedByToken(LARROW) // `<-` comes before possible statement starts
    }

    /** Are the next token the "GivenSig" part of a given definition,
     *  i.e. an identifier followed by type and value parameters, followed by `:`?
     *  @pre  The current token is an identifier
     */
    def followingIsGivenSig() =
      val lookahead = in.LookaheadScanner()
      if lookahead.isIdent then
        lookahead.nextToken()
      def skipParams(): Unit =
        if lookahead.token == LPAREN || lookahead.token == LBRACKET then
          lookahead.skipParens()
          skipParams()
        else if lookahead.isNewLine then
          lookahead.nextToken()
          skipParams()
      skipParams()
      lookahead.isIdent(nme.as)

    def followingIsExtension() =
      val lookahead = in.LookaheadScanner()
      lookahead.nextToken()
      if lookahead.isIdent && !lookahead.isIdent(nme.on) then
        lookahead.nextToken()
      if lookahead.isNewLine then
        lookahead.nextToken()
      lookahead.isIdent(nme.on)
      || lookahead.token == LBRACE
      || lookahead.token == COLON

/* --------- OPERAND/OPERATOR STACK --------------------------------------- */

    var opStack: List[OpInfo] = Nil

    def checkAssoc(offset: Token, op1: Name, op2: Name, op2LeftAssoc: Boolean): Unit =
      if (isLeftAssoc(op1) != op2LeftAssoc)
        syntaxError(MixedLeftAndRightAssociativeOps(op1, op2, op2LeftAssoc), offset)

    def reduceStack(base: List[OpInfo], top: Tree, prec: Int, leftAssoc: Boolean, op2: Name, isType: Boolean): Tree = {
      if (opStack != base && precedence(opStack.head.operator.name) == prec)
        checkAssoc(opStack.head.offset, opStack.head.operator.name, op2, leftAssoc)
      def recur(top: Tree): Tree =
        if (opStack == base) top
        else {
          val opInfo = opStack.head
          val opPrec = precedence(opInfo.operator.name)
          if (prec < opPrec || leftAssoc && prec == opPrec) {
            opStack = opStack.tail
            recur {
              atSpan(opInfo.operator.span union opInfo.operand.span union top.span) {
                InfixOp(opInfo.operand, opInfo.operator, top)
              }
            }
          }
          else top
        }
      recur(top)
    }

    /**   operand { infixop operand | MatchClause } [postfixop],
     *
     *  respecting rules of associativity and precedence.
     *  @param isOperator    the current token counts as an operator.
     *  @param maybePostfix  postfix operators are allowed.
     */
    def infixOps(
        first: Tree, canStartOperand: Token => Boolean, operand: () => Tree,
        isType: Boolean = false,
        isOperator: => Boolean = true,
        maybePostfix: Boolean = false): Tree = {
      val base = opStack

      def recur(top: Tree): Tree =
        if (isIdent && isOperator) {
          val op = if (isType) typeIdent() else termIdent()
          val top1 = reduceStack(base, top, precedence(op.name), isLeftAssoc(op.name), op.name, isType)
          opStack = OpInfo(top1, op, in.offset) :: opStack
          colonAtEOLOpt()
          newLineOptWhenFollowing(canStartOperand)
          if (maybePostfix && !canStartOperand(in.token)) {
            val topInfo = opStack.head
            opStack = opStack.tail
            val od = reduceStack(base, topInfo.operand, 0, true, in.name, isType)
            atSpan(startOffset(od), topInfo.offset) {
              PostfixOp(od, topInfo.operator)
            }
          }
          else recur(operand())
        }
        else
          val t = reduceStack(base, top, minPrec, leftAssoc = true, in.name, isType)
          if !isType && in.token == MATCH then recurAtMinPrec(matchClause(t))
          else t

      def recurAtMinPrec(top: Tree): Tree =
        if isIdent && isOperator && precedence(in.name) == minInfixPrec
           || in.token == MATCH
        then recur(top)
        else top

      recur(first)
    }

/* -------- IDENTIFIERS AND LITERALS ------------------------------------------- */

    /** Accept identifier and return its name as a term name. */
    def ident(): TermName =
      if (isIdent) {
        val name = in.name
        in.nextToken()
        name
      }
      else {
        syntaxErrorOrIncomplete(ExpectedTokenButFound(IDENTIFIER, in.token))
        nme.ERROR
      }

    /** Accept identifier and return Ident with its name as a term name. */
    def termIdent(): Ident =
      makeIdent(in.token, in.offset, ident())

    /** Accept identifier and return Ident with its name as a type name. */
    def typeIdent(): Ident =
      makeIdent(in.token, in.offset, ident().toTypeName)

    private def makeIdent(tok: Token, offset: Offset, name: Name) = {
      val tree = Ident(name)
      if (tok == BACKQUOTED_IDENT) tree.pushAttachment(Backquoted, ())

      // Make sure that even trees with parsing errors have a offset that is within the offset
      val errorOffset = offset min (in.lastOffset - 1)
      if (tree.name == nme.ERROR && tree.span == NoSpan) tree.withSpan(Span(errorOffset, errorOffset))
      else atSpan(offset)(tree)
    }

    def wildcardIdent(): Ident =
      atSpan(accept(USCORE)) { Ident(nme.WILDCARD) }

    /** Accept identifier or match clause acting as a selector on given tree `t` */
    def selector(t: Tree): Tree =
      atSpan(startOffset(t), in.offset) {
        if in.token == MATCH then matchClause(t) else Select(t, ident())
      }

    /** Selectors ::= id { `.' id }
     *
     *  Accept `.' separated identifiers acting as a selectors on given tree `t`.
     *  @param finish   An alternative parse in case the next token is not an identifier.
     *                  If the alternative does not apply, its tree argument is returned unchanged.
     */
    def selectors(t: Tree, finish: Tree => Tree): Tree = {
      val t1 = finish(t)
      if (t1 ne t) t1 else dotSelectors(selector(t), finish)
    }

    /** DotSelectors ::= { `.' id }
     *
     *  Accept `.' separated identifiers acting as a selectors on given tree `t`.
     *  @param finish   An alternative parse in case the token following a `.' is not an identifier.
     *                  If the alternative does not apply, its tree argument is returned unchanged.
     */
    def dotSelectors(t: Tree, finish: Tree => Tree = id): Tree =
      if (in.token == DOT) { in.nextToken(); selectors(t, finish) }
      else t

    private val id: Tree => Tree = x => x

    /** Path       ::= StableId
     *              |  [id `.'] this
     *
     *  @param thisOK   If true, the path can end with the keyword `this`.
     *                  If false, another selection is required after the `this`.
     *  @param finish   An alternative parse in case the token following a `.' is not an identifier.
     *                  If the alternative does not apply, its tree argument is returned unchanged.
     */
    def path(thisOK: Boolean, finish: Tree => Tree = id): Tree = {
      val start = in.offset
      def handleThis(qual: Ident) = {
        in.nextToken()
        val t = atSpan(start) { This(qual) }
        if (!thisOK && in.token != DOT) syntaxError(DanglingThisInPath(), t.span)
        dotSelectors(t, finish)
      }
      def handleSuper(qual: Ident) = {
        in.nextToken()
        val mix = mixinQualifierOpt()
        val t = atSpan(start) { Super(This(qual), mix) }
        accept(DOT)
        dotSelectors(selector(t), finish)
      }
      if (in.token == THIS) handleThis(EmptyTypeIdent)
      else if (in.token == SUPER) handleSuper(EmptyTypeIdent)
      else {
        val t = termIdent()
        if (in.token == DOT) {
          def qual = cpy.Ident(t)(t.name.toTypeName)
          in.nextToken()
          if (in.token == THIS) handleThis(qual)
          else if (in.token == SUPER) handleSuper(qual)
          else selectors(t, finish)
        }
        else t
      }
    }

    /** MixinQualifier ::= `[' id `]'
    */
    def mixinQualifierOpt(): Ident =
      if (in.token == LBRACKET) inBrackets(atSpan(in.offset) { typeIdent() })
      else EmptyTypeIdent

    /** StableId ::= id
     *            |  Path `.' id
     *            |  [id '.'] super [`[' id `]']`.' id
     */
    def stableId(): Tree =
      path(thisOK = false)

    /** QualId ::= id {`.' id}
    */
    def qualId(): Tree = dotSelectors(termIdent())

    /** SimpleExpr    ::= literal
     *                  | 'id | 'this | 'true | 'false | 'null
     *                  | null
     *  @param negOffset   The offset of a preceding `-' sign, if any.
     *                     If the literal is not negated, negOffset = in.offset.
     */
    def literal(negOffset: Int = in.offset, inPattern: Boolean = false, inType: Boolean = false, inStringInterpolation: Boolean = false): Tree = {
      def literalOf(token: Token): Tree = {
        val isNegated = negOffset < in.offset
        def digits0 = in.removeNumberSeparators(in.strVal)
        def digits = if (isNegated) "-" + digits0 else digits0
        if (!inType)
          token match {
            case INTLIT  => return Number(digits, NumberKind.Whole(in.base))
            case DECILIT => return Number(digits, NumberKind.Decimal)
            case EXPOLIT => return Number(digits, NumberKind.Floating)
            case _ =>
          }
        import scala.util.FromDigits._
        val value =
          try token match {
            case INTLIT                        => intFromDigits(digits, in.base)
            case LONGLIT                       => longFromDigits(digits, in.base)
            case FLOATLIT                      => floatFromDigits(digits)
            case DOUBLELIT | DECILIT | EXPOLIT => doubleFromDigits(digits)
            case CHARLIT                       => in.strVal.head
            case STRINGLIT | STRINGPART        => in.strVal
            case TRUE                          => true
            case FALSE                         => false
            case NULL                          => null
            case _                             =>
              syntaxErrorOrIncomplete(IllegalLiteral())
              null
          }
          catch {
            case ex: FromDigitsException => syntaxErrorOrIncomplete(ex.getMessage)
          }
        Literal(Constant(value))
      }

      if (inStringInterpolation) {
        val t = in.token match {
          case STRINGLIT | STRINGPART =>
            val value = in.strVal
            atSpan(negOffset, negOffset, negOffset + value.length) { Literal(Constant(value)) }
          case _ =>
            syntaxErrorOrIncomplete(IllegalLiteral())
            atSpan(negOffset) { Literal(Constant(null)) }
        }
        in.nextToken()
        t
      }
      else atSpan(negOffset) {
        if (in.token == QUOTEID)
          if ((staged & StageKind.Spliced) != 0 && Chars.isIdentifierStart(in.name(0))) {
            val t = atSpan(in.offset + 1) {
              val tok = in.toToken(in.name)
              tok match {
                case TRUE | FALSE | NULL => literalOf(tok)
                case THIS => This(EmptyTypeIdent)
                case _ => Ident(in.name)
              }
            }
            in.nextToken()
            Quote(t)
          }
          else {
            ctx.errorOrMigrationWarning(
              em"""symbol literal '${in.name} is no longer supported,
                  |use a string literal "${in.name}" or an application Symbol("${in.name}") instead,
                  |or enclose in braces '{${in.name}} if you want a quoted expression.""",
              in.sourcePos())
            if migrateTo3 then
              patch(source, Span(in.offset, in.offset + 1), "Symbol(\"")
              patch(source, Span(in.charOffset - 1), "\")")
            atSpan(in.skipToken()) { SymbolLit(in.strVal) }
          }
        else if (in.token == INTERPOLATIONID) interpolatedString(inPattern)
        else {
          val t = literalOf(in.token)
          in.nextToken()
          t
        }
      }
    }

    private def interpolatedString(inPattern: Boolean = false): Tree = atSpan(in.offset) {
      val segmentBuf = new ListBuffer[Tree]
      val interpolator = in.name
      val isTripleQuoted =
        in.charOffset + 1 < in.buf.length &&
        in.buf(in.charOffset) == '"' &&
        in.buf(in.charOffset + 1) == '"'
      in.nextToken()
      def nextSegment(literalOffset: Offset) =
        segmentBuf += Thicket(
            literal(literalOffset, inPattern = inPattern, inStringInterpolation = true),
            atSpan(in.offset) {
              if (in.token == IDENTIFIER)
                termIdent()
              else if (in.token == USCORE && inPattern) {
                in.nextToken()
                Ident(nme.WILDCARD)
              }
              else if (in.token == THIS) {
                in.nextToken()
                This(EmptyTypeIdent)
              }
              else if (in.token == LBRACE)
                if (inPattern) Block(Nil, inBraces(pattern()))
                else expr()
              else {
                ctx.error(InterpolatedStringError(), source.atSpan(Span(in.offset)))
                EmptyTree
              }
            })

      var offsetCorrection = if isTripleQuoted then 3 else 1
      while (in.token == STRINGPART)
        nextSegment(in.offset + offsetCorrection)
        offsetCorrection = 0
      if (in.token == STRINGLIT)
        segmentBuf += literal(inPattern = inPattern, negOffset = in.offset + offsetCorrection, inStringInterpolation = true)

      InterpolatedString(interpolator, segmentBuf.toList)
    }

/* ------------- NEW LINES ------------------------------------------------- */

    def newLineOpt(): Unit =
      if (in.token == NEWLINE) in.nextToken()

    def newLinesOpt(): Unit =
      if in.isNewLine then in.nextToken()

    def newLineOptWhenFollowedBy(token: Int): Unit =
      // note: next is defined here because current == NEWLINE
      if (in.token == NEWLINE && in.next.token == token) in.nextToken()

    def newLinesOptWhenFollowedBy(token: Int): Unit =
      if in.isNewLine && in.next.token == token then in.nextToken()

    def newLinesOptWhenFollowedBy(name: Name): Unit =
      if in.isNewLine && in.next.token == IDENTIFIER && in.next.name == name then
        in.nextToken()

    def newLineOptWhenFollowing(p: Int => Boolean): Unit =
      // note: next is defined here because current == NEWLINE
      if (in.token == NEWLINE && p(in.next.token)) newLineOpt()

    def colonAtEOLOpt(): Unit = {
      possibleColonOffset = in.lastOffset
      if (in.token == COLONEOL) in.nextToken()
    }

    def possibleBracesStart(): Unit = {
      colonAtEOLOpt()
      newLineOptWhenFollowedBy(LBRACE)
    }

    def possibleTemplateStart(isNew: Boolean = false): Unit =
      in.observeColonEOL()
      if in.token == COLONEOL then
        in.nextToken()
        if in.token != INDENT then
          syntaxError(i"indented definitions expected")
      else
        newLineOptWhenFollowedBy(LBRACE)

    def endMarkerScope[T](pid: Tree)(op: => T): T = pid match {
      case pid: RefTree => in.endMarkerScope(pid.name.toTermName)(op)
      case _ => op
    }

/* ------------- TYPES ------------------------------------------------------ */

    /** Same as [[typ]], but if this results in a wildcard it emits a syntax error and
     *  returns a tree for type `Any` instead.
     */
    def toplevelTyp(): Tree = rejectWildcardType(typ())

    private def isFunction(tree: Tree): Boolean = tree match {
      case Parens(tree1) => isFunction(tree1)
      case _: Function => true
      case _ => false
    }

    /** Type        ::=  FunType
     *                |  HkTypeParamClause ‘=>>’ Type
     *                |  MatchType
     *                |  InfixType
     *  FunType     ::=  (MonoFunType | PolyFunType)
     *  MonoFunType ::=  FunArgTypes (‘=>’ | ‘?=>’) Type
     *  PolyFunType ::=  HKTypeParamClause '=>' Type
     *  FunArgTypes ::=  InfixType
     *                |  `(' [ [ ‘[using]’ ‘['erased']  FunArgType {`,' FunArgType } ] `)'
     *                |  '(' [ ‘[using]’ ‘['erased'] TypedFunParam {',' TypedFunParam } ')'
     */
    def typ(): Tree = {
      val start = in.offset
      var imods = Modifiers()
      def functionRest(params: List[Tree]): Tree =
        atSpan(start, in.offset) {
          if in.token == CTXARROW then
            in.nextToken()
            imods |= Given
          else
            accept(ARROW)
          val t = typ()

          if (imods.isOneOf(Given | Erased)) new FunctionWithMods(params, t, imods)
          else if (ctx.settings.YkindProjector.value) {
            val (newParams :+ newT, tparams) = replaceKindProjectorPlaceholders(params :+ t)

            lambdaAbstract(tparams, Function(newParams, newT))
          } else {
            Function(params, t)
          }
        }
      def funArgTypesRest(first: Tree, following: () => Tree) = {
        val buf = new ListBuffer[Tree] += first
        while (in.token == COMMA) {
          in.nextToken()
          buf += following()
        }
        buf.toList
      }
      var isValParamList = false

      val t =
        if (in.token == LPAREN) {
          in.nextToken()
          if (in.token == RPAREN) {
            in.nextToken()
            functionRest(Nil)
          }
          else {
            openParens.change(LPAREN, 1)
            imods = modifiers(funTypeArgMods)
            val paramStart = in.offset
            val ts = funArgType() match {
              case Ident(name) if name != tpnme.WILDCARD && in.token == COLON =>
                isValParamList = true
                funArgTypesRest(
                    typedFunParam(paramStart, name.toTermName, imods),
                    () => typedFunParam(in.offset, ident(), imods))
              case t =>
                funArgTypesRest(t, funArgType)
            }
            openParens.change(LPAREN, -1)
            accept(RPAREN)
            if isValParamList || in.token == ARROW || in.token == CTXARROW then
              functionRest(ts)
            else {
              val ts1 =
                for (t <- ts) yield
                  t match {
                    case t@ByNameTypeTree(t1) =>
                      syntaxError(ByNameParameterNotSupported(t), t.span)
                      t1
                    case _ =>
                      t
                  }
              val tuple = atSpan(start) { makeTupleOrParens(ts1) }
              infixTypeRest(
                refinedTypeRest(
                  withTypeRest(
                    annotTypeRest(
                      simpleTypeRest(tuple)))))
            }
          }
        }
        else if (in.token == LBRACKET) {
          val start = in.offset
          val tparams = typeParamClause(ParamOwner.TypeParam)
          if (in.token == TLARROW)
            atSpan(start, in.skipToken())(LambdaTypeTree(tparams, toplevelTyp()))
          else if (in.token == ARROW) {
            val arrowOffset = in.skipToken()
            val body = toplevelTyp()
            atSpan(start, arrowOffset) {
              if (isFunction(body))
                PolyFunction(tparams, body)
              else {
                syntaxError("Implementation restriction: polymorphic function types must have a value parameter", arrowOffset)
                Ident(nme.ERROR.toTypeName)
              }
            }
          }
          else { accept(TLARROW); typ() }
        }
        else if (in.token == INDENT) enclosed(INDENT, typ())
        else infixType()

      in.token match {
        case ARROW | CTXARROW => functionRest(t :: Nil)
        case MATCH => matchType(t)
        case FORSOME => syntaxError(ExistentialTypesNoLongerSupported()); t
        case _ =>
          if (imods.is(Erased) && !t.isInstanceOf[FunctionWithMods])
            syntaxError(ErasedTypesCanOnlyBeFunctionTypes(), implicitKwPos(start))
          t
      }
    }

    private def makeKindProjectorTypeDef(name: TypeName): TypeDef =
      TypeDef(name, TypeBoundsTree(EmptyTree, EmptyTree)).withFlags(Param)

    /** Replaces kind-projector's `*` in a list of types arguments with synthetic names,
     *  returning the new argument list and the synthetic type definitions.
     */
    private def replaceKindProjectorPlaceholders(params: List[Tree]): (List[Tree], List[TypeDef]) = {
      val tparams = new ListBuffer[TypeDef]

      val newParams = params.mapConserve {
        case param @ Ident(tpnme.raw.STAR) =>
          val name = WildcardParamName.fresh().toTypeName
          tparams += makeKindProjectorTypeDef(name)
          Ident(name)
        case other => other
      }

      (newParams, tparams.toList)
    }

    private def implicitKwPos(start: Int): Span =
      Span(start, start + nme.IMPLICITkw.asSimpleName.length)

    /** TypedFunParam   ::= id ':' Type */
    def typedFunParam(start: Offset, name: TermName, mods: Modifiers = EmptyModifiers): Tree = atSpan(start) {
      accept(COLON)
      makeParameter(name, typ(), mods | Param)
    }

    /** InfixType ::= RefinedType {id [nl] RefinedType}
     */
    def infixType(): Tree = infixTypeRest(refinedType())

    /** Is current ident a `*`, and is it followed by a `)` or `,`? */
    def isPostfixStar: Boolean =
      in.name == nme.raw.STAR && in.lookaheadIn(BitSet(RPAREN, COMMA))

    def infixTypeRest(t: Tree): Tree =
      infixOps(t, canStartTypeTokens, refinedType, isType = true, isOperator = !isPostfixStar)

    /** RefinedType   ::=  WithType {[nl] Refinement}
     */
    val refinedType: () => Tree = () => refinedTypeRest(withType())

    def refinedTypeRest(t: Tree): Tree = {
      possibleBracesStart()
      if (in.isNestedStart)
        refinedTypeRest(atSpan(startOffset(t)) { RefinedTypeTree(rejectWildcardType(t), refinement()) })
      else t
    }

    /** WithType ::= AnnotType {`with' AnnotType}    (deprecated)
     */
    def withType(): Tree = withTypeRest(annotType())

    def withTypeRest(t: Tree): Tree =
      if in.token == WITH then
        val withOffset = in.offset
        in.nextToken()
        if in.token == LBRACE || in.token == INDENT then
          t
        else
          if sourceVersion.isAtLeast(`3.1`) then
            deprecationWarning(DeprecatedWithOperator(), withOffset)
          makeAndType(t, withType())
      else t

    /** AnnotType ::= SimpleType {Annotation}
     */
    def annotType(): Tree = annotTypeRest(simpleType())

    def annotTypeRest(t: Tree): Tree =
      if (in.token == AT)
        annotTypeRest(atSpan(startOffset(t)) {
          Annotated(rejectWildcardType(t), annot())
        })
      else t

    /** The block in a quote or splice */
    def stagedBlock() = inDefScopeBraces(block(simplify = true))

    /** SimpleEpxr  ::=  spliceId | ‘$’ ‘{’ Block ‘}’)
     *  SimpleType  ::=  spliceId | ‘$’ ‘{’ Block ‘}’)
     */
    def splice(isType: Boolean): Tree =
      atSpan(in.offset) {
        val expr =
          if (in.name.length == 1) {
            in.nextToken()
            withinStaged(StageKind.Spliced)(stagedBlock())
          }
          else atSpan(in.offset + 1) {
            val id = Ident(in.name.drop(1))
            in.nextToken()
            id
          }
        if (isType) TypSplice(expr) else Splice(expr)
      }

    /** SimpleType       ::=  SimpleType TypeArgs
     *                     |  SimpleType `#' id
     *                     |  StableId
     *                     |  Path `.' type
     *                     |  `(' ArgTypes `)'
     *                     |  `_' TypeBounds
     *                     |  Refinement
     *                     |  Literal
     *                     |  ‘$’ ‘{’ Block ‘}’
     */
    def simpleType(): Tree = simpleTypeRest {
      if (in.token == LPAREN)
        atSpan(in.offset) {
          makeTupleOrParens(inParens(argTypes(namedOK = false, wildOK = true)))
        }
      else if (in.token == LBRACE)
        atSpan(in.offset) { RefinedTypeTree(EmptyTree, refinement()) }
      else if (isSimpleLiteral) { SingletonTypeTree(literal(inType = true)) }
      else if (isIdent(nme.raw.MINUS) && in.lookaheadIn(numericLitTokens)) {
        val start = in.offset
        in.nextToken()
        SingletonTypeTree(literal(negOffset = start, inType = true))
      }
      else if (in.token == USCORE) {
        if sourceVersion.isAtLeast(`3.1`) then
          deprecationWarning(em"`_` is deprecated for wildcard arguments of types: use `?` instead")
          patch(source, Span(in.offset, in.offset + 1), "?")
        val start = in.skipToken()
        typeBounds().withSpan(Span(start, in.lastOffset, start))
      }
      else if (isIdent(nme.?)) {
        val start = in.skipToken()
        typeBounds().withSpan(Span(start, in.lastOffset, start))
      }
      else if (isIdent(nme.*) && ctx.settings.YkindProjector.value) {
        typeIdent()
      }
      else if (isSplice)
        splice(isType = true)
      else path(thisOK = false, handleSingletonType) match {
        case r @ SingletonTypeTree(_) => r
        case r => convertToTypeId(r)
      }
    }

    val handleSingletonType: Tree => Tree = t =>
      if (in.token == TYPE) {
        in.nextToken()
        atSpan(startOffset(t)) { SingletonTypeTree(t) }
      }
      else t

    private def simpleTypeRest(t: Tree): Tree = in.token match {
      case HASH => simpleTypeRest(typeProjection(t))
      case LBRACKET => simpleTypeRest(atSpan(startOffset(t)) {
        val applied = rejectWildcardType(t)
        val args = typeArgs(namedOK = false, wildOK = true)

        if (ctx.settings.YkindProjector.value) {
          def fail(): Tree = {
            syntaxError(
              "λ requires a single argument of the form X => ... or (X, Y) => ...",
              Span(t.span.start, in.lastOffset)
            )
            AppliedTypeTree(applied, args)
          }

          applied match {
            case Ident(tpnme.raw.LAMBDA) =>
              args match {
                case List(Function(params, body)) =>
                  val typeDefs = params.collect {
                    case param @ Ident(name) => makeKindProjectorTypeDef(name.toTypeName).withSpan(param.span)
                  }
                  if (typeDefs.length != params.length) fail()
                  else LambdaTypeTree(typeDefs, body)
                case _ =>
                  fail()
              }
            case _ =>
              val (newArgs, tparams) = replaceKindProjectorPlaceholders(args)

              lambdaAbstract(tparams, AppliedTypeTree(applied, newArgs))
          }

        } else {
          AppliedTypeTree(applied, args)
        }
      })
      case _ =>
        if (ctx.settings.YkindProjector.value) {
          t match {
            case Tuple(params) =>
              val (newParams, tparams) = replaceKindProjectorPlaceholders(params)

              if (tparams.isEmpty) {
                t
              } else {
                LambdaTypeTree(tparams, Tuple(newParams))
              }
            case _ => t
          }
        } else {
          t
        }
    }

    private def typeProjection(t: Tree): Tree = {
      accept(HASH)
      val id = typeIdent()
      atSpan(startOffset(t), startOffset(id)) { Select(t, id.name) }
    }

    /**   ArgTypes          ::=  Type {`,' Type}
     *                        |  NamedTypeArg {`,' NamedTypeArg}
     *    NamedTypeArg      ::=  id `=' Type
     */
    def argTypes(namedOK: Boolean, wildOK: Boolean): List[Tree] = {

      def argType() = {
        val t = typ()
        if (wildOK) t else rejectWildcardType(t)
      }

      def namedTypeArg() = {
        val name = ident()
        accept(EQUALS)
        NamedArg(name.toTypeName, argType())
      }

      def otherArgs(first: Tree, arg: () => Tree): List[Tree] = {
        val rest =
          if (in.token == COMMA) {
            in.nextToken()
            commaSeparated(arg)
          }
          else Nil
        first :: rest
      }
      if (namedOK && in.token == IDENTIFIER)
        argType() match {
          case Ident(name) if in.token == EQUALS =>
            in.nextToken()
            otherArgs(NamedArg(name, argType()), () => namedTypeArg())
          case firstArg =>
            otherArgs(firstArg, () => argType())
        }
      else commaSeparated(() => argType())
    }

    /** FunArgType ::=  Type | `=>' Type
     */
    val funArgType: () => Tree = () =>
      if (in.token == ARROW) atSpan(in.skipToken()) { ByNameTypeTree(typ()) }
      else typ()

    /** ParamType ::= [`=>'] ParamValueType
     */
    def paramType(): Tree =
      if (in.token == ARROW) atSpan(in.skipToken()) { ByNameTypeTree(paramValueType()) }
      else paramValueType()

    /** ParamValueType ::= Type [`*']
     */
    def paramValueType(): Tree = {
      val t = toplevelTyp()
      if (isIdent(nme.raw.STAR)) {
        in.nextToken()
        atSpan(startOffset(t)) { PostfixOp(t, Ident(tpnme.raw.STAR)) }
      }
      else t
    }

    /** TypeArgs      ::= `[' Type {`,' Type} `]'
     *  NamedTypeArgs ::= `[' NamedTypeArg {`,' NamedTypeArg} `]'
     */
    def typeArgs(namedOK: Boolean, wildOK: Boolean): List[Tree] = inBrackets(argTypes(namedOK, wildOK))

    /** Refinement ::= `{' RefineStatSeq `}'
     */
    def refinement(): List[Tree] = inBracesOrIndented(refineStatSeq())

    /** TypeBounds ::= [`>:' Type] [`<:' Type]
     */
    def typeBounds(): TypeBoundsTree =
      atSpan(in.offset) { TypeBoundsTree(bound(SUPERTYPE), bound(SUBTYPE)) }

    private def bound(tok: Int): Tree =
      if (in.token == tok) { in.nextToken(); toplevelTyp() }
      else EmptyTree

    /** TypeParamBounds   ::=  TypeBounds {`<%' Type} {`:' Type}
     */
    def typeParamBounds(pname: TypeName): Tree = {
      val t = typeBounds()
      val cbs = contextBounds(pname)
      if (cbs.isEmpty) t
      else atSpan((t.span union cbs.head.span).start) { ContextBounds(t, cbs) }
    }

    def contextBounds(pname: TypeName): List[Tree] = in.token match {
      case COLON =>
        atSpan(in.skipToken()) {
          AppliedTypeTree(toplevelTyp(), Ident(pname))
        } :: contextBounds(pname)
      case VIEWBOUND =>
        ctx.errorOrMigrationWarning(
          "view bounds `<%' are deprecated, use a context bound `:' instead",
          in.sourcePos())
        atSpan(in.skipToken()) {
          Function(Ident(pname) :: Nil, toplevelTyp())
        } :: contextBounds(pname)
      case _ =>
        Nil
    }

    def typedOpt(): Tree = {
      if (in.token == COLONEOL) in.token = COLON
      	// a hack to allow
      	//
      	//     def f():
      	//       T
        //
      if (in.token == COLON) { in.nextToken(); toplevelTyp() }
      else TypeTree().withSpan(Span(in.lastOffset))
    }

    def typeDependingOn(location: Location.Value): Tree =
      if (location == Location.InParens) typ()
      else if (location == Location.InPattern) refinedType()
      else infixType()

/* ----------- EXPRESSIONS ------------------------------------------------ */

    /** Does the current conditional expression continue after
     *  the initially parsed (...) region?
     */
    def toBeContinued(altToken: Token): Boolean =
      if in.token == altToken || in.isNewLine || migrateTo3 then
        false // a newline token means the expression is finished
      else if !in.canStartStatTokens.contains(in.token)
              || in.isLeadingInfixOperator(inConditional = true)
      then
        true
      else
        followedByToken(altToken) // scan ahead to see whether we find a `then` or `do`

    def condExpr(altToken: Token): Tree =
      if in.token == LPAREN then
        var t: Tree = atSpan(in.offset) { Parens(inParens(exprInParens())) }
        val enclosedInParens = !toBeContinued(altToken)
        if !enclosedInParens then
          t = inSepRegion(InCond) {
            expr1Rest(postfixExprRest(simpleExprRest(t)), Location.ElseWhere)
          }
        if in.token == altToken then
          if rewriteToOldSyntax() then revertToParens(t)
          in.nextToken()
        else
          if (altToken == THEN || enclosedInParens) && in.isNewLine then
            in.observeIndented()
          if !enclosedInParens && in.token != INDENT then reportMissing(altToken)
          if (rewriteToNewSyntax(t.span))
            dropParensOrBraces(t.span.start, s"${tokenString(altToken)}")
        t
      else
        val t =
          if in.isNestedStart then
            try expr() finally newLinesOpt()
          else
            inSepRegion(InCond)(expr())
        if rewriteToOldSyntax(t.span.startPos) then
          revertToParens(t)
        if altToken == THEN && in.isNewLine then
          // don't require a `then` at the end of a line
          in.observeIndented()
        if in.token != INDENT then accept(altToken)
        t
    end condExpr

    /** Expr              ::=  [`implicit'] FunParams (‘=>’ | ‘?=>’) Expr
     *                      |  Expr1
     *  FunParams         ::=  Bindings
     *                      |  id
     *                      |  `_'
     *  ExprInParens      ::=  PostfixExpr `:' Type
     *                      |  Expr
     *  BlockResult       ::=  [‘implicit’] FunParams (‘=>’ | ‘?=>’) Block
     *                      |  Expr1
     *  Expr1             ::=  [‘inline’] `if' `(' Expr `)' {nl} Expr [[semi] else Expr]
     *                      |  [‘inline’] `if' Expr `then' Expr [[semi] else Expr]
     *                      |  `while' `(' Expr `)' {nl} Expr
     *                      |  `while' Expr `do' Expr
     *                      |  `try' Expr Catches [`finally' Expr]
     *                      |  `try' Expr [`finally' Expr]
     *                      |  `throw' Expr
     *                      |  `return' [Expr]
     *                      |  ForExpr
     *                      |  HkTypeParamClause ‘=>’ Expr
     *                      |  [SimpleExpr `.'] id `=' Expr
     *                      |  SimpleExpr1 ArgumentExprs `=' Expr
     *                      |  PostfixExpr [Ascription]
     *                      |  ‘inline’ InfixExpr MatchClause
     *  Bindings          ::=  `(' [Binding {`,' Binding}] `)'
     *  Binding           ::=  (id | `_') [`:' Type]
     *  Ascription        ::=  `:' InfixType
     *                      |  `:' Annotation {Annotation}
     *                      |  `:' `_' `*'
     *  Catches           ::=  ‘catch’ (Expr | ExprCaseClause)
     */
    val exprInParens: () => Tree = () => expr(Location.InParens)

    val expr: () => Tree = () => expr(Location.ElseWhere)

    def subExpr() = subPart(expr)

    def expr(location: Location.Value): Tree = {
      val start = in.offset
      def isSpecialClosureStart =
        val lookahead = in.LookaheadScanner()
        lookahead.nextToken()
        lookahead.isIdent(nme.using) || lookahead.token == ERASED
      if in.token == IMPLICIT then
        closure(start, location, modifiers(BitSet(IMPLICIT)))
      else if in.token == LPAREN && isSpecialClosureStart then
        closure(start, location, Modifiers())
      else {
        val saved = placeholderParams
        placeholderParams = Nil

        def wrapPlaceholders(t: Tree) = try
          if (placeholderParams.isEmpty) t
          else new WildcardFunction(placeholderParams.reverse, t)
        finally placeholderParams = saved

        val t = expr1(location)
        if (in.token == ARROW || in.token == CTXARROW) {
          placeholderParams = Nil // don't interpret `_' to the left of `=>` as placeholder
          val paramMods = if in.token == CTXARROW then Modifiers(Given) else EmptyModifiers
          wrapPlaceholders(closureRest(start, location, convertToParams(t, paramMods)))
        }
        else if (isWildcard(t)) {
          placeholderParams = placeholderParams ::: saved
          t
        }
        else wrapPlaceholders(t)
      }
    }

    def expr1(location: Location.Value = Location.ElseWhere): Tree = in.token match
      case IF =>
        in.endMarkerScope(IF) { ifExpr(in.offset, If) }
      case WHILE =>
        in.endMarkerScope(WHILE) {
          atSpan(in.skipToken()) {
            val cond = condExpr(DO)
            newLinesOpt()
            val body = subExpr()
            WhileDo(cond, body)
          }
        }
      case DO =>
        ctx.errorOrMigrationWarning(
          i"""`do <body> while <cond>` is no longer supported,
             |use `while <body> ; <cond> do ()` instead.${rewriteNotice()}""",
          in.sourcePos())
        val start = in.skipToken()
        atSpan(start) {
          val body = expr()
          if (isStatSep) in.nextToken()
          val whileStart = in.offset
          accept(WHILE)
          val cond = expr()
          if migrateTo3 then
            patch(source, Span(start, start + 2), "while ({")
            patch(source, Span(whileStart, whileStart + 5), ";")
            cond match {
              case Parens(_) =>
                patch(source, Span(cond.span.start, cond.span.start + 1), "")
                patch(source, Span(cond.span.end - 1, cond.span.end), "")
              case _ =>
            }
            patch(source, cond.span.endPos, "}) ()")
          WhileDo(Block(body, cond), Literal(Constant(())))
        }
      case TRY =>
        in.endMarkerScope(TRY) {
          val tryOffset = in.offset
          atSpan(in.skipToken()) {
            val body = expr()
            val (handler, handlerStart) =
              if in.token == CATCH then
                val span = in.offset
                in.nextToken()
                (if in.token == CASE then Match(EmptyTree, caseClause(exprOnly = true) :: Nil)
                 else subExpr(),
                 span)
              else (EmptyTree, -1)

            handler match {
              case Block(Nil, EmptyTree) =>
                assert(handlerStart != -1)
                syntaxError(
                  EmptyCatchBlock(body),
                  Span(handlerStart, endOffset(handler))
                )
              case _ =>
            }

            val finalizer =
              if (in.token == FINALLY) { in.nextToken(); subExpr() }
              else {
                if (handler.isEmpty) warning(
                  EmptyCatchAndFinallyBlock(body),
                  source.atSpan(Span(tryOffset, endOffset(body)))
                )
                EmptyTree
              }
            ParsedTry(body, handler, finalizer)
          }
        }
      case THROW =>
        atSpan(in.skipToken()) { Throw(expr()) }
      case RETURN =>
        atSpan(in.skipToken()) {
          colonAtEOLOpt()
          Return(if (isExprIntro) expr() else EmptyTree, EmptyTree)
        }
      case FOR =>
        forExpr()
      case LBRACKET =>
        val start = in.offset
        val tparams = typeParamClause(ParamOwner.TypeParam)
        val arrowOffset = accept(ARROW)
        val body = expr()
        atSpan(start, arrowOffset) {
          if (isFunction(body))
            PolyFunction(tparams, body)
          else {
            syntaxError("Implementation restriction: polymorphic function literals must have a value parameter", arrowOffset)
            errorTermTree
          }
        }
      case _ =>
        if isIdent(nme.inline)
           && !in.inModifierPosition()
           && in.lookaheadIn(in.canStartExprTokens)
        then
          val start = in.skipToken()
          in.token match
            case IF =>
              ifExpr(start, InlineIf)
            case _ =>
              postfixExpr() match
                case t @ Match(scrut, cases) =>
                  InlineMatch(scrut, cases).withSpan(t.span)
                case t =>
                  syntaxError(em"`inline` must be followed by an `if` or a `match`", start)
                  t
        else expr1Rest(postfixExpr(), location)
    end expr1

    def expr1Rest(t: Tree, location: Location.Value): Tree = in.token match
      case EQUALS =>
        t match
          case Ident(_) | Select(_, _) | Apply(_, _) =>
            atSpan(startOffset(t), in.skipToken()) { Assign(t, subExpr()) }
          case _ =>
            t
      case COLON =>
        in.nextToken()
        ascription(t, location)
      case _ =>
        t
    end expr1Rest

    def ascription(t: Tree, location: Location.Value): Tree = atSpan(startOffset(t)) {
      in.token match {
        case USCORE =>
          val uscoreStart = in.skipToken()
          if (isIdent(nme.raw.STAR)) {
            in.nextToken()
            if (in.token != RPAREN) syntaxError(SeqWildcardPatternPos(), uscoreStart)
            Typed(t, atSpan(uscoreStart) { Ident(tpnme.WILDCARD_STAR) })
          }
          else {
            syntaxErrorOrIncomplete(IncorrectRepeatedParameterSyntax())
            t
          }
        case AT if location != Location.InPattern =>
          annotations().foldLeft(t)(Annotated)
        case _ =>
          val tpt = typeDependingOn(location)
          if (isWildcard(t) && location != Location.InPattern) {
            val vd :: rest = placeholderParams
            placeholderParams =
              cpy.ValDef(vd)(tpt = tpt).withSpan(vd.span.union(tpt.span)) :: rest
          }
          Typed(t, tpt)
      }
    }

    /**    `if' `(' Expr `)' {nl} Expr [[semi] else Expr]
     *     `if' Expr `then' Expr [[semi] else Expr]
     */
    def ifExpr(start: Offset, mkIf: (Tree, Tree, Tree) => If): If =
      atSpan(start, in.skipToken()) {
        val cond = condExpr(THEN)
        newLinesOpt()
        val thenp = subExpr()
        val elsep = if (in.token == ELSE) { in.nextToken(); subExpr() }
                    else EmptyTree
        mkIf(cond, thenp, elsep)
      }

    /**    MatchClause ::= `match' `{' CaseClauses `}'
     */
    def matchClause(t: Tree): Match =
      in.endMarkerScope(MATCH) {
        atSpan(t.span.start, in.skipToken()) {
          Match(t, inBracesOrIndented(caseClauses(() => caseClause())))
        }
      }

    /**    `match' `{' TypeCaseClauses `}'
     */
    def matchType(t: Tree): MatchTypeTree =
      atSpan(t.span.start, accept(MATCH)) {
        MatchTypeTree(EmptyTree, t, inBracesOrIndented(caseClauses(typeCaseClause)))
      }

    /** FunParams         ::=  Bindings
     *                     |   id
     *                     |   `_'
     *  Bindings          ::=  `(' [[‘using’] [‘erased’] Binding {`,' Binding}] `)'
     */
    def funParams(mods: Modifiers, location: Location.Value): List[Tree] =
      if in.token == LPAREN then
        in.nextToken()
        if in.token == RPAREN then
          Nil
        else
          openParens.change(LPAREN, 1)
          var mods1 = mods
          if mods.flags.isEmpty then
            if isIdent(nme.using) then mods1 = addMod(mods1, atSpan(in.skipToken()) { Mod.Given() })
            if in.token == ERASED then mods1 = addModifier(mods1)
          try
            commaSeparated(() => binding(mods1))
          finally
            accept(RPAREN)
            openParens.change(LPAREN, -1)
      else {
        val start = in.offset
        val name = bindingName()
        val t =
          if (in.token == COLON && location == Location.InBlock) {
            if sourceVersion.isAtLeast(`3.1`)
                // Don't error in non-strict mode, as the alternative syntax "implicit (x: T) => ... "
                // is not supported by Scala2.x
              ctx.errorOrMigrationWarning(
                s"This syntax is no longer supported; parameter needs to be enclosed in (...)",
                in.sourcePos())
            in.nextToken()
            val t = infixType()
            if (false && migrateTo3) {
              patch(source, Span(start), "(")
              patch(source, Span(in.lastOffset), ")")
            }
            t
          }
          else TypeTree()
        (atSpan(start) { makeParameter(name, t, mods) }) :: Nil
      }

    /**  Binding           ::= (id | `_') [`:' Type]
     */
    def binding(mods: Modifiers): Tree =
      atSpan(in.offset) { makeParameter(bindingName(), typedOpt(), mods) }

    def bindingName(): TermName =
      if (in.token == USCORE) {
        in.nextToken()
        WildcardParamName.fresh()
      }
      else ident()

    /** Expr         ::= [‘implicit’] FunParams `=>' Expr
     *  BlockResult  ::= implicit id [`:' InfixType] `=>' Block // Scala2 only
     */
    def closure(start: Int, location: Location.Value, implicitMods: Modifiers): Tree =
      closureRest(start, location, funParams(implicitMods, location))

    def closureRest(start: Int, location: Location.Value, params: List[Tree]): Tree =
      atSpan(start, in.offset) {
        if in.token == CTXARROW then in.nextToken() else accept(ARROW)
        Function(params, if (location == Location.InBlock) block() else expr())
      }

    /** PostfixExpr   ::= InfixExpr [id [nl]]
     *  InfixExpr     ::= PrefixExpr
     *                  | InfixExpr id [nl] InfixExpr
     *                  | InfixExpr MatchClause
     */
    def postfixExpr(): Tree = postfixExprRest(prefixExpr())

    def postfixExprRest(t: Tree): Tree =
      infixOps(t, in.canStartExprTokens, prefixExpr, maybePostfix = true)

    /** PrefixExpr   ::= [`-' | `+' | `~' | `!'] SimpleExpr
    */
    val prefixExpr: () => Tree = () =>
      if (isIdent && nme.raw.isUnary(in.name)) {
        val start = in.offset
        val op = termIdent()
        if (op.name == nme.raw.MINUS && isNumericLit)
          simpleExprRest(literal(start), canApply = true)
        else
          atSpan(start) { PrefixOp(op, simpleExpr()) }
      }
      else simpleExpr()

    /** SimpleExpr    ::= ‘new’ ConstrApp {`with` ConstrApp} [TemplateBody]
     *                 |  ‘new’ TemplateBody
     *                 |  BlockExpr
     *                 |  ‘$’ ‘{’ Block ‘}’
     *                 |  Quoted
     *                 |  quoteId
     *                 |  SimpleExpr1 [`_`]
     *  SimpleExpr1   ::= literal
     *                 |  xmlLiteral
     *                 |  Path
     *                 |  `(` [ExprsInParens] `)`
     *                 |  SimpleExpr `.` id
     *                 |  SimpleExpr `.` MatchClause
     *                 |  SimpleExpr (TypeArgs | NamedTypeArgs)
     *                 |  SimpleExpr1 ArgumentExprs
     *  Quoted        ::= ‘'’ ‘{’ Block ‘}’
     *                 |  ‘'’ ‘[’ Type ‘]’
     */
    def simpleExpr(): Tree = {
      var canApply = true
      val t = in.token match {
        case XMLSTART =>
          xmlLiteral()
        case IDENTIFIER =>
          if (isSplice) splice(isType = false)
          else path(thisOK = true)
        case BACKQUOTED_IDENT | THIS | SUPER =>
          path(thisOK = true)
        case USCORE =>
          val start = in.skipToken()
          val pname = WildcardParamName.fresh()
          val param = ValDef(pname, TypeTree(), EmptyTree).withFlags(SyntheticTermParam)
            .withSpan(Span(start))
          placeholderParams = param :: placeholderParams
          atSpan(start) { Ident(pname) }
        case LPAREN =>
          atSpan(in.offset) { makeTupleOrParens(inParens(exprsInParensOpt())) }
        case LBRACE | INDENT =>
          canApply = false
          blockExpr()
        case QUOTE =>
          atSpan(in.skipToken()) {
            withinStaged(StageKind.Quoted) {
              Quote {
                if (in.token == LBRACKET) inBrackets(typ())
                else stagedBlock()
              }
            }
          }
        case NEW =>
          canApply = false
          newExpr()
        case MACRO =>
          val start = in.skipToken()
          val call = ident()
          // The standard library uses "macro ???" to denote "fast track" macros
          // hardcoded in the compiler, don't issue an error for those macros
          // since we want to be able to compile the standard library.
          if (call `ne` nme.???)
            syntaxError(
              "Scala 2 macros are not supported, see https://dotty.epfl.ch/docs/reference/dropped-features/macros.html",
              start)
          unimplementedExpr
        case COLONEOL =>
          syntaxError("':' not allowed here")
          in.nextToken()
          simpleExpr()
        case _ =>
          if (isLiteral) literal()
          else {
            syntaxErrorOrIncomplete(IllegalStartSimpleExpr(tokenString(in.token)), expectedOffset)
            errorTermTree
          }
      }
      simpleExprRest(t, canApply)
    }

    def simpleExprRest(t: Tree, canApply: Boolean = true): Tree = {
      if (canApply) possibleBracesStart()
      in.token match {
        case DOT =>
          in.nextToken()
          simpleExprRest(selector(t), canApply = true)
        case LBRACKET =>
          val tapp = atSpan(startOffset(t), in.offset) { TypeApply(t, typeArgs(namedOK = true, wildOK = false)) }
          simpleExprRest(tapp, canApply = true)
        case LPAREN | LBRACE | INDENT if canApply =>
          val app = atSpan(startOffset(t), in.offset) { mkApply(t, argumentExprs()) }
          simpleExprRest(app, canApply = true)
        case USCORE =>
          atSpan(startOffset(t), in.skipToken()) { PostfixOp(t, Ident(nme.WILDCARD)) }
        case _ =>
          t
      }
    }

    /** SimpleExpr    ::=  ‘new’ ConstrApp {`with` ConstrApp} [TemplateBody]
     *                  |  ‘new’ TemplateBody
     */
    def newExpr(): Tree =
      in.endMarkerScope(NEW) {
        val start = in.skipToken()
        def reposition(t: Tree) = t.withSpan(Span(start, in.lastOffset))
        possibleTemplateStart()
        val parents =
          if in.isNestedStart then Nil
          else constrApps(commaOK = false, templateCanFollow = true)
        colonAtEOLOpt()
        possibleTemplateStart(isNew = true)
        parents match {
          case parent :: Nil if !in.isNestedStart =>
            reposition(if (parent.isType) ensureApplied(wrapNew(parent)) else parent)
          case _ =>
            New(reposition(templateBodyOpt(emptyConstructor, parents, Nil)))
        }
      }

    /**   ExprsInParens     ::=  ExprInParens {`,' ExprInParens}
     */
    def exprsInParensOpt(): List[Tree] =
      if (in.token == RPAREN) Nil else commaSeparated(exprInParens)

    /** ParArgumentExprs ::= `(' [‘using’] [ExprsInParens] `)'
     *                    |  `(' [ExprsInParens `,'] PostfixExpr `:' `_' `*' ')'
     */
    def parArgumentExprs(): (List[Tree], Boolean) = inParens {
      if in.token == RPAREN then
        (Nil, false)
      else if isIdent(nme.using) then
        in.nextToken()
        (commaSeparated(argumentExpr), true)
      else
        (commaSeparated(argumentExpr), false)
    }

    /** ArgumentExprs ::= ParArgumentExprs
     *                 |  [nl] BlockExpr
     */
    def argumentExprs(): (List[Tree], Boolean) =
      if (in.isNestedStart) (blockExpr() :: Nil, false) else parArgumentExprs()

    def mkApply(fn: Tree, args: (List[Tree], Boolean)): Tree =
      val res = Apply(fn, args._1)
      if args._2 then res.setUsingApply()
      res

    val argumentExpr: () => Tree = () => exprInParens() match {
      case arg @ Assign(Ident(id), rhs) => cpy.NamedArg(arg)(id, rhs)
      case arg => arg
    }

    /** ArgumentExprss ::= {ArgumentExprs}
     */
    def argumentExprss(fn: Tree): Tree = {
      possibleBracesStart()
      if (in.token == LPAREN || in.isNestedStart) argumentExprss(mkApply(fn, argumentExprs()))
      else fn
    }

    /** ParArgumentExprss ::= {ParArgumentExprs}
     *
     *  Special treatment for arguments to primary constructor annotations.
     *  (...) is considered an argument only if it does not look like a formal
     *  parameter list, i.e. does not start with `( <annot>* <mod>* ident : `
     *  Furthermore, `()` is considered a annotation argument only if it comes first.
     */
    def parArgumentExprss(fn: Tree): Tree = {
      def isLegalAnnotArg: Boolean = {
        val lookahead = in.LookaheadScanner()
        (lookahead.token == LPAREN) && {
          lookahead.nextToken()
          if (lookahead.token == RPAREN)
            !fn.isInstanceOf[Trees.Apply[?]] // allow one () as annotation argument
          else if (lookahead.token == IDENTIFIER) {
            lookahead.nextToken()
            lookahead.token != COLON
          }
          else in.canStartExprTokens.contains(lookahead.token)
        }
      }
      if (in.token == LPAREN && (!inClassConstrAnnots || isLegalAnnotArg))
        parArgumentExprss(
          atSpan(startOffset(fn)) { mkApply(fn, parArgumentExprs()) }
        )
      else fn
    }

    /** BlockExpr         ::= `{' BlockExprContents `}'
     *  BlockExprContents ::= CaseClauses | Block
     */
    def blockExpr(): Tree = atSpan(in.offset) {
      val simplify = in.token == INDENT
      inDefScopeBraces {
        if (in.token == CASE) Match(EmptyTree, caseClauses(() => caseClause()))
        else block(simplify)
      }
    }

    /** Block ::= BlockStatSeq
     *  @note  Return tree does not have a defined span.
     */
    def block(simplify: Boolean = false): Tree = {
      val stats = blockStatSeq()
      def isExpr(stat: Tree) = !(stat.isDef || stat.isInstanceOf[Import])
      if (stats.nonEmpty && isExpr(stats.last)) {
        val inits = stats.init
        val last = stats.last
        if (inits.isEmpty && (simplify || last.isInstanceOf[Block])) last
        else Block(inits, last)
      }
      else Block(stats, EmptyTree)
    }

    /** Guard ::= if PostfixExpr
     */
    def guard(): Tree =
      if (in.token == IF) { in.nextToken(); postfixExpr() }
      else EmptyTree

    /** Enumerators ::= Generator {semi Enumerator | Guard}
     */
    def enumerators(): List[Tree] = generator() :: enumeratorsRest()

    def enumeratorsRest(): List[Tree] =
      if (isStatSep) {
        in.nextToken()
        if (in.token == DO || in.token == YIELD || in.token == RBRACE) Nil
        else enumerator() :: enumeratorsRest()
      }
      else if (in.token == IF)
        guard() :: enumeratorsRest()
      else Nil

    /** Enumerator  ::=  Generator
     *                |  Guard
     *                |  Pattern1 `=' Expr
     */
    def enumerator(): Tree =
      if (in.token == IF) guard()
      else if (in.token == CASE) generator()
      else {
        val pat = pattern1()
        if (in.token == EQUALS) atSpan(startOffset(pat), in.skipToken()) { GenAlias(pat, subExpr()) }
        else generatorRest(pat, casePat = false)
      }

    /** Generator   ::=  [‘case’] Pattern `<-' Expr
     */
    def generator(): Tree = {
      val casePat = if (in.token == CASE) { in.nextToken(); true } else false
      generatorRest(pattern1(), casePat)
    }

    def generatorRest(pat: Tree, casePat: Boolean): GenFrom =
      atSpan(startOffset(pat), accept(LARROW)) {
        val checkMode =
          if (casePat) GenCheckMode.FilterAlways
          else if sourceVersion.isAtLeast(`3.1`) then GenCheckMode.Check
          else GenCheckMode.FilterNow  // filter for now, to keep backwards compat
        GenFrom(pat, subExpr(), checkMode)
      }

    /** ForExpr  ::= `for' (`(' Enumerators `)' | `{' Enumerators `}')
     *                {nl} [`yield'] Expr
     *            |  `for' Enumerators (`do' Expr | `yield' Expr)
     */
    def forExpr(): Tree = in.endMarkerScope(FOR) {
      atSpan(in.skipToken()) {
        var wrappedEnums = true
        val start = in.offset
        val forEnd = in.lastOffset
        val leading = in.token
        val enums =
          if (leading == LBRACE || leading == LPAREN && followingIsEnclosedGenerators()) {
            in.nextToken()
            openParens.change(leading, 1)
            val res =
              if (leading == LBRACE || in.token == CASE)
                enumerators()
              else {
                val pats = patternsOpt()
                val pat =
                  if (in.token == RPAREN || pats.length > 1) {
                    wrappedEnums = false
                    accept(RPAREN)
                    openParens.change(LPAREN, -1)
                    atSpan(start) { makeTupleOrParens(pats) } // note: alternatives `|' need to be weeded out by typer.
                  }
                  else pats.head
                generatorRest(pat, casePat = false) :: enumeratorsRest()
              }
            if (wrappedEnums) {
              val closingOnNewLine = in.isAfterLineEnd
              accept(leading + 1)
              openParens.change(leading, -1)
              def hasMultiLineEnum =
                res.exists { t =>
                  val pos = t.sourcePos
                  pos.startLine < pos.endLine
                }
              if (rewriteToNewSyntax(Span(start)) && (leading == LBRACE || !hasMultiLineEnum)) {
                // Don't rewrite if that could change meaning of newlines
                newLinesOpt()
                dropParensOrBraces(start, if (in.token == YIELD || in.token == DO) "" else "do")
              }
            }
            in.observeIndented()
            res
          }
          else {
            wrappedEnums = false

            if (in.token == INDENT)
              inBracesOrIndented(enumerators())
            else {
              val ts = inSepRegion(InCond)(enumerators())
              if (rewriteToOldSyntax(Span(start)) && ts.nonEmpty)
                if (ts.head.sourcePos.startLine != ts.last.sourcePos.startLine) {
                  patch(source, Span(forEnd), " {")
                  patch(source, Span(in.offset), "} ")
                }
                else {
                  patch(source, ts.head.span.startPos, "(")
                  patch(source, ts.last.span.endPos, ")")
                }
              ts
            }
          }
        newLinesOpt()
        if (in.token == YIELD) {
          in.nextToken()
          ForYield(enums, subExpr())
        }
        else if (in.token == DO) {
          if (rewriteToOldSyntax()) dropTerminator()
          in.nextToken()
          ForDo(enums, subExpr())
        }
        else {
          if (!wrappedEnums) syntaxErrorOrIncomplete(YieldOrDoExpectedInForComprehension())
          ForDo(enums, expr())
        }
      }
    }

    /** CaseClauses         ::= CaseClause {CaseClause}
     *  TypeCaseClauses     ::= TypeCaseClause {TypeCaseClause}
     */
    def caseClauses(clause: () => CaseDef): List[CaseDef] = {
      val buf = new ListBuffer[CaseDef]
      buf += clause()
      while (in.token == CASE) buf += clause()
      buf.toList
    }

    /** CaseClause         ::= ‘case’ Pattern [Guard] `=>' Block
     *  ExprCaseClause    ::=  ‘case’ Pattern [Guard] ‘=>’ Expr
     */
    def caseClause(exprOnly: Boolean = false): CaseDef = atSpan(in.offset) {
      val (pat, grd) = inSepRegion(InCase) {
        accept(CASE)
        (pattern(), guard())
      }
      CaseDef(pat, grd, atSpan(accept(ARROW)) {
        if exprOnly then expr() else block()
      })
    }

    /** TypeCaseClause     ::= ‘case’ InfixType ‘=>’ Type [nl]
     */
    def typeCaseClause(): CaseDef = atSpan(in.offset) {
      val pat = inSepRegion(InCase) {
        accept(CASE)
        infixType()
      }
      CaseDef(pat, EmptyTree, atSpan(accept(ARROW)) {
        val t = typ()
        newLinesOptWhenFollowedBy(CASE)
        t
      })
    }

    /* -------- PATTERNS ------------------------------------------- */

    /**  Pattern           ::=  Pattern1 { `|' Pattern1 }
     */
    val pattern: () => Tree = () => {
      val pat = pattern1()
      if (isIdent(nme.raw.BAR))
        atSpan(startOffset(pat)) { Alternative(pat :: patternAlts()) }
      else pat
    }

    def patternAlts(): List[Tree] =
      if (isIdent(nme.raw.BAR)) { in.nextToken(); pattern1() :: patternAlts() }
      else Nil

    /**  Pattern1     ::= Pattern2 [Ascription]
     *                  | ‘given’ PatVar ‘:’ RefinedType
     */
    def pattern1(): Tree =
      if (in.token == GIVEN) {
        val givenMod = atSpan(in.skipToken())(Mod.Given())
        atSpan(in.offset) {
          in.token match {
            case IDENTIFIER | USCORE if in.name.isVariableName =>
              val name = in.name
              in.nextToken()
              accept(COLON)
              val typed = ascription(Ident(nme.WILDCARD), Location.InPattern)
              Bind(name, typed).withMods(addMod(Modifiers(), givenMod))
            case _ =>
              syntaxErrorOrIncomplete("pattern variable expected")
              errorTermTree
          }
        }
      }
      else {
        val p = pattern2()
        if (in.token == COLON) {
          in.nextToken()
          ascription(p, Location.InPattern)
        }
        else p
      }

    /**  Pattern2    ::=  [id `@'] InfixPattern
     */
    val pattern2: () => Tree = () => infixPattern() match {
      case p @ Ident(name) if in.token == AT =>
        val offset = in.skipToken()

        // compatibility for Scala2 `x @ _*` syntax
        infixPattern() match {
          case pt @ Ident(tpnme.WILDCARD_STAR) =>
            if sourceVersion.isAtLeast(`3.1`) then
              ctx.errorOrMigrationWarning(
                "The syntax `x @ _*` is no longer supported; use `x : _*` instead",
                in.sourcePos(startOffset(p)))
            atSpan(startOffset(p), offset) { Typed(p, pt) }
          case pt =>
            atSpan(startOffset(p), 0) { Bind(name, pt) }
        }
      case p @ Ident(tpnme.WILDCARD_STAR) =>
        // compatibility for Scala2 `_*` syntax
        if sourceVersion.isAtLeast(`3.1`) then
          ctx.errorOrMigrationWarning(
            "The syntax `_*` is no longer supported; use `x : _*` instead",
            in.sourcePos(startOffset(p)))
        atSpan(startOffset(p)) { Typed(Ident(nme.WILDCARD), p) }
      case p =>
        p
    }

    /**  InfixPattern ::= SimplePattern {id [nl] SimplePattern}
     */
    def infixPattern(): Tree =
      infixOps(simplePattern(), in.canStartExprTokens, simplePattern, isOperator = in.name != nme.raw.BAR)

    /** SimplePattern    ::= PatVar
     *                    |  Literal
     *                    |  Quoted
     *                    |  XmlPattern
     *                    |  `(' [Patterns] `)'
     *                    |  SimplePattern1 [TypeArgs] [ArgumentPatterns]
     *  SimplePattern1   ::= Path
     *                    |  SimplePattern1 `.' id
     *  PatVar           ::= id
     *                    |  `_'
     */
    val simplePattern: () => Tree = () => in.token match {
      case IDENTIFIER | BACKQUOTED_IDENT | THIS =>
        path(thisOK = true) match {
          case id @ Ident(nme.raw.MINUS) if isNumericLit => literal(startOffset(id))
          case t => simplePatternRest(t)
        }
      case USCORE =>
        val wildIdent = wildcardIdent()

        // compatibility for Scala2 `x @ _*` and `_*` syntax
        // `x: _*' is parsed in `ascription'
        if (isIdent(nme.raw.STAR)) {
          in.nextToken()
          if (in.token != RPAREN) syntaxError(SeqWildcardPatternPos(), wildIdent.span)
          atSpan(wildIdent.span) { Ident(tpnme.WILDCARD_STAR) }
        }
        else wildIdent
      case LPAREN =>
        atSpan(in.offset) { makeTupleOrParens(inParens(patternsOpt())) }
      case QUOTE =>
        simpleExpr()
      case XMLSTART =>
        xmlLiteralPattern()
      case _ =>
        if (isLiteral) literal(inPattern = true)
        else {
          syntaxErrorOrIncomplete(IllegalStartOfSimplePattern(), expectedOffset)
          errorTermTree
        }
    }

    def simplePatternRest(t: Tree): Tree = {
      var p = t
      if (in.token == LBRACKET)
        p = atSpan(startOffset(t), in.offset) { TypeApply(p, typeArgs(namedOK = false, wildOK = false)) }
      if (in.token == LPAREN)
        p = atSpan(startOffset(t), in.offset) { Apply(p, argumentPatterns()) }
      p
    }

    /** Patterns          ::=  Pattern [`,' Pattern]
     */
    def patterns(): List[Tree] = commaSeparated(pattern)

    def patternsOpt(): List[Tree] =
      if (in.token == RPAREN) Nil else patterns()


    /** ArgumentPatterns  ::=  ‘(’ [Patterns] ‘)’
     *                      |  ‘(’ [Patterns ‘,’] Pattern2 ‘:’ ‘_’ ‘*’ ‘)’
     */
    def argumentPatterns(): List[Tree] =
      inParens(patternsOpt())

/* -------- MODIFIERS and ANNOTATIONS ------------------------------------------- */

    private def modOfToken(tok: Int, name: Name): Mod = tok match {
      case ABSTRACT    => Mod.Abstract()
      case FINAL       => Mod.Final()
      case IMPLICIT    => Mod.Implicit()
      case GIVEN       => Mod.Given()
      case ERASED      => Mod.Erased()
      case LAZY        => Mod.Lazy()
      case OVERRIDE    => Mod.Override()
      case PRIVATE     => Mod.Private()
      case PROTECTED   => Mod.Protected()
      case SEALED      => Mod.Sealed()
      case IDENTIFIER =>
        name match {
          case nme.inline => Mod.Inline()
          case nme.opaque => Mod.Opaque()
          case nme.open => Mod.Open()
          case nme.transparent => Mod.Transparent()
        }
    }

    /** Drop `private' modifier when followed by a qualifier.
     *  Contract `abstract' and `override' to ABSOVERRIDE
     */
    private def normalize(mods: Modifiers): Modifiers =
      if (mods.is(Private) && mods.hasPrivateWithin)
        normalize(mods &~ Private)
      else if (mods.isAllOf(AbstractOverride))
        normalize(addFlag(mods &~ (Abstract | Override), AbsOverride))
      else
        mods

    private def addModifier(mods: Modifiers): Modifiers = {
      val tok = in.token
      val name = in.name
      val mod = atSpan(in.skipToken()) { modOfToken(tok, name) }

      if (mods.isOneOf(mod.flags)) syntaxError(RepeatedModifier(mod.flags.flagsString))
      addMod(mods, mod)
    }

    def addFlag(mods: Modifiers, flag: FlagSet): Modifiers =
      mods.withAddedFlags(flag, Span(in.offset))

    /** Always add the syntactic `mod`, but check and conditionally add semantic `mod.flags`
     */
    def addMod(mods: Modifiers, mod: Mod): Modifiers =
      addFlag(mods, mod.flags).withAddedMod(mod)

    /** AccessQualifier ::= "[" (id | this) "]"
     */
    def accessQualifierOpt(mods: Modifiers): Modifiers =
      if (in.token == LBRACKET) {
        if (mods.is(Local) || mods.hasPrivateWithin)
          syntaxError(DuplicatePrivateProtectedQualifier())
        inBrackets {
          if in.token == THIS then
            if sourceVersion.isAtLeast(`3.1`) then
              deprecationWarning("The [this] qualifier is deprecated in Scala 3.1; it should be dropped.")
            in.nextToken()
            mods | Local
          else mods.withPrivateWithin(ident().toTypeName)
        }
      }
      else mods

    /** {Annotation} {Modifier}
     *  Modifiers      ::= {Modifier}
     *  LocalModifiers ::= {LocalModifier}
     *  AccessModifier ::= (private | protected) [AccessQualifier]
     *  Modifier       ::= LocalModifier
     *                  |  AccessModifier
     *                  |  override
     *                  |  opaque
     *  LocalModifier  ::= abstract | final | sealed | open | implicit | lazy | erased | inline | transparent
     */
    def modifiers(allowed: BitSet = modifierTokens, start: Modifiers = Modifiers()): Modifiers = {
      @tailrec
      def loop(mods: Modifiers): Modifiers =
        if (allowed.contains(in.token) ||
            in.isSoftModifier &&
              localModifierTokens.subsetOf(allowed)) { // soft modifiers are admissible everywhere local modifiers are
          val isAccessMod = accessModifierTokens contains in.token
          val mods1 = addModifier(mods)
          loop(if (isAccessMod) accessQualifierOpt(mods1) else mods1)
        }
        else if (in.token == NEWLINE && (mods.hasFlags || mods.hasAnnotations)) {
          in.nextToken()
          loop(mods)
        }
        else
          mods
      val result = normalize(loop(start))
      for case mod @ Mod.Transparent() <- result.mods do
        if !result.is(Inline) then
          syntaxError(em"`transparent` can only be used in conjunction with `inline`", mod.span)
      result
    }

    val funTypeArgMods: BitSet = BitSet(ERASED)

    /** Wrap annotation or constructor in New(...).<init> */
    def wrapNew(tpt: Tree): Select = Select(New(tpt), nme.CONSTRUCTOR)

    /** Adjust start of annotation or constructor to offset of preceding @ or new */
    def adjustStart(start: Offset)(tree: Tree): Tree = {
      val tree1 = tree match {
        case Apply(fn, args) => cpy.Apply(tree)(adjustStart(start)(fn), args)
        case Select(qual, name) => cpy.Select(tree)(adjustStart(start)(qual), name)
        case _ => tree
      }
      if (tree1.span.exists && start < tree1.span.start)
        tree1.withSpan(tree1.span.withStart(start))
      else tree1
    }

    /** Annotation        ::=  `@' SimpleType {ParArgumentExprs}
     */
    def annot(): Tree =
      adjustStart(accept(AT)) {
        ensureApplied(parArgumentExprss(wrapNew(simpleType())))
      }

    def annotations(skipNewLines: Boolean = false): List[Tree] = {
      if (skipNewLines) newLineOptWhenFollowedBy(AT)
      if (in.token == AT) annot() :: annotations(skipNewLines)
      else Nil
    }

    def annotsAsMods(skipNewLines: Boolean = false): Modifiers =
      Modifiers() withAnnotations annotations(skipNewLines)

    def defAnnotsMods(allowed: BitSet): Modifiers =
      modifiers(allowed, annotsAsMods(skipNewLines = true))

 /* -------- PARAMETERS ------------------------------------------- */

    /** ClsTypeParamClause::=  ‘[’ ClsTypeParam {‘,’ ClsTypeParam} ‘]’
     *  ClsTypeParam      ::=  {Annotation} [‘+’ | ‘-’]
     *                         id [HkTypeParamClause] TypeParamBounds
     *
     *  DefTypeParamClause::=  ‘[’ DefTypeParam {‘,’ DefTypeParam} ‘]’
     *  DefTypeParam      ::=  {Annotation} id [HkTypeParamClause] TypeParamBounds
     *
     *  TypTypeParamClause::=  ‘[’ TypTypeParam {‘,’ TypTypeParam} ‘]’
     *  TypTypeParam      ::=  {Annotation} id [HkTypePamClause] TypeBounds
     *
     *  HkTypeParamClause ::=  ‘[’ HkTypeParam {‘,’ HkTypeParam} ‘]’
     *  HkTypeParam       ::=  {Annotation} [‘+’ | ‘-’] (id [HkTypePamClause] | ‘_’) TypeBounds
     */
    def typeParamClause(ownerKind: ParamOwner.Value): List[TypeDef] = inBrackets {

      def variance(vflag: FlagSet): FlagSet =
        if ownerKind == ParamOwner.Def || ownerKind == ParamOwner.TypeParam then
          syntaxError(i"no `+/-` variance annotation allowed here")
          in.nextToken()
          EmptyFlags
        else
          in.nextToken()
          vflag

      def typeParam(): TypeDef = {
        val isAbstractOwner = ownerKind == ParamOwner.Type || ownerKind == ParamOwner.TypeParam
        val start = in.offset
        val mods =
          annotsAsMods()
          | (if (ownerKind == ParamOwner.Class) Param | PrivateLocal else Param)
          | (if isIdent(nme.raw.PLUS) then variance(Covariant)
             else if isIdent(nme.raw.MINUS) then variance(Contravariant)
             else EmptyFlags)
        atSpan(start, nameStart) {
          val name =
            if (isAbstractOwner && in.token == USCORE) {
              in.nextToken()
              WildcardParamName.fresh().toTypeName
            }
            else ident().toTypeName
          val hkparams = typeParamClauseOpt(ParamOwner.Type)
          val bounds = if (isAbstractOwner) typeBounds() else typeParamBounds(name)
          TypeDef(name, lambdaAbstract(hkparams, bounds)).withMods(mods)
        }
      }
      commaSeparated(() => typeParam())
    }

    def typeParamClauseOpt(ownerKind: ParamOwner.Value): List[TypeDef] =
      if (in.token == LBRACKET) typeParamClause(ownerKind) else Nil

    /** ContextTypes   ::=  Type {‘,’ Type}
     */
    def contextTypes(ofClass: Boolean, nparams: Int): List[ValDef] =
      val tps = commaSeparated(typ)
      var counter = nparams
      def nextIdx = { counter += 1; counter }
      val paramFlags = if ofClass then Private | Local | ParamAccessor else Param
      tps.map(makeSyntheticParameter(nextIdx, _, paramFlags | Synthetic | Given))

    /** ClsParamClause    ::=  ‘(’ [‘erased’] ClsParams ‘)’ | UsingClsParamClause
     *  UsingClsParamClause::= ‘(’ ‘using’ [‘erased’] (ClsParams | ContextTypes) ‘)’
     *  ClsParams         ::=  ClsParam {‘,’ ClsParam}
     *  ClsParam          ::=  {Annotation}
     *
     *  DefParamClause    ::=  ‘(’ [‘erased’] DefParams ‘)’ | UsingParamClause
     *  UsingParamClause  ::=  ‘(’ ‘using’ [‘erased’] (DefParams | ContextTypes) ‘)’
     *  DefParams         ::=  DefParam {‘,’ DefParam}
     *  DefParam          ::=  {Annotation} [‘inline’] Param
     *
     *  Param             ::=  id `:' ParamType [`=' Expr]
     *
     *  @return   the list of parameter definitions
     */
    def paramClause(nparams: Int,                            // number of parameters preceding this clause
                    ofClass: Boolean = false,                // owner is a class
                    ofCaseClass: Boolean = false,            // owner is a case class
                    prefix: Boolean = false,                 // clause precedes name of an extension method
                    givenOnly: Boolean = false,              // only given parameters allowed
                    firstClause: Boolean = false             // clause is the first in regular list of clauses
                   ): List[ValDef] = {
      var impliedMods: Modifiers = EmptyModifiers

      def addParamMod(mod: () => Mod) = impliedMods = addMod(impliedMods, atSpan(in.skipToken()) { mod() })

      def paramMods() =
        if in.token == IMPLICIT then addParamMod(() => Mod.Implicit())
        else
          if isIdent(nme.using) then addParamMod(() => Mod.Given())
          if in.token == ERASED then addParamMod(() => Mod.Erased())

      def param(): ValDef = {
        val start = in.offset
        var mods = impliedMods.withAnnotations(annotations())
        if (ofClass) {
          mods = addFlag(modifiers(start = mods), ParamAccessor)
          mods =
            if in.token == VAL then
              in.nextToken()
              mods
            else if in.token == VAR then
              val mod = atSpan(in.skipToken()) { Mod.Var() }
              addMod(mods, mod)
            else
              if (!(mods.flags &~ (ParamAccessor | Inline | impliedMods.flags)).isEmpty)
                syntaxError("`val` or `var` expected")
              if (firstClause && ofCaseClass) mods
              else mods | PrivateLocal
        }
        else {
          if (isIdent(nme.inline) && in.isSoftModifierInParamModifierPosition)
            mods = addModifier(mods)
          mods |= Param
        }
        atSpan(start, nameStart) {
          val name = ident()
          accept(COLON)
          if (in.token == ARROW && ofClass && !mods.is(Local))
            syntaxError(VarValParametersMayNotBeCallByName(name, mods.is(Mutable)))
          val tpt = paramType()
          val default =
            if (in.token == EQUALS) { in.nextToken(); subExpr() }
            else EmptyTree
          if (impliedMods.mods.nonEmpty)
            impliedMods = impliedMods.withMods(Nil) // keep only flags, so that parameter positions don't overlap
          ValDef(name, tpt, default).withMods(mods)
        }
      }

      def checkVarArgsRules(vparams: List[ValDef]): Unit = vparams match {
        case Nil =>
        case _ :: Nil if !prefix =>
        case vparam :: rest =>
          vparam.tpt match {
            case PostfixOp(_, op) if op.name == tpnme.raw.STAR =>
              syntaxError(VarArgsParamMustComeLast(), vparam.tpt.span)
            case _ =>
          }
          checkVarArgsRules(rest)
      }

      // begin paramClause
      inParens {
        if in.token == RPAREN && !prefix && !impliedMods.is(Given) then Nil
        else
          val clause =
            if prefix then param() :: Nil
            else
              paramMods()
              if givenOnly && !impliedMods.is(Given) then
                syntaxError("`using` expected")
              val isParams =
                !impliedMods.is(Given)
                || startParamTokens.contains(in.token)
                || isIdent && (in.name == nme.inline || in.lookaheadIn(BitSet(COLON)))
              if isParams then commaSeparated(() => param())
              else contextTypes(ofClass, nparams)
          checkVarArgsRules(clause)
          clause
      }
    }

    /** ClsParamClauses   ::=  {ClsParamClause} [[nl] ‘(’ [‘implicit’] ClsParams ‘)’]
     *  DefParamClauses   ::=  {DefParamClause} [[nl] ‘(’ [‘implicit’] DefParams ‘)’]
     *
     *  @return  The parameter definitions
     */
    def paramClauses(ofClass: Boolean = false,
                     ofCaseClass: Boolean = false,
                     givenOnly: Boolean = false): List[List[ValDef]] =

      def recur(firstClause: Boolean, nparams: Int): List[List[ValDef]] =
        newLineOptWhenFollowedBy(LPAREN)
        if in.token == LPAREN then
          val paramsStart = in.offset
          val params = paramClause(
              nparams,
              ofClass = ofClass,
              ofCaseClass = ofCaseClass,
              givenOnly = givenOnly,
              firstClause = firstClause)
          val lastClause = params.nonEmpty && params.head.mods.flags.is(Implicit)
          params :: (
            if lastClause then Nil
            else recur(firstClause = false, nparams + params.length))
        else Nil
      end recur

      recur(firstClause = true, 0)
    end paramClauses

/* -------- DEFS ------------------------------------------- */

    def finalizeDef(md: MemberDef, mods: Modifiers, start: Int): md.ThisTree[Untyped] =
      md.withMods(mods).setComment(in.getDocComment(start))

    type ImportConstr = (Tree, List[ImportSelector]) => Tree

    /** Import  ::= `import' [`given'] [ImportExpr {`,' ImportExpr}
     *  Export  ::= `export' [`given'] [ImportExpr {`,' ImportExpr}
     */
    def importClause(leading: Token, mkTree: ImportConstr): List[Tree] = {
      val offset = accept(leading)
      commaSeparated(importExpr(mkTree)) match {
        case t :: rest =>
          // The first import should start at the start offset of the keyword.
          val firstPos =
            if (t.span.exists) t.span.withStart(offset)
            else Span(offset, in.lastOffset)
          t.withSpan(firstPos) :: rest
        case nil => nil
      }
    }

    /** Create an import node and handle source version imports */
    def mkImport(outermost: Boolean = false): ImportConstr = (tree, selectors) =>
      val isLanguageImport = tree match
        case Ident(nme.language) => true
        case Select(Ident(nme.scala), nme.language) => true
        case _ => false
      if isLanguageImport then
        for
          case ImportSelector(id @ Ident(imported), EmptyTree, _) <- selectors
          if allSourceVersionNames.contains(imported)
        do
          if !outermost then
            syntaxError(i"source version import is only allowed at the toplevel", id.span)
          else if ctx.compilationUnit.sourceVersion.isDefined then
            syntaxError(i"duplicate source version import", id.span)
          else
            ctx.compilationUnit.sourceVersion = Some(SourceVersion.valueOf(imported.toString))
      Import(tree, selectors)

    /**  ImportExpr ::= StableId ‘.’ ImportSpec
     *   ImportSpec  ::=  id
     *                 | ‘_’
     *                 | ‘{’ ImportSelectors) ‘}’
     */
    def importExpr(mkTree: ImportConstr): () => Tree = {

      /** '_' */
      def wildcardSelectorId() = atSpan(in.skipToken()) { Ident(nme.WILDCARD) }

      /** ImportSelectors  ::=  id [‘=>’ id | ‘=>’ ‘_’] [‘,’ ImportSelectors]
       *                     |  WildCardSelector {‘,’ WildCardSelector}
       *  WildCardSelector ::=  ‘given’ (‘_' | InfixType)
       *                     |  ‘_'
       */
      def importSelectors(idOK: Boolean): List[ImportSelector] =
        val isWildcard = in.token == USCORE || in.token == GIVEN
        val selector = atSpan(in.offset) {
          in.token match
            case USCORE =>
              ImportSelector(wildcardSelectorId())
            case GIVEN =>
              val start = in.skipToken()
              def givenSelector() = atSpan(start) { Ident(nme.EMPTY) }
              if in.token == USCORE then
                in.nextToken()
                ImportSelector(givenSelector()) // Let the selector span all of `given _`; needed for -Ytest-pickler
              else
                ImportSelector(givenSelector(), bound = infixType())
            case _ =>
              val from = termIdent()
              if !idOK then syntaxError(i"named imports cannot follow wildcard imports")
              if in.token == ARROW then
                atSpan(startOffset(from), in.skipToken()) {
                  val to = if in.token == USCORE then wildcardIdent() else termIdent()
                  ImportSelector(from, if to.name == nme.ERROR then EmptyTree else to)
                }
              else ImportSelector(from)
        }
        val rest =
          if in.token == COMMA then
            in.nextToken()
            importSelectors(idOK = idOK && !isWildcard)
          else
            Nil
        selector :: rest

      val handleImport: Tree => Tree = tree =>
        in.token match
          case USCORE =>
            mkTree(tree, ImportSelector(wildcardSelectorId()) :: Nil)
          case LBRACE =>
            mkTree(tree, inBraces(importSelectors(idOK = true)))
          case _ =>
            tree

      () => {
        val p = path(thisOK = false, handleImport)
        p match
          case _: Import | _: Export => p
          case sel @ Select(qual, name) =>
            val selector = ImportSelector(atSpan(pointOffset(sel)) { Ident(name) })
            mkTree(qual, selector :: Nil).withSpan(sel.span)
          case t =>
            accept(DOT)
            mkTree(t, ImportSelector(Ident(nme.WILDCARD)) :: Nil)
      }
    }

    def posMods(start: Int, mods: Modifiers): Modifiers = {
      in.nextToken()
      mods
    }

    /** Def      ::= val PatDef
     *             | var VarDef
     *             | def DefDef
     *             | type {nl} TypeDcl
     *             | TmplDef
     *  Dcl      ::= val ValDcl
     *             | var ValDcl
     *             | def DefDcl
     *             | type {nl} TypeDcl
     *  EnumCase ::= `case' (id ClassConstr [`extends' ConstrApps]] | ids)
     */
    def defOrDcl(start: Int, mods: Modifiers): Tree = in.token match {
      case VAL =>
        in.nextToken()
        patDefOrDcl(start, mods)
      case VAR =>
        val mod = atSpan(in.skipToken()) { Mod.Var() }
        val mod1 = addMod(mods, mod)
        patDefOrDcl(start, mod1)
      case DEF =>
        defDefOrDcl(start, posMods(start, mods))
      case TYPE =>
        typeDefOrDcl(start, posMods(start, mods))
      case CASE if inEnum =>
        enumCase(start, mods)
      case _ =>
        tmplDef(start, mods)
    }

    /** PatDef  ::=  ids [‘:’ Type] ‘=’ Expr
     *            |  Pattern2 [‘:’ Type | Ascription] ‘=’ Expr
     *  VarDef  ::=  PatDef | id {`,' id} `:' Type `=' `_'
     *  ValDcl  ::=  id {`,' id} `:' Type
     *  VarDcl  ::=  id {`,' id} `:' Type
     */
    def patDefOrDcl(start: Offset, mods: Modifiers): Tree = atSpan(start, nameStart) {
      val first = pattern2()
      var lhs = first match {
        case id: Ident if in.token == COMMA =>
          in.nextToken()
          id :: commaSeparated(() => termIdent())
        case _ =>
          first :: Nil
      }
      def emptyType = TypeTree().withSpan(Span(in.lastOffset))
      val tpt =
        if (in.token == COLON) {
          in.nextToken()
          if (in.token == AT && lhs.tail.isEmpty) {
            lhs = ascription(first, Location.ElseWhere) :: Nil
            emptyType
          }
          else toplevelTyp()
        }
        else emptyType
      val rhs =
        if tpt.isEmpty || in.token == EQUALS then
          endMarkerScope(first) {
            accept(EQUALS)
            subExpr() match
              case rhs0 @ Ident(name) if placeholderParams.nonEmpty && name == placeholderParams.head.name
                  && !tpt.isEmpty && mods.is(Mutable) && lhs.forall(_.isInstanceOf[Ident]) =>
                placeholderParams = placeholderParams.tail
                atSpan(rhs0.span) { Ident(nme.WILDCARD) }
              case rhs0 => rhs0
          }
        else EmptyTree
      lhs match {
        case IdPattern(id, t) :: Nil if t.isEmpty =>
          val vdef = ValDef(id.name.asTermName, tpt, rhs)
          if (isBackquoted(id)) vdef.pushAttachment(Backquoted, ())
          finalizeDef(vdef, mods, start)
        case _ =>
          def isAllIds = lhs.forall {
            case IdPattern(id, t) => t.isEmpty
            case _ => false
          }
          if rhs.isEmpty && !isAllIds then
            syntaxError(ExpectedTokenButFound(EQUALS, in.token), Span(in.lastOffset))
          PatDef(mods, lhs, tpt, rhs)
      }
    }

    /** DefDef  ::=  DefSig [(‘:’ | ‘<:’) Type] ‘=’ Expr
     *            |  this ParamClause ParamClauses `=' ConstrExpr
     *  DefDcl  ::=  DefSig `:' Type
     *  DefSig  ::=  id [DefTypeParamClause] DefParamClauses
     *            |  ExtParamClause [nl] [‘.’] id DefParamClauses
     */
    def defDefOrDcl(start: Offset, mods: Modifiers): Tree = atSpan(start, nameStart) {

      def scala2ProcedureSyntax(resultTypeStr: String) =
        def toInsert =
          if in.token == LBRACE then s"$resultTypeStr ="
          else ": Unit "  // trailing space ensures that `def f()def g()` works.
        if migrateTo3 then
          ctx.errorOrMigrationWarning(
            s"Procedure syntax no longer supported; `$toInsert` should be inserted here",
            in.sourcePos())
          patch(source, Span(in.lastOffset), toInsert)
          true
        else
          false

      if (in.token == THIS) {
        in.nextToken()
        val vparamss = paramClauses()
        if (vparamss.isEmpty || vparamss.head.take(1).exists(_.mods.isOneOf(GivenOrImplicit)))
          in.token match {
            case LBRACKET   => syntaxError("no type parameters allowed here")
            case EOF        => incompleteInputError(AuxConstructorNeedsNonImplicitParameter())
            case _          => syntaxError(AuxConstructorNeedsNonImplicitParameter(), nameStart)
          }
        if (migrateTo3) newLineOptWhenFollowedBy(LBRACE)
        val rhs = {
          if (!(in.token == LBRACE && scala2ProcedureSyntax(""))) accept(EQUALS)
          atSpan(in.offset) { subPart(constrExpr) }
        }
        makeConstructor(Nil, vparamss, rhs).withMods(mods).setComment(in.getDocComment(start))
      }
      else {
        var mods1 = addFlag(mods, Method)
        var isInfix = false
        def extParamss() =
          try paramClause(0, prefix = true) :: Nil
          finally
            mods1 = addFlag(mods, Extension)
            if in.token == DOT then in.nextToken()
            else
              isInfix = true
              newLineOpt()
        val (leadingTparams, leadingVparamss) =
          if in.token == LBRACKET then
            (typeParamClause(ParamOwner.Def), extParamss())
          else if in.token == LPAREN then
            (Nil, extParamss())
          else
            (Nil, Nil)
        val ident = termIdent()
        val name = ident.name.asTermName
        if isInfix && !name.isOperatorName then
          val infixAnnot = Apply(wrapNew(scalaAnnotationDot(tpnme.infix)), Nil)
              .withSpan(Span(start, start))
          mods1 = mods1.withAddedAnnotation(infixAnnot)
        val tparams =
          if in.token == LBRACKET then
            if mods1.is(Extension) then
              if leadingTparams.isEmpty then
                deprecationWarning("type parameters in extension methods should be written after `def`")
              else
                syntaxError("no type parameters allowed here")
            typeParamClause(ParamOwner.Def)
          else leadingTparams
        val vparamss = paramClauses() match
          case rparams :: rparamss if leadingVparamss.nonEmpty && !isLeftAssoc(ident.name) =>
            rparams :: leadingVparamss ::: rparamss
          case rparamss =>
            leadingVparamss ::: rparamss
        var tpt = fromWithinReturnType {
          if in.token == SUBTYPE && mods.is(Inline) && AllowOldWhiteboxSyntax then
            in.nextToken()
            mods1 = addMod(mods1, Mod.Transparent())
            toplevelTyp()
          else typedOpt()
        }
        if (migrateTo3) newLineOptWhenFollowedBy(LBRACE)
        val rhs =
          if (in.token == EQUALS)
            in.endMarkerScope(name) {
              in.nextToken()
              subExpr()
            }
          else if (!tpt.isEmpty)
            EmptyTree
          else if (scala2ProcedureSyntax(": Unit")) {
            tpt = scalaUnit
            if (in.token == LBRACE) expr()
            else EmptyTree
          }
          else {
            if (!isExprIntro) syntaxError(MissingReturnType(), in.lastOffset)
            accept(EQUALS)
            expr()
          }

        val ddef = DefDef(name, tparams, vparamss, tpt, rhs)
        if (isBackquoted(ident)) ddef.pushAttachment(Backquoted, ())
        finalizeDef(ddef, mods1, start)
      }
    }

    /** ConstrExpr      ::=  SelfInvocation
     *                    |  `{' SelfInvocation {semi BlockStat} `}'
     */
    val constrExpr: () => Tree = () =>
      if (in.isNestedStart)
        atSpan(in.offset) {
          inBracesOrIndented {
            val stats = selfInvocation() :: (
              if (isStatSep) { in.nextToken(); blockStatSeq() }
              else Nil)
            Block(stats, Literal(Constant(())))
          }
        }
      else Block(selfInvocation() :: Nil, Literal(Constant(())))

    /** SelfInvocation  ::= this ArgumentExprs {ArgumentExprs}
     */
    def selfInvocation(): Tree =
      atSpan(accept(THIS)) {
        possibleBracesStart()
        argumentExprss(mkApply(Ident(nme.CONSTRUCTOR), argumentExprs()))
      }

    /** TypeDcl ::=  id [TypeParamClause] TypeBounds [‘=’ Type]
     */
    def typeDefOrDcl(start: Offset, mods: Modifiers): Tree = {
      newLinesOpt()
      atSpan(start, nameStart) {
        val nameIdent = typeIdent()
        val tparams = typeParamClauseOpt(ParamOwner.Type)
        def makeTypeDef(rhs: Tree): Tree = {
          val rhs1 = lambdaAbstract(tparams, rhs)
          val tdef = TypeDef(nameIdent.name.toTypeName, rhs1)
          if (nameIdent.isBackquoted)
            tdef.pushAttachment(Backquoted, ())
          finalizeDef(tdef, mods, start)
        }
        in.token match {
          case EQUALS =>
            in.nextToken()
            makeTypeDef(toplevelTyp())
          case SUBTYPE | SUPERTYPE =>
            val bounds = typeBounds()
            if (in.token == EQUALS) {
              val eqOffset = in.skipToken()
              var rhs = toplevelTyp()
              rhs match {
                case mtt: MatchTypeTree =>
                  bounds match {
                    case TypeBoundsTree(EmptyTree, upper, _) =>
                      rhs = MatchTypeTree(upper, mtt.selector, mtt.cases)
                    case _ =>
                      syntaxError(i"cannot combine lower bound and match type alias", eqOffset)
                  }
                case _ =>
                  if mods.is(Opaque) then
                    rhs = TypeBoundsTree(bounds.lo, bounds.hi, rhs)
                  else
                    syntaxError(i"cannot combine bound and alias", eqOffset)
              }
              makeTypeDef(rhs)
            }
            else makeTypeDef(bounds)
          case SEMI | NEWLINE | NEWLINES | COMMA | RBRACE | OUTDENT | EOF =>
            makeTypeDef(typeBounds())
          case _ =>
            syntaxErrorOrIncomplete(ExpectedTypeBoundOrEquals(in.token))
            return EmptyTree // return to avoid setting the span to EmptyTree
        }
      }
    }

    /** TmplDef ::=  ([‘case’] ‘class’ | ‘trait’) ClassDef
     *            |  [‘case’] ‘object’ ObjectDef
     *            |  ‘enum’ EnumDef
     *            |  ‘given’ GivenDef
     *            |  ‘extension’ ExtensionDef
     */
    def tmplDef(start: Int, mods: Modifiers): Tree =
      in.token match {
        case TRAIT =>
          classDef(start, posMods(start, addFlag(mods, Trait)))
        case CLASS =>
          classDef(start, posMods(start, mods))
        case CASECLASS =>
          classDef(start, posMods(start, mods | Case))
        case OBJECT =>
          objectDef(start, posMods(start, mods | Module))
        case CASEOBJECT =>
          objectDef(start, posMods(start, mods | Case | Module))
        case ENUM =>
          enumDef(start, posMods(start, mods | Enum))
        case GIVEN =>
          givenDef(start, mods, atSpan(in.skipToken()) { Mod.Given() })
        case _ =>
          if isIdent(nme.extension) && followingIsExtension() then
            extensionDef(start, mods)
          else
            syntaxErrorOrIncomplete(ExpectedStartOfTopLevelDefinition())
            EmptyTree
      }

    /** ClassDef ::= id ClassConstr TemplateOpt
     */
    def classDef(start: Offset, mods: Modifiers): TypeDef = atSpan(start, nameStart) {
      classDefRest(start, mods, ident().toTypeName)
    }

    def classDefRest(start: Offset, mods: Modifiers, name: TypeName): TypeDef =
      in.endMarkerScope(name.toTermName) {
        val constr = classConstr(isCaseClass = mods.is(Case))
        val templ = templateOpt(constr)
        finalizeDef(TypeDef(name, templ), mods, start)
      }

    /** ClassConstr ::= [ClsTypeParamClause] [ConstrMods] ClsParamClauses
     */
    def classConstr(isCaseClass: Boolean = false): DefDef = atSpan(in.lastOffset) {
      val tparams = typeParamClauseOpt(ParamOwner.Class)
      val cmods = fromWithinClassConstr(constrModsOpt())
      val vparamss = paramClauses(ofClass = true, ofCaseClass = isCaseClass)
      makeConstructor(tparams, vparamss).withMods(cmods)
    }

    /** ConstrMods        ::=  {Annotation} [AccessModifier]
     */
    def constrModsOpt(): Modifiers =
      modifiers(accessModifierTokens, annotsAsMods())

    /** ObjectDef       ::= id TemplateOpt
     */
    def objectDef(start: Offset, mods: Modifiers): ModuleDef = atSpan(start, nameStart) {
      val name = ident()
      in.endMarkerScope(name) {
        val templ = templateOpt(emptyConstructor)
        finalizeDef(ModuleDef(name, templ), mods, start)
      }
    }

    private def checkAccessOnly(mods: Modifiers, where: String): Modifiers =
      val mods1 = mods & (AccessFlags | Enum)
      if mods1 ne mods then
        syntaxError(s"Only access modifiers are allowed on enum $where")
      mods1

    /**  EnumDef ::=  id ClassConstr InheritClauses EnumBody
     */
    def enumDef(start: Offset, mods: Modifiers): TypeDef = atSpan(start, nameStart) {
      val mods1 = checkAccessOnly(mods, "definitions")
      val modulName = ident()
      in.endMarkerScope(modulName) {
        val clsName = modulName.toTypeName
        val constr = classConstr()
        val templ = template(constr, isEnum = true)
        finalizeDef(TypeDef(clsName, templ), mods1, start)
      }
    }

    /** EnumCase = `case' (id ClassConstr [`extends' ConstrApps] | ids)
     */
    def enumCase(start: Offset, mods: Modifiers): DefTree = {
      val mods1 = checkAccessOnly(mods, "cases") | EnumCase
      accept(CASE)

      atSpan(start, nameStart) {
        val id = termIdent()
        if (in.token == COMMA) {
          in.nextToken()
          val ids = commaSeparated(() => termIdent())
          PatDef(mods1, id :: ids, TypeTree(), EmptyTree)
        }
        else {
          val caseDef =
            if (in.token == LBRACKET || in.token == LPAREN || in.token == AT || isModifier) {
              val clsName = id.name.toTypeName
              val constr = classConstr(isCaseClass = true)
              TypeDef(clsName, caseTemplate(constr))
            }
            else
              ModuleDef(id.name.toTermName, caseTemplate(emptyConstructor))
          finalizeDef(caseDef, mods1, start)
        }
      }
    }

    /** [`extends' ConstrApps] */
    def caseTemplate(constr: DefDef): Template = {
      val parents =
        if (in.token == EXTENDS) {
          in.nextToken()
          constrApps(commaOK = true, templateCanFollow = false)
        }
        else Nil
      Template(constr, parents, Nil, EmptyValDef, Nil)
    }

    def checkExtensionMethod(tparams: List[Tree],
        vparamss: List[List[Tree]], stat: Tree): Unit = stat match {
      case stat: DefDef =>
        if stat.mods.is(Extension) && vparamss.nonEmpty then
          syntaxError(i"no extension method allowed here since leading parameter was already given", stat.span)
        else if !stat.mods.is(Extension) && vparamss.isEmpty then
          syntaxError(i"an extension method is required here", stat.span)
        else if tparams.nonEmpty && stat.tparams.nonEmpty then
          syntaxError(i"extension method cannot have type parameters since some were already given previously",
            stat.tparams.head.span)
        else if stat.rhs.isEmpty then
          syntaxError(i"extension method cannot be abstract", stat.span)
      case EmptyTree =>
      case stat =>
        syntaxError(i"extension clause can only define methods", stat.span)
    }

    /** GivenDef          ::=  [GivenSig] [‘_’ ‘<:’] Type ‘=’ Expr
     *                      |  [GivenSig] ConstrApps [TemplateBody]
     *  GivenSig          ::=  [id] [DefTypeParamClause] {UsingParamClauses} ‘as’
     */
    def givenDef(start: Offset, mods: Modifiers, givenMod: Mod) = atSpan(start, nameStart) {
      var mods1 = addMod(mods, givenMod)
      val hasGivenSig = followingIsGivenSig()
      val nameStart = in.offset
      val name = if isIdent && hasGivenSig then ident() else EmptyTermName

      val gdef = in.endMarkerScope(if name.isEmpty then GIVEN else name) {
        val tparams = typeParamClauseOpt(ParamOwner.Def)
        newLineOpt()
        val vparamss =
          if in.token == LPAREN && in.lookaheadIn(nme.using)
          then paramClauses(givenOnly = true)
          else Nil
        newLinesOpt()
        if isIdent(nme.as) || !name.isEmpty || !tparams.isEmpty || !vparamss.isEmpty then
          accept(nme.as)
        def givenAlias(tpt: Tree) =
          accept(EQUALS)
          mods1 |= Final
          DefDef(name, tparams, vparamss, tpt, subExpr())
        if in.token == USCORE && AllowOldWhiteboxSyntax then
          if !mods.is(Inline) then
            syntaxError("`_ <:` is only allowed for given with `inline` modifier")
          in.nextToken()
          accept(SUBTYPE)
          mods1 = addMod(mods1, Mod.Transparent())
          givenAlias(toplevelTyp())
        else
          val parents = constrApps(commaOK = true, templateCanFollow = true)
          if in.token == EQUALS && parents.length == 1 && parents.head.isType then
            givenAlias(parents.head)
          else
            possibleTemplateStart()
            val tparams1 = tparams.map(tparam => tparam.withMods(tparam.mods | PrivateLocal))
            val vparamss1 = vparamss.map(_.map(vparam =>
              vparam.withMods(vparam.mods &~ Param | ParamAccessor | Protected)))
            val templ = templateBodyOpt(makeConstructor(tparams1, vparamss1), parents, Nil)
            if tparams.isEmpty && vparamss.isEmpty then ModuleDef(name, templ)
            else TypeDef(name.toTypeName, templ)
      }
      finalizeDef(gdef, mods1, start)
    }

    /** ExtensionDef  ::=  [id] [‘on’ ExtParamClause {UsingParamClause}] TemplateBody
     */
    def extensionDef(start: Offset, mods: Modifiers): ModuleDef =
      in.nextToken()
      val nameOffset = in.offset
      val name = if isIdent && !isIdent(nme.on) then ident() else EmptyTermName
      in.endMarkerScope(if name.isEmpty then nme.extension else name) {
        val (tparams, vparamss, extensionFlag) =
          if isIdent(nme.on) then
            in.nextToken()
            val tparams = typeParamClauseOpt(ParamOwner.Def)
            val extParams = paramClause(0, prefix = true)
            val givenParamss = paramClauses(givenOnly = true)
            (tparams, extParams :: givenParamss, Extension)
          else
            (Nil, Nil, EmptyFlags)
        possibleTemplateStart()
        if !in.isNestedStart then syntaxError("Extension without extension methods")
        val templ = templateBodyOpt(makeConstructor(tparams, vparamss), Nil, Nil)
        templ.body.foreach(checkExtensionMethod(tparams, vparamss, _))
        val edef = atSpan(start, nameOffset, in.offset)(ModuleDef(name, templ))
        finalizeDef(edef, addFlag(mods, Given | extensionFlag), start)
      }

/* -------- TEMPLATES ------------------------------------------- */

    /** SimpleConstrApp  ::=  AnnotType {ParArgumentExprs}
     */
    val constrApp: () => Tree = () => {
      val t = rejectWildcardType(annotType(), fallbackTree = Ident(nme.ERROR))
        // Using Ident(nme.ERROR) to avoid causing cascade errors on non-user-written code
      if in.token == LPAREN then parArgumentExprss(wrapNew(t)) else t
    }

    /** ConstrApps  ::=  ConstrApp {(‘,’ | ‘with’) ConstrApp}
     */
    def constrApps(commaOK: Boolean, templateCanFollow: Boolean): List[Tree] =
      val t = constrApp()
      val ts =
        if in.token == WITH then
          in.nextToken()
          newLineOptWhenFollowedBy(LBRACE)
          if templateCanFollow && (in.token == LBRACE || in.token == INDENT) then
            Nil
          else
            constrApps(commaOK, templateCanFollow)
        else if commaOK && in.token == COMMA then
          in.nextToken()
          constrApps(commaOK, templateCanFollow)
        else Nil
      t :: ts

    /** Template          ::=  InheritClauses [TemplateBody]
     *  InheritClauses    ::=  [‘extends’ ConstrApps] [‘derives’ QualId {‘,’ QualId}]
     */
    def template(constr: DefDef, isEnum: Boolean = false): Template = {
      val parents =
        if (in.token == EXTENDS) {
          in.nextToken()
          if (in.token == LBRACE || in.token == COLONEOL) {
            ctx.errorOrMigrationWarning(
              "`extends` must be followed by at least one parent",
              in.sourcePos())
            Nil
          }
          else constrApps(commaOK = true, templateCanFollow = true)
        }
        else Nil
      newLinesOptWhenFollowedBy(nme.derives)
      val derived =
        if (isIdent(nme.derives)) {
          in.nextToken()
          tokenSeparated(COMMA, () => convertToTypeId(qualId()))
        }
        else Nil
      possibleTemplateStart()
      if (isEnum) {
        val (self, stats) = withinEnum(templateBody())
        Template(constr, parents, derived, self, stats)
      }
      else templateBodyOpt(constr, parents, derived)
    }

    /** TemplateOpt = [Template]
     */
    def templateOpt(constr: DefDef): Template =
      newLinesOptWhenFollowedBy(nme.derives)
      if in.token == EXTENDS || isIdent(nme.derives) then
        template(constr)
      else
        possibleTemplateStart()
        if in.isNestedStart then
          template(constr)
        else
          checkNextNotIndented()
          Template(constr, Nil, Nil, EmptyValDef, Nil)

    /** TemplateBody ::=  [nl] `{' TemplateStatSeq `}'
     *  EnumBody     ::=  [nl] ‘{’ [SelfType] EnumStat {semi EnumStat} ‘}’
     */
    def templateBodyOpt(constr: DefDef, parents: List[Tree], derived: List[Tree]): Template =
      val (self, stats) =
        if in.isNestedStart then
          templateBody()
        else
          checkNextNotIndented()
          (EmptyValDef, Nil)
      Template(constr, parents, derived, self, stats)

    def templateBody(): (ValDef, List[Tree]) =
      val r = inDefScopeBraces { templateStatSeq() }
      if in.token == WITH then
        syntaxError(EarlyDefinitionsNotSupported())
        in.nextToken()
        template(emptyConstructor)
      r

/* -------- STATSEQS ------------------------------------------- */

    /** Create a tree representing a packaging */
    def makePackaging(start: Int, pkg: Tree, stats: List[Tree]): PackageDef = pkg match {
      case x: RefTree => atSpan(start, pointOffset(pkg))(PackageDef(x, stats))
    }

    /** Packaging ::= package QualId [nl] `{' TopStatSeq `}'
     */
    def packaging(start: Int): Tree = {
      val pkg = qualId()
      endMarkerScope(pkg) {
        possibleTemplateStart()
        val stats = inDefScopeBraces(topStatSeq())
        makePackaging(start, pkg, stats)
      }
    }

    /** TopStatSeq ::= TopStat {semi TopStat}
     *  TopStat ::= Import
     *            | Export
     *            | Annotations Modifiers Def
     *            | Packaging
     *            | package object objectDef
     *            |
     */
    def topStatSeq(outermost: Boolean = false): List[Tree] = {
      val stats = new ListBuffer[Tree]
      while (!isStatSeqEnd) {
        setLastStatOffset()
        if (in.token == PACKAGE) {
          val start = in.skipToken()
          if (in.token == OBJECT) {
            in.nextToken()
            stats += objectDef(start, Modifiers(Package))
          }
          else stats += packaging(start)
        }
        else if (in.token == IMPORT)
          stats ++= importClause(IMPORT, mkImport(outermost))
        else if (in.token == EXPORT)
          stats ++= importClause(EXPORT, Export.apply)
        else if (in.token == AT || isDefIntro(modifierTokens))
          stats +++= defOrDcl(in.offset, defAnnotsMods(modifierTokens))
        else if (!isStatSep)
          if (in.token == CASE)
            syntaxErrorOrIncomplete(OnlyCaseClassOrCaseObjectAllowed())
          else
            syntaxErrorOrIncomplete(ExpectedToplevelDef())
        acceptStatSepUnlessAtEnd()
      }
      stats.toList
    }

    /** TemplateStatSeq  ::= [id [`:' Type] `=>'] TemplateStat {semi TemplateStat}
     *  TemplateStat     ::= Import
     *                     | Export
     *                     | Annotations Modifiers Def
     *                     | Annotations Modifiers Dcl
     *                     | Expr1
     *                     |
     *  EnumStat         ::= TemplateStat
     *                     | Annotations Modifiers EnumCase
     */
    def templateStatSeq(): (ValDef, List[Tree]) = checkNoEscapingPlaceholders {
      var self: ValDef = EmptyValDef
      val stats = new ListBuffer[Tree]
      if (isExprIntro && !isDefIntro(modifierTokens)) {
        val first = expr1()
        if (in.token == ARROW) {
          first match {
            case Typed(tree @ This(EmptyTypeIdent), tpt) =>
              self = makeSelfDef(nme.WILDCARD, tpt).withSpan(first.span)
            case _ =>
              val ValDef(name, tpt, _) = convertToParam(first, EmptyModifiers, "self type clause")
              if (name != nme.ERROR)
                self = makeSelfDef(name, tpt).withSpan(first.span)
          }
          in.token = EMPTY // hack to suppress INDENT insertion after `=>`
          in.nextToken()
        }
        else {
          stats += first
          acceptStatSepUnlessAtEnd()
        }
      }
      var exitOnError = false
      while (!isStatSeqEnd && !exitOnError) {
        setLastStatOffset()
        if (in.token == IMPORT)
          stats ++= importClause(IMPORT, mkImport())
        else if (in.token == EXPORT)
          stats ++= importClause(EXPORT, Export.apply)
        else if (isDefIntro(modifierTokensOrCase))
          stats +++= defOrDcl(in.offset, defAnnotsMods(modifierTokens))
        else if (isExprIntro)
          stats += expr1()
        else if (!isStatSep) {
          exitOnError = mustStartStat
          syntaxErrorOrIncomplete("illegal start of definition")
        }
        acceptStatSepUnlessAtEnd()
      }
      (self, if (stats.isEmpty) List(EmptyTree) else stats.toList)
    }

    /** RefineStatSeq    ::=  RefineStat {semi RefineStat}
     *  RefineStat       ::=  ‘val’ VarDcl
     *                     |  ‘def’ DefDcl
     *                     |  ‘type’ {nl} TypeDcl
     *  (in reality we admit Defs and vars and filter them out afterwards in `checkLegal`)
     */
    def refineStatSeq(): List[Tree] = {
      val stats = new ListBuffer[Tree]
      def checkLegal(tree: Tree): List[Tree] =
        val problem = tree match
          case tree: MemberDef if !(tree.mods.flags & ModifierFlags).isEmpty =>
            i"refinement cannot be ${(tree.mods.flags & ModifierFlags).flagStrings().mkString("`", "`, `", "`")}"
          case tree: DefDef if tree.vparamss.exists(_.exists(!_.rhs.isEmpty)) =>
            i"refinement cannot have default arguments"
          case tree: ValOrDefDef =>
            if tree.rhs.isEmpty then ""
            else "refinement cannot have a right-hand side"
          case tree: TypeDef =>
            if !tree.isClassDef then ""
            else "refinement cannot be a class or trait"
          case _ =>
            "this kind of definition cannot be a refinement"
        if problem.isEmpty then tree :: Nil
        else { syntaxError(problem, tree.span); Nil }

      while (!isStatSeqEnd) {
        if (isDclIntro)
          stats ++= checkLegal(defOrDcl(in.offset, Modifiers()))
        else if (!isStatSep)
          syntaxErrorOrIncomplete(
            "illegal start of declaration" +
            (if (inFunReturnType) " (possible cause: missing `=` in front of current method body)"
             else ""))
        acceptStatSepUnlessAtEnd()
      }
      stats.toList
    }

    def localDef(start: Int, implicitMods: Modifiers = EmptyModifiers): Tree = {
      var mods = defAnnotsMods(localModifierTokens)
      for (imod <- implicitMods.mods) mods = addMod(mods, imod)
      if (mods.is(Final))
        // A final modifier means the local definition is "class-like".  // FIXME: Deal with modifiers separately
        tmplDef(start, mods)
      else
        defOrDcl(start, mods)
    }

    /** BlockStatSeq ::= { BlockStat semi } [Expr]
     *  BlockStat    ::= Import
     *                 | Annotations [implicit] [lazy] Def
     *                 | Annotations LocalModifiers TmplDef
     *                 | Expr1
     *                 |
     */
    def blockStatSeq(): List[Tree] = checkNoEscapingPlaceholders {
      val stats = new ListBuffer[Tree]
      var exitOnError = false
      while (!isStatSeqEnd && in.token != CASE && !exitOnError) {
        setLastStatOffset()
        if (in.token == IMPORT)
          stats ++= importClause(IMPORT, mkImport())
        else if (isExprIntro)
          stats += expr(Location.InBlock)
        else if in.token == IMPLICIT && !in.inModifierPosition() then
          stats += closure(in.offset, Location.InBlock, modifiers(BitSet(IMPLICIT)))
        else if isDefIntro(localModifierTokens, excludedSoftModifiers = Set(nme.`opaque`)) then
          stats +++= localDef(in.offset)
        else if (!isStatSep && (in.token != CASE)) {
          exitOnError = mustStartStat
          syntaxErrorOrIncomplete(IllegalStartOfStatement(isModifier))
        }
        acceptStatSepUnlessAtEnd(CASE)
      }
      stats.toList
    }

    /** CompilationUnit ::= {package QualId semi} TopStatSeq
     */
    def compilationUnit(): Tree = checkNoEscapingPlaceholders {
      def topstats(): List[Tree] = {
        val ts = new ListBuffer[Tree]
        while (in.token == SEMI) in.nextToken()
        val start = in.offset
        if (in.token == PACKAGE) {
          in.nextToken()
          if (in.token == OBJECT) {
            in.nextToken()
            ts += objectDef(start, Modifiers(Package))
            if (in.token != EOF) {
              acceptStatSep()
              ts ++= topStatSeq()
            }
          }
          else
            val pkg = qualId()
            var continue = false
            endMarkerScope(pkg) {
              possibleTemplateStart()
              if in.token == EOF then
                ts += makePackaging(start, pkg, List())
              else if in.isNestedStart then
                ts += inDefScopeBraces(makePackaging(start, pkg, topStatSeq()))
                continue = true
              else
                acceptStatSep()
                ts += makePackaging(start, pkg, topstats())
            }
            if continue then
              acceptStatSepUnlessAtEnd()
              ts ++= topStatSeq()
        }
        else
          ts ++= topStatSeq(outermost = true)

        ts.toList
      }

      topstats() match {
        case List(stat @ PackageDef(_, _)) => stat
        case Nil => EmptyTree  // without this case we'd get package defs without positions
        case stats => PackageDef(Ident(nme.EMPTY_PACKAGE), stats)
      }
    }
  }

  /** OutlineParser parses top-level declarations in `source` to find declared classes, ignoring their bodies (which
   *  must only have balanced braces). This is used to map class names to defining sources.
   */
  class OutlineParser(source: SourceFile)(implicit ctx: Context) extends Parser(source) with OutlineParserCommon {

    def skipBracesHook(): Option[Tree] =
      if (in.token == XMLSTART) Some(xmlLiteral()) else None

    override def blockExpr(): Tree = {
      skipBraces()
      EmptyTree
    }

    override def templateBody(): (ValDef, List[Thicket]) = {
      skipBraces()
      (EmptyValDef, List(EmptyTree))
    }
  }
}
