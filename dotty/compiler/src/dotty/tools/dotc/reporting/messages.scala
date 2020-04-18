package dotty.tools
package dotc
package reporting

import core._
import Contexts.Context
import Decorators._, Symbols._, Names._, NameOps._, Types._, Flags._
import Denotations.SingleDenotation
import SymDenotations.SymDenotation
import util.SourcePosition
import parsing.Scanners.Token
import parsing.Tokens
import printing.Highlighting._
import printing.Formatting
import ErrorMessageID._
import ast.Trees
import config.{Feature, ScalaVersion}
import typer.ErrorReporting.{Errors, err}
import typer.ProtoTypes.ViewProto
import scala.util.control.NonFatal
import StdNames.nme
import printing.Formatting.hl

/**  Messages
  *  ========
  *  The role of messages is to provide the necessary details for a simple to
  *  understand diagnostic event. Each message can be turned into a message
  *  container (one of the above) by calling the appropriate method on them.
  *  For instance:
  *
  *  ```scala
  *  EmptyCatchBlock(tree).error(pos)   // res: Error
  *  EmptyCatchBlock(tree).warning(pos) // res: Warning
  *  ```
  */
object messages {

  import ast.Trees._
  import ast.untpd
  import ast.tpd

  /** Helper methods for messages */
  def implicitClassRestrictionsText(implicit ctx: Context): String =
    em"""|For a full list of restrictions on implicit classes visit
         |${Blue("http://docs.scala-lang.org/overviews/core/implicit-classes.html")}"""

  abstract class SyntaxMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Syntax"

  abstract class TypeMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Type"

  abstract class TypeMismatchMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Type Mismatch"

  abstract class NamingMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Naming"

  abstract class DeclarationMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Declaration"

  /** A simple not found message (either for idents, or member selection.
   *  Messages of this class are sometimes dropped in favor of other, more
   *  specific messages.
   */
  abstract class NotFoundMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Not Found"
    def name: Name

  abstract class PatternMatchMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Pattern Match"

  abstract class CyclicMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Cyclic"

  abstract class ReferenceMsg(errorId: ErrorMessageID) extends Message(errorId):
    def kind = "Reference"

  abstract class EmptyCatchOrFinallyBlock(tryBody: untpd.Tree, errNo: ErrorMessageID)(implicit ctx: Context)
  extends SyntaxMsg(EmptyCatchOrFinallyBlockID) {
    def explain = {
      val tryString = tryBody match {
        case Block(Nil, untpd.EmptyTree) => "{}"
        case _ => tryBody.show
      }

      val code1 =
        s"""|import scala.util.control.NonFatal
            |
            |try $tryString catch {
            |  case NonFatal(e) => ???
            |}""".stripMargin

      val code2 =
        s"""|try $tryString finally {
            |  // perform your cleanup here!
            |}""".stripMargin

      em"""|A ${hl("try")} expression should be followed by some mechanism to handle any exceptions
           |thrown. Typically a ${hl("catch")} expression follows the ${hl("try")} and pattern matches
           |on any expected exceptions. For example:
           |
           |$code1
           |
           |It is also possible to follow a ${hl("try")} immediately by a ${hl("finally")} - letting the
           |exception propagate - but still allowing for some clean up in ${hl("finally")}:
           |
           |$code2
           |
           |It is recommended to use the ${hl("NonFatal")} extractor to catch all exceptions as it
           |correctly handles transfer functions like ${hl("return")}."""
    }
  }

  class EmptyCatchBlock(tryBody: untpd.Tree)(implicit ctx: Context)
  extends EmptyCatchOrFinallyBlock(tryBody, EmptyCatchBlockID) {
    def msg =
      em"""|The ${hl("catch")} block does not contain a valid expression, try
           |adding a case like - ${hl("case e: Exception =>")} to the block"""
  }

  class EmptyCatchAndFinallyBlock(tryBody: untpd.Tree)(implicit ctx: Context)
  extends EmptyCatchOrFinallyBlock(tryBody, EmptyCatchAndFinallyBlockID) {
    def msg =
      em"""|A ${hl("try")} without ${hl("catch")} or ${hl("finally")} is equivalent to putting
           |its body in a block; no exceptions are handled."""
  }

  class DeprecatedWithOperator()(implicit ctx: Context)
  extends SyntaxMsg(DeprecatedWithOperatorID) {
    def msg =
      em"""${hl("with")} as a type operator has been deprecated; use ${hl("&")} instead"""
    def explain =
      em"""|Dotty introduces intersection types - ${hl("&")} types. These replace the
           |use of the ${hl("with")} keyword. There are a few differences in
           |semantics between intersection types and using ${hl("with")}."""
  }

  class CaseClassMissingParamList(cdef: untpd.TypeDef)(implicit ctx: Context)
  extends SyntaxMsg(CaseClassMissingParamListID) {
    def msg =
      em"""|A ${hl("case class")} must have at least one parameter list"""

    def explain =
      em"""|${cdef.name} must have at least one parameter list, if you would rather
           |have a singleton representation of ${cdef.name}, use a "${hl("case object")}".
           |Or, add an explicit ${hl("()")} as a parameter list to ${cdef.name}."""
  }

  class AnonymousFunctionMissingParamType(param: untpd.ValDef,
                                          args: List[untpd.Tree],
                                          tree: untpd.Function,
                                          pt: Type)
                                          (implicit ctx: Context)
  extends TypeMsg(AnonymousFunctionMissingParamTypeID) {
    def msg = {
      val ofFun =
        if (MethodType.syntheticParamNames(args.length + 1) contains param.name)
          i" of expanded function:\n$tree"
        else
          ""

      val inferred =
        if (pt == WildcardType) ""
        else i"\nWhat I could infer was: $pt"

      i"""Missing parameter type
         |
         |I could not infer the type of the parameter ${param.name}$ofFun.$inferred"""
    }

    def explain = ""
  }

  class WildcardOnTypeArgumentNotAllowedOnNew()(implicit ctx: Context)
  extends SyntaxMsg(WildcardOnTypeArgumentNotAllowedOnNewID) {
    def msg = "Type argument must be fully defined"
    def explain =
      val code1: String =
        """
          |object TyperDemo {
          |  class Team[A]
          |  val team = new Team[?]
          |}
        """.stripMargin

      val code2: String =
        """
          |object TyperDemo {
          |  class Team[A]
          |  val team = new Team[Int]
          |}
        """.stripMargin
      em"""|Wildcard on arguments is not allowed when declaring a new type.
           |
           |Given the following example:
           |
           |$code1
           |
           |You must complete all the type parameters, for instance:
           |
           |$code2 """
  }


  // Type Errors ------------------------------------------------------------ //
  class DuplicateBind(bind: untpd.Bind, tree: untpd.CaseDef)(implicit ctx: Context)
  extends NamingMsg(DuplicateBindID) {
    def msg = em"duplicate pattern variable: ${bind.name}"

    def explain = {
      val pat = tree.pat.show
      val guard = tree.guard match {
        case untpd.EmptyTree => ""
        case guard => s"if ${guard.show}"
      }

      val body = tree.body match {
        case Block(Nil, untpd.EmptyTree) => ""
        case body => s" ${body.show}"
      }

      val caseDef = s"case $pat$guard => $body"

      em"""|For each ${hl("case")} bound variable names have to be unique. In:
           |
           |$caseDef
           |
           |${bind.name} is not unique. Rename one of the bound variables!"""
    }
  }

  class MissingIdent(tree: untpd.Ident, treeKind: String, val name: Name)(implicit ctx: Context)
  extends NotFoundMsg(MissingIdentID) {
    def msg = em"Not found: $treeKind$name"
    def explain = {
      em"""|The identifier for `$treeKind$name` is not bound, that is,
           |no declaration for this identifier can be found.
           |That can happen, for example, if `$name` or its declaration has either been
           |misspelt or if an import is missing."""
    }
  }

  class TypeMismatch(found: Type, expected: Type, addenda: => String*)(implicit ctx: Context)
    extends TypeMismatchMsg(TypeMismatchID):

    // replace constrained TypeParamRefs and their typevars by their bounds where possible
    // the idea is that if the bounds are also not-subtypes of each other to report
    // the type mismatch on the bounds instead of the original TypeParamRefs, since
    // these are usually easier to analyze.
    object reported extends TypeMap:
      def setVariance(v: Int) = variance = v
      val constraint = mapCtx.typerState.constraint
      def apply(tp: Type): Type = tp match
        case tp: TypeParamRef =>
          constraint.entry(tp) match
            case bounds: TypeBounds =>
              if variance < 0 then apply(mapCtx.typeComparer.fullUpperBound(tp))
              else if variance > 0 then apply(mapCtx.typeComparer.fullLowerBound(tp))
              else tp
            case NoType => tp
            case instType => apply(instType)
        case tp: TypeVar => apply(tp.stripTypeVar)
        case _ => mapOver(tp)

    def msg =
      val found1 = reported(found)
      reported.setVariance(-1)
      val expected1 = reported(expected)
      val (found2, expected2) =
        if (found1 frozen_<:< expected1) (found, expected) else (found1, expected1)
      val postScript = addenda.find(!_.isEmpty) match
        case Some(p) => p
        case None =>
          if expected.isAny
             || expected.isAnyRef
             || expected.isRef(defn.AnyValClass)
             || defn.isBottomType(found)
          then ""
          else ctx.typer.importSuggestionAddendum(ViewProto(found.widen, expected))
      val (where, printCtx) = Formatting.disambiguateTypes(found2, expected2)
      val whereSuffix = if (where.isEmpty) where else s"\n\n$where"
      val (foundStr, expectedStr) = Formatting.typeDiff(found2, expected2)(printCtx)
      s"""|Found:    $foundStr
          |Required: $expectedStr""".stripMargin
        + whereSuffix + err.whyNoMatchStr(found, expected) + postScript

    def explain = ""
  end TypeMismatch

  class NotAMember(site: Type, val name: Name, selected: String, addendum: => String = "")(implicit ctx: Context)
  extends NotFoundMsg(NotAMemberID) {
    //println(i"site = $site, decls = ${site.decls}, source = ${site.widen.typeSymbol.sourceFile}") //DEBUG

    def msg = {
      import core.Flags._
      val maxDist = 3  // maximal number of differences to be considered for a hint
      val missing = name.show

      // The names of all non-synthetic, non-private members of `site`
      // that are of the same type/term kind as the missing member.
      def candidates: Set[String] =
        for
          bc <- site.widen.baseClasses.toSet
          sym <- bc.info.decls.filter(sym =>
            sym.isType == name.isTypeName
            && !sym.isConstructor
            && !sym.flagsUNSAFE.isOneOf(Synthetic | Private))
        yield sym.name.show

      // Calculate Levenshtein distance
      def distance(s1: String, s2: String): Int =
        val dist = Array.ofDim[Int](s2.length + 1, s1.length + 1)
        for
          j <- 0 to s2.length
          i <- 0 to s1.length
        do
          dist(j)(i) =
            if j == 0 then i
            else if i == 0 then j
            else if s2(j - 1) == s1(i - 1) then dist(j - 1)(i - 1)
            else (dist(j - 1)(i) min dist(j)(i - 1) min dist(j - 1)(i - 1)) + 1
        dist(s2.length)(s1.length)

      // A list of possible candidate strings with their Levenstein distances
      // to the name of the missing member
      def closest: List[(Int, String)] = candidates
        .toList
        .map(n => (distance(n.show, missing), n))
        .filter((d, n) => d <= maxDist && d < missing.length && d < n.length)
        .sorted  // sort by distance first, alphabetically second

      val finalAddendum =
        if addendum.nonEmpty then addendum
        else closest match {
          case (d, n) :: _ =>
            val siteName = site match
              case site: NamedType => site.name.show
              case site => i"$site"
            s" - did you mean $siteName.$n?"
          case Nil => ""
        }

      ex"$selected $name is not a member of ${site.widen}$finalAddendum"
    }

    def explain = ""
  }

  class EarlyDefinitionsNotSupported()(implicit ctx: Context)
  extends SyntaxMsg(EarlyDefinitionsNotSupportedID) {
    def msg = "Early definitions are not supported; use trait parameters instead"

    def explain = {
      val code1 =
        """|trait Logging {
           |  val f: File
           |  f.open()
           |  onExit(f.close())
           |  def log(msg: String) = f.write(msg)
           |}
           |
           |class B extends Logging {
           |  val f = new File("log.data") // triggers a NullPointerException
           |}
           |
           |// early definition gets around the NullPointerException
           |class C extends {
           |  val f = new File("log.data")
           |} with Logging""".stripMargin

      val code2 =
        """|trait Logging(f: File) {
           |  f.open()
           |  onExit(f.close())
           |  def log(msg: String) = f.write(msg)
           |}
           |
           |class C extends Logging(new File("log.data"))""".stripMargin

      em"""|Earlier versions of Scala did not support trait parameters and "early
           |definitions" (also known as "early initializers") were used as an alternative.
           |
           |Example of old syntax:
           |
           |$code1
           |
           |The above code can now be written as:
           |
           |$code2
           |"""
    }
  }

  class TopLevelImplicitClass(cdef: untpd.TypeDef)(implicit ctx: Context)
  extends SyntaxMsg(TopLevelImplicitClassID) {
    def msg = em"""An ${hl("implicit class")} may not be top-level"""

    def explain = {
      val TypeDef(name, impl @ Template(constr0, parents, self, _)) = cdef
      val exampleArgs =
        if(constr0.vparamss.isEmpty) "..."
        else constr0.vparamss(0).map(_.withMods(untpd.Modifiers()).show).mkString(", ")
      def defHasBody[T] = impl.body.exists(!_.isEmpty)
      val exampleBody = if (defHasBody) "{\n ...\n }" else ""
      em"""|There may not be any method, member or object in scope with the same name as
           |the implicit class and a case class automatically gets a companion object with
           |the same name created by the compiler which would cause a naming conflict if it
           |were allowed.
           |
           |""" + implicitClassRestrictionsText + em"""|
           |
           |To resolve the conflict declare ${cdef.name} inside of an ${hl("object")} then import the class
           |from the object at the use site if needed, for example:
           |
           |object Implicits {
           |  implicit class ${cdef.name}($exampleArgs)$exampleBody
           |}
           |
           |// At the use site:
           |import Implicits.${cdef.name}"""
    }
  }

  class ImplicitCaseClass(cdef: untpd.TypeDef)(implicit ctx: Context)
  extends SyntaxMsg(ImplicitCaseClassID) {
    def msg = em"""A ${hl("case class")} may not be defined as ${hl("implicit")}"""

    def explain =
      em"""|Implicit classes may not be case classes. Instead use a plain class:
           |
           |implicit class ${cdef.name}...
           |
           |""" + implicitClassRestrictionsText
  }

  class ImplicitClassPrimaryConstructorArity()(implicit ctx: Context)
  extends SyntaxMsg(ImplicitClassPrimaryConstructorArityID){
    def msg = "Implicit classes must accept exactly one primary constructor parameter"
    def explain = {
      val example = "implicit class RichDate(date: java.util.Date)"
      em"""Implicit classes may only take one non-implicit argument in their constructor. For example:
          |
          | $example
          |
          |While it’s possible to create an implicit class with more than one non-implicit argument,
          |such classes aren’t used during implicit lookup.
          |""" + implicitClassRestrictionsText
    }
  }

  class ObjectMayNotHaveSelfType(mdef: untpd.ModuleDef)(implicit ctx: Context)
  extends SyntaxMsg(ObjectMayNotHaveSelfTypeID) {
    def msg = em"""${hl("object")}s must not have a self ${hl("type")}"""

    def explain = {
      val untpd.ModuleDef(name, tmpl) = mdef
      val ValDef(_, selfTpt, _) = tmpl.self
      em"""|${hl("object")}s must not have a self ${hl("type")}:
           |
           |Consider these alternative solutions:
           |  - Create a trait or a class instead of an object
           |  - Let the object extend a trait containing the self type:
           |
           |    object $name extends ${selfTpt.show}"""
    }
  }

  class RepeatedModifier(modifier: String)(implicit ctx:Context)
  extends SyntaxMsg(RepeatedModifierID) {
    def msg = em"""Repeated modifier $modifier"""

    def explain = {
      val code1 = em"""private private val Origin = Point(0, 0)"""
      val code2 = em"""private final val Origin = Point(0, 0)"""
      em"""This happens when you accidentally specify the same modifier twice.
           |
           |Example:
           |
           |$code1
           |
           |instead of
           |
           |$code2
           |
           |"""
    }
  }

  class InterpolatedStringError()(implicit ctx:Context)
  extends SyntaxMsg(InterpolatedStringErrorID) {
    def msg = "Error in interpolated string: identifier or block expected"
    def explain = {
      val code1 = "s\"$new Point(0, 0)\""
      val code2 = "s\"${new Point(0, 0)}\""
      em"""|This usually happens when you forget to place your expressions inside curly braces.
           |
           |$code1
           |
           |should be written as
           |
           |$code2
           |"""
    }
  }

  class UnboundPlaceholderParameter()(implicit ctx:Context)
  extends SyntaxMsg(UnboundPlaceholderParameterID) {
    def msg = em"""Unbound placeholder parameter; incorrect use of ${hl("_")}"""
    def explain =
      em"""|The ${hl("_")} placeholder syntax was used where it could not be bound.
           |Consider explicitly writing the variable binding.
           |
           |This can be done by replacing ${hl("_")} with a variable (eg. ${hl("x")})
           |and adding ${hl("x =>")} where applicable.
           |
           |Example before:
           |
           |${hl("{ _ }")}
           |
           |Example after:
           |
           |${hl("x => { x }")}
           |
           |Another common occurrence for this error is defining a val with ${hl("_")}:
           |
           |${hl("val a = _")}
           |
           |But this val definition isn't very useful, it can never be assigned
           |another value. And thus will always remain uninitialized.
           |Consider replacing the ${hl("val")} with ${hl("var")}:
           |
           |${hl("var a = _")}
           |
           |Note that this use of ${hl("_")} is not placeholder syntax,
           |but an uninitialized var definition.
           |Only fields can be left uninitialized in this manner; local variables
           |must be initialized.
           |"""
  }

  class IllegalStartSimpleExpr(illegalToken: String)(implicit ctx: Context)
  extends SyntaxMsg(IllegalStartSimpleExprID) {
    def msg = em"expression expected but ${Red(illegalToken)} found"
    def explain = {
      em"""|An expression cannot start with ${Red(illegalToken)}."""
    }
  }

  class MissingReturnType()(implicit ctx:Context)
  extends SyntaxMsg(MissingReturnTypeID) {
    def msg = "Missing return type"
    def explain =
      em"""|An abstract declaration must have a return type. For example:
           |
           |trait Shape {hl(
           |  def area: Double // abstract declaration returning a ${"Double"}
           |)}"""
  }

  class MissingReturnTypeWithReturnStatement(method: Symbol)(implicit ctx: Context)
  extends SyntaxMsg(MissingReturnTypeWithReturnStatementID) {
    def msg = em"$method has a return statement; it needs a result type"
    def explain =
      em"""|If a method contains a ${hl("return")} statement, it must have an
           |explicit return type. For example:
           |
           |${hl("def good: Int /* explicit return type */ = return 1")}"""
  }

  class YieldOrDoExpectedInForComprehension()(implicit ctx: Context)
  extends SyntaxMsg(YieldOrDoExpectedInForComprehensionID) {
    def msg = em"${hl("yield")} or ${hl("do")} expected"

    def explain =
      em"""|When the enumerators in a for comprehension are not placed in parentheses or
           |braces, a ${hl("do")} or ${hl("yield")} statement is required after the enumerators
           |section of the comprehension.
           |
           |You can save some keystrokes by omitting the parentheses and writing
           |
           |${hl("val numbers = for i <- 1 to 3 yield i")}
           |
           |  instead of
           |
           |${hl("val numbers = for (i <- 1 to 3) yield i")}
           |
           |but the ${hl("yield")} keyword is still required.
           |
           |For comprehensions that simply perform a side effect without yielding anything
           |can also be written without parentheses but a ${hl("do")} keyword has to be
           |included. For example,
           |
           |${hl("for (i <- 1 to 3) println(i)")}
           |
           |can be written as
           |
           |${hl("for i <- 1 to 3 do println(i) // notice the 'do' keyword")}
           |
           |"""
  }

  class ProperDefinitionNotFound()(implicit ctx: Context)
  extends Message(ProperDefinitionNotFoundID) {
    def kind: String = "Doc Comment"
    def msg = em"""Proper definition was not found in ${hl("@usecase")}"""

    def explain = {
      val noUsecase =
        "def map[B, That](f: A => B)(implicit bf: CanBuildFrom[List[A], B, That]): That"

      val usecase =
        """|/** Map from List[A] => List[B]
           |  *
           |  * @usecase def map[B](f: A => B): List[B]
           |  */
           |def map[B, That](f: A => B)(implicit bf: CanBuildFrom[List[A], B, That]): That
           |""".stripMargin

      em"""|Usecases are only supported for ${hl("def")}s. They exist because with Scala's
           |advanced type-system, we sometimes end up with seemingly scary signatures.
           |The usage of these methods, however, needs not be - for instance the ${hl("map")}
           |function
           |
           |${hl("List(1, 2, 3).map(2 * _) // res: List(2, 4, 6)")}
           |
           |is easy to understand and use - but has a rather bulky signature:
           |
           |$noUsecase
           |
           |to mitigate this and ease the usage of such functions we have the ${hl("@usecase")}
           |annotation for docstrings. Which can be used like this:
           |
           |$usecase
           |
           |When creating the docs, the signature of the method is substituted by the
           |usecase and the compiler makes sure that it is valid. Because of this, you're
           |only allowed to use ${hl("def")}s when defining usecases."""
    }
  }

  class ByNameParameterNotSupported(tpe: untpd.TypTree)(implicit ctx: Context)
  extends SyntaxMsg(ByNameParameterNotSupportedID) {
    def msg = em"By-name parameter type ${tpe} not allowed here."

    def explain =
      em"""|By-name parameters act like functions that are only evaluated when referenced,
           |allowing for lazy evaluation of a parameter.
           |
           |An example of using a by-name parameter would look like:
           |${hl("def func(f: => Boolean) = f // 'f' is evaluated when referenced within the function")}
           |
           |An example of the syntax of passing an actual function as a parameter:
           |${hl("def func(f: (Boolean => Boolean)) = f(true)")}
           |
           |or:
           |
           |${hl("def func(f: Boolean => Boolean) = f(true)")}
           |
           |And the usage could be as such:
           |${hl("func(bool => // do something...)")}
           |"""
  }

  class WrongNumberOfTypeArgs(fntpe: Type, expectedArgs: List[ParamInfo], actual: List[untpd.Tree])(implicit ctx: Context)
  extends SyntaxMsg(WrongNumberOfTypeArgsID) {

    private val expectedCount = expectedArgs.length
    private val actualCount = actual.length
    private val msgPrefix = if (actualCount > expectedCount) "Too many" else "Not enough"

    def msg =
      val expectedArgString = expectedArgs
        .map(_.paramName.unexpandedName.show)
        .mkString("[", ", ", "]")
      val actualArgString = actual.map(_.show).mkString("[", ", ", "]")
      val prettyName =
        try fntpe.termSymbol match
          case NoSymbol => fntpe.show
          case symbol   => symbol.showFullName
        catch case NonFatal(ex) => fntpe.show
      em"""|$msgPrefix type arguments for $prettyName$expectedArgString
           |expected: $expectedArgString
           |actual:   $actualArgString""".stripMargin

    def explain = {
      val tooManyTypeParams =
        """|val tuple2: (Int, String) = (1, "one")
           |val list: List[(Int, String)] = List(tuple2)""".stripMargin

      if (actualCount > expectedCount)
        em"""|You have supplied too many type parameters
             |
             |For example List takes a single type parameter (List[A])
             |If you need to hold more types in a list then you need to combine them
             |into another data type that can contain the number of types you need,
             |In this example one solution would be to use a Tuple:
             |
             |${tooManyTypeParams}"""
      else
        em"""|You have not supplied enough type parameters
             |If you specify one type parameter then you need to specify every type parameter."""
    }
  }

  class IllegalVariableInPatternAlternative()(implicit ctx: Context)
  extends SyntaxMsg(IllegalVariableInPatternAlternativeID) {
    def msg = "Variables are not allowed in alternative patterns"
    def explain = {
      val varInAlternative =
        """|def g(pair: (Int,Int)): Int = pair match {
           |  case (1, n) | (n, 1) => n
           |  case _ => 0
           |}""".stripMargin

      val fixedVarInAlternative =
        """|def g(pair: (Int,Int)): Int = pair match {
           |  case (1, n) => n
           |  case (n, 1) => n
           |  case _ => 0
           |}""".stripMargin

      em"""|Variables are not allowed within alternate pattern matches. You can workaround
           |this issue by adding additional cases for each alternative. For example, the
           |illegal function:
           |
           |$varInAlternative
           |could be implemented by moving each alternative into a separate case:
           |
           |$fixedVarInAlternative"""
    }
  }

  class IdentifierExpected(identifier: String)(implicit ctx: Context)
  extends SyntaxMsg(IdentifierExpectedID) {
    def msg = "identifier expected"
    def explain = {
      val wrongIdentifier = em"def foo: $identifier = {...}"
      val validIdentifier = em"def foo = {...}"
      em"""|An identifier expected, but $identifier found. This could be because
           |$identifier is not a valid identifier. As a workaround, the compiler could
           |infer the type for you. For example, instead of:
           |
           |$wrongIdentifier
           |
           |Write your code like:
           |
           |$validIdentifier
           |
           |"""
    }
  }

  class AuxConstructorNeedsNonImplicitParameter()(implicit ctx:Context)
  extends SyntaxMsg(AuxConstructorNeedsNonImplicitParameterID) {
    def msg = "Auxiliary constructor needs non-implicit parameter list"
    def explain =
      em"""|Only the primary constructor is allowed an ${hl("implicit")} parameter list;
           |auxiliary constructors need non-implicit parameter lists. When a primary
           |constructor has an implicit argslist, auxiliary constructors that call the
           |primary constructor must specify the implicit value.
           |
           |To resolve this issue check for:
           | - Forgotten parenthesis on ${hl("this")} (${hl("def this() = { ... }")})
           | - Auxiliary constructors specify the implicit value
           |"""
  }

  class IncorrectRepeatedParameterSyntax()(implicit ctx: Context)
  extends SyntaxMsg(IncorrectRepeatedParameterSyntaxID) {
    def msg = "'*' expected"
    def explain =
      em"""|Expected * in ${hl("_*")} operator.
           |
           |The ${hl("_*")} operator can be used to supply a sequence-based argument
           |to a method with a variable-length or repeated parameter. It is used
           |to expand the sequence to a variable number of arguments, such that:
           |${hl("func(args: _*)")} would expand to ${hl("func(arg1, arg2 ... argN)")}.
           |
           |Below is an example of how a method with a variable-length
           |parameter can be declared and used.
           |
           |Squares the arguments of a variable-length parameter:
           |${hl("def square(args: Int*) = args.map(a => a * a)")}
           |
           |Usage:
           |${hl("square(1, 2, 3) // res0: List[Int] = List(1, 4, 9)")}
           |
           |Secondary Usage with ${hl("_*")}:
           |${hl("val ints = List(2, 3, 4)  // ints: List[Int] = List(2, 3, 4)")}
           |${hl("square(ints: _*)          // res1: List[Int] = List(4, 9, 16)")}
           |""".stripMargin
  }

  class IllegalLiteral()(implicit ctx: Context)
  extends SyntaxMsg(IllegalLiteralID) {
    def msg = "Illegal literal"
    def explain =
      em"""|Available literals can be divided into several groups:
           | - Integer literals: 0, 21, 0xFFFFFFFF, -42L
           | - Floating Point Literals: 0.0, 1e30f, 3.14159f, 1.0e-100, .1
           | - Boolean Literals: true, false
           | - Character Literals: 'a', '\u0041', '\n'
           | - String Literals: "Hello, World!"
           | - null
           |"""
  }

  class PatternMatchExhaustivity(uncoveredFn: => String)(implicit ctx: Context)
  extends Message(PatternMatchExhaustivityID) {
    def kind = "Pattern Match Exhaustivity"
    lazy val uncovered = uncoveredFn
    def msg =
      em"""|${hl("match")} may not be exhaustive.
           |
           |It would fail on pattern case: $uncovered"""


    def explain =
      em"""|There are several ways to make the match exhaustive:
           | - Add missing cases as shown in the warning
           | - If an extractor always return ${hl("Some(...)")}, write ${hl("Some[X]")} for its return type
           | - Add a ${hl("case _ => ...")} at the end to match all remaining cases
           |"""
  }

  class UncheckedTypePattern(msgFn: => String)(implicit ctx: Context)
    extends PatternMatchMsg(UncheckedTypePatternID) {
    def msg = msgFn
    def explain =
      em"""|Type arguments and type refinements are erased during compile time, thus it's
           |impossible to check them at run-time.
           |
           |You can either replace the type arguments by ${hl("_")} or use `@unchecked`.
           |"""
  }

  class MatchCaseUnreachable()(implicit ctx: Context)
  extends Message(MatchCaseUnreachableID) {
    def kind = "Match case Unreachable"
    def msg = "Unreachable case"
    def explain = ""
  }

  class MatchCaseOnlyNullWarning()(implicit ctx: Context)
  extends PatternMatchMsg(MatchCaseOnlyNullWarningID) {
    def msg = em"""Only ${hl("null")} is matched. Consider using ${hl("case null =>")} instead."""
    def explain = ""
  }

  class SeqWildcardPatternPos()(implicit ctx: Context)
  extends SyntaxMsg(SeqWildcardPatternPosID) {
    def msg = em"""${hl("_*")} can be used only for last argument"""
    def explain = {
      val code =
        """def sumOfTheFirstTwo(list: List[Int]): Int = list match {
          |  case List(first, second, x:_*) => first + second
          |  case _ => 0
          |}"""
      em"""|Sequence wildcard pattern is expected at the end of an argument list.
           |This pattern matches any remaining elements in a sequence.
           |Consider the following example:
           |
           |$code
           |
           |Calling:
           |
           |${hl("sumOfTheFirstTwo(List(1, 2, 10))")}
           |
           |would give 3 as a result"""
    }
  }

  class IllegalStartOfSimplePattern()(implicit ctx: Context)
  extends SyntaxMsg(IllegalStartOfSimplePatternID) {
    def msg = "pattern expected"
    def explain = {
      val sipCode =
        """def f(x: Int, y: Int) = x match {
          |  case `y` => ...
          |}
        """
      val constructorPatternsCode =
        """case class Person(name: String, age: Int)
          |
          |def test(p: Person) = p match {
          |  case Person(name, age) => ...
          |}
        """
      val tupplePatternsCode =
        """def swap(tuple: (String, Int)): (Int, String) = tuple match {
          |  case (text, number) => (number, text)
          |}
        """
      val patternSequencesCode =
        """def getSecondValue(list: List[Int]): Int = list match {
          |  case List(_, second, x:_*) => second
          |  case _ => 0
          |}"""
      em"""|Simple patterns can be divided into several groups:
           |- Variable Patterns: ${hl("case x => ...")}.
           |  It matches any value, and binds the variable name to that value.
           |  A special case is the wild-card pattern _ which is treated as if it was a fresh
           |  variable on each occurrence.
           |
           |- Typed Patterns: ${hl("case x: Int => ...")} or ${hl("case _: Int => ...")}.
           |  This pattern matches any value matched by the specified type; it binds the variable
           |  name to that value.
           |
           |- Literal Patterns: ${hl("case 123 => ...")} or ${hl("case 'A' => ...")}.
           |  This type of pattern matches any value that is equal to the specified literal.
           |
           |- Stable Identifier Patterns:
           |
           |  $sipCode
           |
           |  the match succeeds only if the x argument and the y argument of f are equal.
           |
           |- Constructor Patterns:
           |
           |  $constructorPatternsCode
           |
           |  The pattern binds all object's fields to the variable names (name and age, in this
           |  case).
           |
           |- Tuple Patterns:
           |
           |  $tupplePatternsCode
           |
           |  Calling:
           |
           |  ${hl("""swap(("Luftballons", 99)""")}
           |
           |  would give ${hl("""(99, "Luftballons")""")} as a result.
           |
           |- Pattern Sequences:
           |
           |  $patternSequencesCode
           |
           |  Calling:
           |
           |  ${hl("getSecondValue(List(1, 10, 2))")}
           |
           |  would give 10 as a result.
           |  This pattern is possible because a companion object for the List class has a method
           |  with the following signature:
           |
           |  ${hl("def unapplySeq[A](x: List[A]): Some[List[A]]")}
           |"""
    }
  }

  class PkgDuplicateSymbol(existing: Symbol)(implicit ctx: Context)
  extends NamingMsg(PkgDuplicateSymbolID) {
    def msg = em"Trying to define package with same name as $existing"
    def explain = ""
  }

  class ExistentialTypesNoLongerSupported()(implicit ctx: Context)
  extends SyntaxMsg(ExistentialTypesNoLongerSupportedID) {
    def msg =
      em"""|Existential types are no longer supported -
           |use a wildcard or dependent type instead"""
    def explain =
      em"""|The use of existential types is no longer supported.
           |
           |You should use a wildcard or dependent type instead.
           |
           |For example:
           |
           |Instead of using ${hl("forSome")} to specify a type variable
           |
           |${hl("List[T forSome { type T }]")}
           |
           |Try using a wildcard type variable
           |
           |${hl("List[?]")}
           |"""
  }

  class UnboundWildcardType()(implicit ctx: Context)
  extends SyntaxMsg(UnboundWildcardTypeID) {
    def msg = "Unbound wildcard type"
    def explain =
      em"""|The wildcard type syntax (${hl("_")}) was used where it could not be bound.
           |Replace ${hl("_")} with a non-wildcard type. If the type doesn't matter,
           |try replacing ${hl("_")} with ${hl("Any")}.
           |
           |Examples:
           |
           |- Parameter lists
           |
           |  Instead of:
           |    ${hl("def foo(x: _) = ...")}
           |
           |  Use ${hl("Any")} if the type doesn't matter:
           |    ${hl("def foo(x: Any) = ...")}
           |
           |- Type arguments
           |
           |  Instead of:
           |    ${hl("val foo = List[?](1, 2)")}
           |
           |  Use:
           |    ${hl("val foo = List[Int](1, 2)")}
           |
           |- Type bounds
           |
           |  Instead of:
           |    ${hl("def foo[T <: _](x: T) = ...")}
           |
           |  Remove the bounds if the type doesn't matter:
           |    ${hl("def foo[T](x: T) = ...")}
           |
           |- ${hl("val")} and ${hl("def")} types
           |
           |  Instead of:
           |    ${hl("val foo: _ = 3")}
           |
           |  Use:
           |    ${hl("val foo: Int = 3")}
           |"""
  }

  class DanglingThisInPath()(implicit ctx: Context) extends SyntaxMsg(DanglingThisInPathID) {
    def msg = em"""Expected an additional member selection after the keyword ${hl("this")}"""
    def explain =
      val contextCode: String =
        """  trait Outer {
          |    val member: Int
          |    type Member
          |    trait Inner {
          |      ...
          |    }
          |  }"""
      val importCode: String =
        """  import Outer.this.member
          |  //               ^^^^^^^"""
      val typeCode: String =
        """  type T = Outer.this.Member
          |  //                 ^^^^^^^"""
      em"""|Paths of imports and type selections must not end with the keyword ${hl("this")}.
           |
           |Maybe you forgot to select a member of ${hl("this")}? As an example, in the
           |following context:
           |${contextCode}
           |
           |- This is a valid import expression using a path
           |${importCode}
           |
           |- This is a valid type using a path
           |${typeCode}
           |"""
  }

  class OverridesNothing(member: Symbol)(implicit ctx: Context)
  extends DeclarationMsg(OverridesNothingID) {
    def msg = em"""${member} overrides nothing"""

    def explain =
      em"""|There must be a field or method with the name ${member.name} in a super
           |class of ${member.owner} to override it. Did you misspell it?
           |Are you extending the right classes?
           |"""
  }

  class OverridesNothingButNameExists(member: Symbol, existing: List[Denotations.SingleDenotation])(implicit ctx: Context)
  extends DeclarationMsg(OverridesNothingButNameExistsID) {
    def msg = em"""${member} has a different signature than the overridden declaration"""
    def explain =
      val existingDecl: String = existing.map(_.showDcl).mkString("  \n")
      em"""|There must be a non-final field or method with the name ${member.name} and the
           |same parameter list in a super class of ${member.owner} to override it.
           |
           |  ${member.showDcl}
           |
           |The super classes of ${member.owner} contain the following members
           |named ${member.name}:
           |  ${existingDecl}
           |"""
  }

  class ForwardReferenceExtendsOverDefinition(value: Symbol, definition: Symbol)(implicit ctx: Context)
  extends ReferenceMsg(ForwardReferenceExtendsOverDefinitionID) {
    def msg = em"${definition.name} is a forward reference extending over the definition of ${value.name}"

    def explain =
      em"""|${definition.name} is used before you define it, and the definition of ${value.name}
           |appears between that use and the definition of ${definition.name}.
           |
           |Forward references are allowed only, if there are no value definitions between
           |the reference and the referred method definition.
           |
           |Define ${definition.name} before it is used,
           |or move the definition of ${value.name} so it does not appear between
           |the declaration of ${definition.name} and its use,
           |or define ${value.name} as lazy.
           |""".stripMargin
  }

  class ExpectedTokenButFound(expected: Token, found: Token)(implicit ctx: Context)
  extends SyntaxMsg(ExpectedTokenButFoundID) {

    private lazy val foundText = Tokens.showToken(found)

    def msg =
      val expectedText =
        if (Tokens.isIdentifier(expected)) "an identifier"
        else Tokens.showToken(expected)
      em"""${expectedText} expected, but ${foundText} found"""

    def explain =
      if (Tokens.isIdentifier(expected) && Tokens.isKeyword(found))
        s"""
         |If you want to use $foundText as identifier, you may put it in backticks: `$foundText`.""".stripMargin
      else
        ""
  }

  class MixedLeftAndRightAssociativeOps(op1: Name, op2: Name, op2LeftAssoc: Boolean)(implicit ctx: Context)
  extends SyntaxMsg(MixedLeftAndRightAssociativeOpsID) {
    def msg =
      val op1Asso: String = if (op2LeftAssoc) "which is right-associative" else "which is left-associative"
      val op2Asso: String = if (op2LeftAssoc) "which is left-associative" else "which is right-associative"
      em"${op1} (${op1Asso}) and ${op2} ($op2Asso) have same precedence and may not be mixed"
    def explain =
      s"""|The operators ${op1} and ${op2} are used as infix operators in the same expression,
          |but they bind to different sides:
          |${op1} is applied to the operand to its ${if (op2LeftAssoc) "right" else "left"}
          |${op2} is applied to the operand to its ${if (op2LeftAssoc) "left" else "right"}
          |As both have the same precedence the compiler can't decide which to apply first.
          |
          |You may use parenthesis to make the application order explicit,
          |or use method application syntax operand1.${op1}(operand2).
          |
          |Operators ending in a colon ${hl(":")} are right-associative. All other operators are left-associative.
          |
          |Infix operator precedence is determined by the operator's first character. Characters are listed
          |below in increasing order of precedence, with characters on the same line having the same precedence.
          |  (all letters)
          |  |
          |  ^
          |  &
          |  = !
          |  < >
          |  :
          |  + -
          |  * / %
          |  (all other special characters)
          |Operators starting with a letter have lowest precedence, followed by operators starting with `|`, etc.
          |""".stripMargin
  }

  class CantInstantiateAbstractClassOrTrait(cls: Symbol, isTrait: Boolean)(implicit ctx: Context)
  extends TypeMsg(CantInstantiateAbstractClassOrTraitID) {
    private val traitOrAbstract = if (isTrait) "a trait" else "abstract"
    def msg = em"""${cls.name} is ${traitOrAbstract}; it cannot be instantiated"""
    def explain =
      em"""|Abstract classes and traits need to be extended by a concrete class or object
           |to make their functionality accessible.
           |
           |You may want to create an anonymous class extending ${cls.name} with
           |  ${s"class ${cls.name} { }"}
           |
           |or add a companion object with
           |  ${s"object ${cls.name} extends ${cls.name}"}
           |
           |You need to implement any abstract members in both cases.
           |""".stripMargin
  }

  class UnreducibleApplication(tycon: Type)(using Context) extends TypeMsg(UnreducibleApplicationID):
    def msg = em"unreducible application of higher-kinded type $tycon to wildcard arguments"
    def explain =
      em"""|An abstract type constructor cannot be applied to wildcard arguments.
           |Such applications are equivalent to existential types, which are not
           |supported in Scala 3."""

  class OverloadedOrRecursiveMethodNeedsResultType(cycleSym: Symbol)(implicit ctx: Context)
  extends CyclicMsg(OverloadedOrRecursiveMethodNeedsResultTypeID) {
    def msg = em"""Overloaded or recursive $cycleSym needs return type"""
    def explain =
      em"""Case 1: $cycleSym is overloaded
          |If there are multiple methods named $cycleSym and at least one definition of
          |it calls another, you need to specify the calling method's return type.
          |
          |Case 2: $cycleSym is recursive
          |If $cycleSym calls itself on any path (even through mutual recursion), you need to specify the return type
          |of $cycleSym or of a definition it's mutually recursive with.
          |""".stripMargin
  }

  class RecursiveValueNeedsResultType(cycleSym: Symbol)(implicit ctx: Context)
  extends CyclicMsg(RecursiveValueNeedsResultTypeID) {
    def msg = em"""Recursive $cycleSym needs type"""
    def explain =
      em"""The definition of $cycleSym is recursive and you need to specify its type.
          |""".stripMargin
  }

  class CyclicReferenceInvolving(denot: SymDenotation)(implicit ctx: Context)
  extends CyclicMsg(CyclicReferenceInvolvingID) {
    def msg =
      val where = if denot.exists then s" involving $denot" else ""
      em"Cyclic reference$where"
    def explain =
      em"""|$denot is declared as part of a cycle which makes it impossible for the
           |compiler to decide upon ${denot.name}'s type.
           |To avoid this error, try giving ${denot.name} an explicit type.
           |""".stripMargin
  }

  class CyclicReferenceInvolvingImplicit(cycleSym: Symbol)(implicit ctx: Context)
  extends CyclicMsg(CyclicReferenceInvolvingImplicitID) {
    def msg = em"""Cyclic reference involving implicit $cycleSym"""
    def explain =
      em"""|$cycleSym is declared as part of a cycle which makes it impossible for the
           |compiler to decide upon ${cycleSym.name}'s type.
           |This might happen when the right hand-side of $cycleSym's definition involves an implicit search.
           |To avoid this error, try giving ${cycleSym.name} an explicit type.
           |""".stripMargin
  }

  class SuperQualMustBeParent(qual: untpd.Ident, cls: ClassSymbol)(implicit ctx: Context)
  extends ReferenceMsg(SuperQualMustBeParentID) {
    def msg = em"""|$qual does not name a parent of $cls"""
    def explain =
      val parents: Seq[String] = (cls.info.parents map (_.typeSymbol.name.show)).sorted
      em"""|When a qualifier ${hl("T")} is used in a ${hl("super")} prefix of the form ${hl("C.super[T]")},
           |${hl("T")} must be a parent type of ${hl("C")}.
           |
           |In this case, the parents of $cls are:
           |${parents.mkString("  - ", "\n  - ", "")}
           |""".stripMargin
  }

  class VarArgsParamMustComeLast()(implicit ctx: Context)
  extends SyntaxMsg(IncorrectRepeatedParameterSyntaxID) {
    def msg = em"""${hl("varargs")} parameter must come last"""
    def explain =
      em"""|The ${hl("varargs")} field must be the last field in the method signature.
           |Attempting to define a field in a method signature after a ${hl("varargs")} field is an error.
           |"""
  }

  import typer.Typer.BindingPrec

  class AmbiguousReference(name: Name, newPrec: BindingPrec, prevPrec: BindingPrec, prevCtx: Context)(implicit ctx: Context)
    extends ReferenceMsg(AmbiguousReferenceID) {

    /** A string which explains how something was bound; Depending on `prec` this is either
      *      imported by <tree>
      *  or  defined in <symbol>
      */
    private def bindingString(prec: BindingPrec, whereFound: Context, qualifier: String = "") = {
      val howVisible = prec match {
        case BindingPrec.Definition => "defined"
        case BindingPrec.Inheritance => "inherited"
        case BindingPrec.NamedImport => "imported by name"
        case BindingPrec.WildImport => "imported"
        case BindingPrec.PackageClause => "found"
        case BindingPrec.NothingBound => assert(false)
      }
      if (prec.isImportPrec) {
        ex"""$howVisible$qualifier by ${em"${whereFound.importInfo}"}"""
      } else
        ex"""$howVisible$qualifier in ${em"${whereFound.owner}"}"""
    }

    def msg =
      i"""|Reference to ${em"$name"} is ambiguous,
          |it is both ${bindingString(newPrec, ctx)}
          |and ${bindingString(prevPrec, prevCtx, " subsequently")}"""

    def explain =
      em"""|The compiler can't decide which of the possible choices you
           |are referencing with $name: A definition of lower precedence
           |in an inner scope, or a definition with higher precedence in
           |an outer scope.
           |Note:
           | - Definitions in an enclosing scope take precedence over inherited definitions
           | - Definitions take precedence over imports
           | - Named imports take precedence over wildcard imports
           | - You may replace a name when imported using
           |   ${hl("import")} scala.{ $name => ${name.show + "Tick"} }
           |"""
  }

  class MethodDoesNotTakeParameters(tree: tpd.Tree)(implicit ctx: Context)
  extends TypeMsg(MethodDoesNotTakeParametersId) {
    def methodSymbol: Symbol = tpd.methPart(tree).symbol

    def msg = {
      val more = if (tree.isInstanceOf[tpd.Apply]) " more" else ""
      val meth = methodSymbol
      val methStr = if (meth.exists) methodSymbol.showLocated else "expression"
      em"$methStr does not take$more parameters"
    }

    def explain = {
      val isNullary = methodSymbol.info.isInstanceOf[ExprType]
      val addendum =
        if (isNullary) "\nNullary methods may not be called with parenthesis"
        else ""

      "You have specified more parameter lists as defined in the method definition(s)." + addendum
    }

  }

  class AmbiguousOverload(tree: tpd.Tree, val alternatives: List[SingleDenotation], pt: Type)(
    err: Errors)(
    implicit ctx: Context)
  extends ReferenceMsg(AmbiguousOverloadID) {
    private def all = if (alternatives.length == 2) "both" else "all"
    def msg =
      s"""|Ambiguous overload. The ${err.overloadedAltsStr(alternatives)}
          |$all match ${err.expectedTypeStr(pt)}""".stripMargin
    def explain =
      em"""|There are ${alternatives.length} methods that could be referenced as the compiler knows too little
           |about the expected type.
           |You may specify the expected type e.g. by
           |- assigning it to a value with a specified type, or
           |- adding a type ascription as in ${hl("instance.myMethod: String => Int")}
           |"""
  }

  class ReassignmentToVal(name: Name)(implicit ctx: Context)
    extends TypeMsg(ReassignmentToValID) {
    def msg = em"""Reassignment to val $name"""
    def explain =
      em"""|You can not assign a new value to $name as values can't be changed.
           |Keep in mind that every statement has a value, so you may e.g. use
           |  ${hl("val")} $name ${hl("= if (condition) 2 else 5")}
           |In case you need a reassignable name, you can declare it as
           |variable
           |  ${hl("var")} $name ${hl("=")} ...
           |""".stripMargin
  }

  class TypeDoesNotTakeParameters(tpe: Type, params: List[Trees.Tree[Trees.Untyped]])(implicit ctx: Context)
    extends TypeMsg(TypeDoesNotTakeParametersID) {
    def msg = em"$tpe does not take type parameters"
    def explain =
      val ps =
        if (params.size == 1) s"a type parameter ${params.head}"
        else s"type parameters ${params.map(_.show).mkString(", ")}"
      i"""You specified ${NoColor(ps)} for ${em"$tpe"}, which is not
         |declared to take any.
         |"""
  }

  class ParameterizedTypeLacksArguments(psym: Symbol)(implicit ctx: Context)
    extends TypeMsg(ParameterizedTypeLacksArgumentsID) {
    def msg = em"Parameterized $psym lacks argument list"
    def explain =
      em"""The $psym is declared with non-implicit parameters, you may not leave
          |out the parameter list when extending it.
          |"""
  }

  class VarValParametersMayNotBeCallByName(name: TermName, mutable: Boolean)(implicit ctx: Context)
    extends SyntaxMsg(VarValParametersMayNotBeCallByNameID) {
    def varOrVal = if (mutable) em"${hl("var")}" else em"${hl("val")}"
    def msg = s"$varOrVal parameters may not be call-by-name"
    def explain =
      em"""${hl("var")} and ${hl("val")} parameters of classes and traits may no be call-by-name. In case you
          |want the parameter to be evaluated on demand, consider making it just a parameter
          |and a ${hl("def")} in the class such as
          |  ${s"class MyClass(${name}Tick: => String) {"}
          |  ${s"  def $name() = ${name}Tick"}
          |  ${hl("}")}
          |"""
  }

  class MissingTypeParameterFor(tpe: Type)(implicit ctx: Context)
    extends SyntaxMsg(MissingTypeParameterForID) {
    def msg =
      if (tpe.derivesFrom(defn.AnyKindClass)) em"${tpe} cannot be used as a value type"
      else em"Missing type parameter for ${tpe}"
    def explain = ""
  }

  class MissingTypeParameterInTypeApp(tpe: Type)(implicit ctx: Context)
    extends TypeMsg(MissingTypeParameterInTypeAppID) {
    def numParams = tpe.typeParams.length
    def parameters = if (numParams == 1) "parameter" else "parameters"
    def msg = em"Missing type $parameters for $tpe"
    def explain = em"A fully applied type is expected but $tpe takes $numParams $parameters"
  }

  class DoesNotConformToBound(tpe: Type, which: String, bound: Type)(
    err: Errors)(implicit ctx: Context)
    extends TypeMismatchMsg(DoesNotConformToBoundID) {
    def msg = em"Type argument ${tpe} does not conform to $which bound $bound${err.whyNoMatchStr(tpe, bound)}"
    def explain = ""
  }

  class DoesNotConformToSelfType(category: String, selfType: Type, cls: Symbol,
                                      otherSelf: Type, relation: String, other: Symbol)(
    implicit ctx: Context)
    extends TypeMismatchMsg(DoesNotConformToSelfTypeID) {
    def msg = em"""$category: self type $selfType of $cls does not conform to self type $otherSelf
                  |of $relation $other"""
    def explain =
      em"""You mixed in $other which requires self type $otherSelf, but $cls has self type
          |$selfType and does not inherit from $otherSelf.
          |
          |Note: Self types are indicated with the notation
          |  ${s"class "}$other ${hl("{ this: ")}$otherSelf${hl(" => ")}
        """
  }

  class DoesNotConformToSelfTypeCantBeInstantiated(tp: Type, selfType: Type)(
    implicit ctx: Context)
    extends TypeMismatchMsg(DoesNotConformToSelfTypeCantBeInstantiatedID) {
    def msg = em"""$tp does not conform to its self type $selfType; cannot be instantiated"""
    def explain =
      em"""To create an instance of $tp it needs to inherit $selfType in some way.
          |
          |Note: Self types are indicated with the notation
          |  ${s"class "}$tp ${hl("{ this: ")}$selfType${hl(" => ")}
          |"""
  }

  class AbstractMemberMayNotHaveModifier(sym: Symbol, flag: FlagSet)(
    implicit ctx: Context)
    extends SyntaxMsg(AbstractMemberMayNotHaveModifierID) {
    def msg = em"""${hl("abstract")} $sym may not have `${flag.flagsString}` modifier"""
    def explain = ""
  }

  class TopLevelCantBeImplicit(sym: Symbol)(
    implicit ctx: Context)
    extends SyntaxMsg(TopLevelCantBeImplicitID) {
    def msg = em"""${hl("implicit")} modifier cannot be used for top-level definitions"""
    def explain = ""
  }

  class TypesAndTraitsCantBeImplicit()(implicit ctx: Context)
    extends SyntaxMsg(TypesAndTraitsCantBeImplicitID) {
    def msg = em"""${hl("implicit")} modifier cannot be used for types or traits"""
    def explain = ""
  }

  class OnlyClassesCanBeAbstract(sym: Symbol)(
    implicit ctx: Context)
    extends SyntaxMsg(OnlyClassesCanBeAbstractID) {
    def explain = ""
    def msg = em"""${hl("abstract")} modifier can be used only for classes; it should be omitted for abstract members"""
  }

  class AbstractOverrideOnlyInTraits(sym: Symbol)(
    implicit ctx: Context)
    extends SyntaxMsg(AbstractOverrideOnlyInTraitsID) {
    def msg = em"""${hl("abstract override")} modifier only allowed for members of traits"""
    def explain = ""
  }

  class TraitsMayNotBeFinal(sym: Symbol)(
    implicit ctx: Context)
    extends SyntaxMsg(TraitsMayNotBeFinalID) {
    def msg = em"""$sym may not be ${hl("final")}"""
    def explain =
      "A trait can never be final since it is abstract and must be extended to be useful."
  }

  class NativeMembersMayNotHaveImplementation(sym: Symbol)(
    implicit ctx: Context)
    extends SyntaxMsg(NativeMembersMayNotHaveImplementationID) {
    def msg = em"""${hl("@native")} members may not have an implementation"""
    def explain = ""
  }

  class OnlyClassesCanHaveDeclaredButUndefinedMembers(sym: Symbol)(
    implicit ctx: Context)
    extends SyntaxMsg(OnlyClassesCanHaveDeclaredButUndefinedMembersID) {

    private def varNote =
      if (sym.is(Mutable)) "Note that variables need to be initialized to be defined."
      else ""
    def msg = em"""Declaration of $sym not allowed here: only classes can have declared but undefined members"""
    def explain = s"$varNote"
  }

  class CannotExtendAnyVal(sym: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(CannotExtendAnyValID) {
    def msg = em"""$sym cannot extend ${hl("AnyVal")}"""
    def explain =
      em"""Only classes (not traits) are allowed to extend ${hl("AnyVal")}, but traits may extend
          |${hl("Any")} to become ${Green("\"universal traits\"")} which may only have ${hl("def")} members.
          |Universal traits can be mixed into classes that extend ${hl("AnyVal")}.
          |"""
  }

  class CannotHaveSameNameAs(sym: Symbol, cls: Symbol, reason: CannotHaveSameNameAs.Reason)(implicit ctx: Context)
    extends SyntaxMsg(CannotHaveSameNameAsID) {
    import CannotHaveSameNameAs._
    def reasonMessage: String = reason match {
      case CannotBeOverridden => "class definitions cannot be overridden"
      case DefinedInSelf(self) =>
        s"""cannot define ${sym.showKind} member with the same name as a ${cls.showKind} member in self reference ${self.name}.
           |(Note: this can be resolved by using another name)
           |""".stripMargin
    }

    def msg = em"""$sym cannot have the same name as ${cls.showLocated} -- """ + reasonMessage
    def explain = ""
  }
  object CannotHaveSameNameAs {
    sealed trait Reason
    case object CannotBeOverridden extends Reason
    case class DefinedInSelf(self: tpd.ValDef) extends Reason
  }

  class ValueClassesMayNotDefineInner(valueClass: Symbol, inner: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassesMayNotDefineInnerID) {
    def msg = em"""Value classes may not define an inner class"""
    def explain = ""
  }

  class ValueClassesMayNotDefineNonParameterField(valueClass: Symbol, field: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassesMayNotDefineNonParameterFieldID) {
    def msg = em"""Value classes may not define non-parameter field"""
    def explain = ""
  }

  class ValueClassesMayNotDefineASecondaryConstructor(valueClass: Symbol, constructor: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassesMayNotDefineASecondaryConstructorID) {
    def msg = em"""Value classes may not define a secondary constructor"""
    def explain = ""
  }

  class ValueClassesMayNotContainInitalization(valueClass: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassesMayNotContainInitalizationID) {
    def msg = em"""Value classes may not contain initialization statements"""
    def explain = ""
  }

  class ValueClassesMayNotBeAbstract(valueClass: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassesMayNotBeAbstractID) {
    def msg = em"""Value classes may not be ${hl("abstract")}"""
    def explain = ""
  }

  class ValueClassesMayNotBeContainted(valueClass: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassesMayNotBeContaintedID) {
    private def localOrMember = if (valueClass.owner.isTerm) "local class" else "member of another class"
    def msg = s"""Value classes may not be a $localOrMember"""
    def explain = ""
  }

  class ValueClassesMayNotWrapItself(valueClass: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassesMayNotWrapItselfID) {
    def msg = """A value class may not wrap itself"""
    def explain = ""
  }

  class ValueClassParameterMayNotBeAVar(valueClass: Symbol, param: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassParameterMayNotBeAVarID) {
    def msg = em"""A value class parameter may not be a ${hl("var")}"""
    def explain =
      em"""A value class must have exactly one ${hl("val")} parameter."""
  }

  class ValueClassNeedsOneValParam(valueClass: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassNeedsExactlyOneValParamID) {
    def msg = em"""Value class needs one ${hl("val")} parameter"""
    def explain = ""
  }

  class ValueClassParameterMayNotBeCallByName(valueClass: Symbol, param: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ValueClassParameterMayNotBeCallByNameID) {
    def msg = s"Value class parameter `${param.name}` may not be call-by-name"
    def explain = ""
  }

  class OnlyCaseClassOrCaseObjectAllowed()(implicit ctx: Context)
    extends SyntaxMsg(OnlyCaseClassOrCaseObjectAllowedID) {
    def msg = em"""Only ${hl("case class")} or ${hl("case object")} allowed"""
    def explain = ""
  }

  class ExpectedToplevelDef()(implicit ctx: Context)
    extends SyntaxMsg(ExpectedTopLevelDefID) {
    def msg = "Expected a toplevel definition"
    def explain = ""
  }

  class SuperCallsNotAllowedInlineable(symbol: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(SuperCallsNotAllowedInlineableID) {
    def msg = em"Super call not allowed in inlineable $symbol"
    def explain = "Method inlining prohibits calling superclass methods, as it may lead to confusion about which super is being called."
  }

  class NotAPath(tp: Type, usage: String)(using Context) extends TypeMsg(NotAPathID):
    def msg = em"$tp is not a valid $usage, since it is not an immutable path"
    def explain =
      i"""An immutable path is
         | - a reference to an immutable value, or
         | - a reference to `this`, or
         | - a selection of an immutable path with an immutable value."""

  class WrongNumberOfParameters(expected: Int)(implicit ctx: Context)
    extends SyntaxMsg(WrongNumberOfParametersID) {
    def msg = s"Wrong number of parameters, expected: $expected"
    def explain = ""
  }

  class DuplicatePrivateProtectedQualifier()(implicit ctx: Context)
    extends SyntaxMsg(DuplicatePrivateProtectedQualifierID) {
    def msg = "Duplicate private/protected qualifier"
    def explain =
      em"It is not allowed to combine `private` and `protected` modifiers even if they are qualified to different scopes"
  }

  class ExpectedStartOfTopLevelDefinition()(implicit ctx: Context)
    extends SyntaxMsg(ExpectedStartOfTopLevelDefinitionID) {
    def msg = "Expected start of definition"
    def explain =
      em"You have to provide either ${hl("class")}, ${hl("trait")}, ${hl("object")}, or ${hl("enum")} definitions after qualifiers"
  }

  class NoReturnFromInlineable(owner: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(NoReturnFromInlineableID) {
    def msg = em"No explicit ${hl("return")} allowed from inlineable $owner"
    def explain =
      em"""Methods marked with ${hl("inline")} modifier may not use ${hl("return")} statements.
          |Instead, you should rely on the last expression's value being
          |returned from a method.
          |"""
  }

  class ReturnOutsideMethodDefinition(owner: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ReturnOutsideMethodDefinitionID) {
    def msg = em"${hl("return")} outside method definition"
    def explain =
      em"""You used ${hl("return")} in ${owner}.
          |${hl("return")} is a keyword and may only be used within method declarations.
          |"""
  }

  class ExtendFinalClass(clazz:Symbol, finalClazz: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ExtendFinalClassID) {
    def msg = em"$clazz cannot extend ${hl("final")} $finalClazz"
    def explain =
      em"""A class marked with the ${hl("final")} keyword cannot be extended"""
  }

  class ExpectedTypeBoundOrEquals(found: Token)(implicit ctx: Context)
    extends SyntaxMsg(ExpectedTypeBoundOrEqualsID) {
    def msg = em"${hl("=")}, ${hl(">:")}, or ${hl("<:")} expected, but ${Tokens.showToken(found)} found"

    def explain =
      em"""Type parameters and abstract types may be constrained by a type bound.
           |Such type bounds limit the concrete values of the type variables and possibly
           |reveal more information about the members of such types.
           |
           |A lower type bound ${hl("B >: A")} expresses that the type variable ${hl("B")}
           |refers to a supertype of type ${hl("A")}.
           |
           |An upper type bound ${hl("T <: A")} declares that type variable ${hl("T")}
           |refers to a subtype of type ${hl("A")}.
           |"""
  }

  class ClassAndCompanionNameClash(cls: Symbol, other: Symbol)(implicit ctx: Context)
    extends NamingMsg(ClassAndCompanionNameClashID) {
    def msg = em"Name clash: both ${cls.owner} and its companion object defines ${cls.name.stripModuleClassSuffix}"
    def explain =
      em"""|A ${cls.kindString} and its companion object cannot both define a ${hl("class")}, ${hl("trait")} or ${hl("object")} with the same name:
           |  - ${cls.owner} defines ${cls}
           |  - ${other.owner} defines ${other}"""
  }

  class TailrecNotApplicable(symbol: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(TailrecNotApplicableID) {
    def msg = {
      val reason =
        if (!symbol.is(Method)) em"$symbol isn't a method"
        else if (symbol.is(Deferred)) em"$symbol is abstract"
        else if (!symbol.isEffectivelyFinal) em"$symbol is neither ${hl("private")} nor ${hl("final")} so can be overridden"
        else em"$symbol contains no recursive calls"

      s"TailRec optimisation not applicable, $reason"
    }
    def explain = ""
  }

  class FailureToEliminateExistential(tp: Type, tp1: Type, tp2: Type, boundSyms: List[Symbol])(implicit ctx: Context)
    extends Message(FailureToEliminateExistentialID) {
    def kind: String = "Compatibility"
    def msg =
      val originalType = ctx.printer.dclsText(boundSyms, "; ").show
      em"""An existential type that came from a Scala-2 classfile cannot be
          |mapped accurately to to a Scala-3 equivalent.
          |original type    : $tp forSome ${originalType}
          |reduces to       : $tp1
          |type used instead: $tp2
          |This choice can cause follow-on type errors or hide type errors.
          |Proceed at own risk."""
    def explain =
      em"""Existential types in their full generality are no longer supported.
          |Scala-3 does applications of class types to wildcard type arguments.
          |Other forms of existential types that come from Scala-2 classfiles
          |are only approximated in a best-effort way."""
  }

  class OnlyFunctionsCanBeFollowedByUnderscore(tp: Type)(implicit ctx: Context)
    extends SyntaxMsg(OnlyFunctionsCanBeFollowedByUnderscoreID) {
    def msg = em"Only function types can be followed by ${hl("_")} but the current expression has type $tp"
    def explain =
      em"""The syntax ${hl("x _")} is no longer supported if ${hl("x")} is not a function.
          |To convert to a function value, you need to explicitly write ${hl("() => x")}"""
  }

  class MissingEmptyArgumentList(method: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(MissingEmptyArgumentListID) {
    def msg = em"$method must be called with ${hl("()")} argument"
    def explain = {
      val codeExample =
        """def next(): T = ...
          |next     // is expanded to next()"""

      em"""Previously an empty argument list () was implicitly inserted when calling a nullary method without arguments. E.g.
          |
          |$codeExample
          |
          |In Dotty, this idiom is an error. The application syntax has to follow exactly the parameter syntax.
          |Excluded from this rule are methods that are defined in Java or that override methods defined in Java."""
    }
  }

  class DuplicateNamedTypeParameter(name: Name)(implicit ctx: Context)
    extends SyntaxMsg(DuplicateNamedTypeParameterID) {
    def msg = em"Type parameter $name was defined multiple times."
    def explain = ""
  }

  class UndefinedNamedTypeParameter(undefinedName: Name, definedNames: List[Name])(implicit ctx: Context)
    extends SyntaxMsg(UndefinedNamedTypeParameterID) {
    def msg = em"Type parameter $undefinedName is undefined. Expected one of ${definedNames.map(_.show).mkString(", ")}."
    def explain = ""
  }

  class IllegalStartOfStatement(isModifier: Boolean)(implicit ctx: Context) extends SyntaxMsg(IllegalStartOfStatementID) {
    def msg = {
      val addendum = if (isModifier) ": no modifiers allowed here" else ""
      "Illegal start of statement" + addendum
    }
    def explain = "A statement is either an import, a definition or an expression."
  }

  class TraitIsExpected(symbol: Symbol)(implicit ctx: Context) extends SyntaxMsg(TraitIsExpectedID) {
    def msg = em"$symbol is not a trait"
    def explain = {
      val errorCodeExample =
        """class A
          |class B
          |
          |val a = new A with B // will fail with a compile error - class B is not a trait""".stripMargin
      val codeExample =
        """class A
          |trait B
          |
          |val a = new A with B // compiles normally""".stripMargin

      em"""Only traits can be mixed into classes using a ${hl("with")} keyword.
          |Consider the following example:
          |
          |$errorCodeExample
          |
          |The example mentioned above would fail because B is not a trait.
          |But if you make B a trait it will be compiled without any errors:
          |
          |$codeExample
          |"""
    }
  }

  class TraitRedefinedFinalMethodFromAnyRef(method: Symbol)(implicit ctx: Context) extends SyntaxMsg(TraitRedefinedFinalMethodFromAnyRefID) {
    def msg = em"Traits cannot redefine final $method from ${hl("class AnyRef")}."
    def explain = ""
  }

  class PackageNameAlreadyDefined(pkg: Symbol)(implicit ctx: Context) extends NamingMsg(PackageNameAlreadyDefinedID) {
    lazy val (where, or) =
      if pkg.associatedFile == null then ("", "")
      else (s" in ${pkg.associatedFile}", " or delete the containing class file")
    def msg = em"""${pkg.name} is the name of $pkg$where.
                          |It cannot be used at the same time as the name of a package."""
    def explain =
      em"""An ${hl("object")} or other toplevel definition cannot have the same name as an existing ${hl("package")}.
          |Rename either one of them$or."""
  }

  class UnapplyInvalidNumberOfArguments(qual: untpd.Tree, argTypes: List[Type])(implicit ctx: Context)
    extends SyntaxMsg(UnapplyInvalidNumberOfArgumentsID) {
    def msg = em"Wrong number of argument patterns for $qual; expected: ($argTypes%, %)"
    def explain =
      em"""The Unapply method of $qual was used with incorrect number of arguments.
          |Expected usage would be something like:
          |case $qual(${argTypes.map(_ => '_')}%, %) => ...
          |
        |where subsequent arguments would have following types: ($argTypes%, %).
        |""".stripMargin
  }

  class UnapplyInvalidReturnType(unapplyResult: Type, unapplyName: Symbol#ThisName)(implicit ctx: Context)
    extends DeclarationMsg(UnapplyInvalidReturnTypeID) {
    def msg =
      val addendum =
        if Feature.migrateTo3 && unapplyName == nme.unapplySeq
        then "\nYou might want to try to rewrite the extractor to use `unapply` instead."
        else ""
      em"""| ${Red(i"$unapplyResult")} is not a valid result type of an $unapplyName method of an ${Magenta("extractor")}.$addendum"""
    def explain = if (unapplyName.show == "unapply")
      em"""
          |To be used as an extractor, an unapply method has to return a type that either:
          | - has members ${Magenta("isEmpty: Boolean")} and ${Magenta("get: S")} (usually an ${Green("Option[S]")})
          | - is a ${Green("Boolean")}
          | - is a ${Green("Product")} (like a ${Magenta("Tuple2[T1, T2]")})
          |
          |class A(val i: Int)
          |
          |object B {
          |  def unapply(a: A): ${Green("Option[Int]")} = Some(a.i)
          |}
          |
          |object C {
          |  def unapply(a: A): ${Green("Boolean")} = a.i == 2
          |}
          |
          |object D {
          |  def unapply(a: A): ${Green("(Int, Int)")} = (a.i, a.i)
          |}
          |
          |object Test {
          |  def test(a: A) = a match {
          |    ${Magenta("case B(1)")} => 1
          |    ${Magenta("case a @ C()")} => 2
          |    ${Magenta("case D(3, 3)")} => 3
          |  }
          |}
        """.stripMargin
    else
      em"""
          |To be used as an extractor, an unapplySeq method has to return a type which has members
          |${Magenta("isEmpty: Boolean")} and ${Magenta("get: S")} where ${Magenta("S <: Seq[V]")} (usually an ${Green("Option[Seq[V]]")}):
          |
          |object CharList {
          |  def unapplySeq(s: String): ${Green("Option[Seq[Char]")} = Some(s.toList)
          |
          |  "example" match {
          |    ${Magenta("case CharList(c1, c2, c3, c4, _, _, _)")} =>
          |      println(s"$$c1,$$c2,$$c3,$$c4")
          |    case _ =>
          |      println("Expected *exactly* 7 characters!")
          |  }
          |}
        """.stripMargin
  }

  class StaticFieldsOnlyAllowedInObjects(member: Symbol)(implicit ctx: Context) extends SyntaxMsg(StaticFieldsOnlyAllowedInObjectsID) {
    def msg = em"${hl("@static")} $member in ${member.owner} must be defined inside an ${hl("object")}."
    def explain =
      em"${hl("@static")} members are only allowed inside objects."
  }

  class StaticFieldsShouldPrecedeNonStatic(member: Symbol, defns: List[tpd.Tree])(implicit ctx: Context) extends SyntaxMsg(StaticFieldsShouldPrecedeNonStaticID) {
    def msg = em"${hl("@static")} $member in ${member.owner} must be defined before non-static fields."
    def explain = {
      val nonStatics = defns.takeWhile(_.symbol != member).take(3).filter(_.isInstanceOf[tpd.ValDef])
      val codeExample = s"""object ${member.owner.name.firstPart} {
                        |  @static ${member} = ...
                        |  ${nonStatics.map(m => s"${m.symbol} = ...").mkString("\n  ")}
                        |  ...
                        |}"""
      em"""The fields annotated with @static should precede any non @static fields.
        |This ensures that we do not introduce surprises for users in initialization order of this class.
        |Static field are initialized when class loading the code of Foo.
        |Non static fields are only initialized the first  time that Foo is accessed.
        |
        |The definition of ${member.name} should have been before the non ${hl("@static val")}s:
        |$codeExample
        |"""
    }
  }

  class CyclicInheritance(symbol: Symbol, addendum: => String)(implicit ctx: Context) extends SyntaxMsg(CyclicInheritanceID) {
    def msg = em"Cyclic inheritance: $symbol extends itself$addendum"
    def explain = {
      val codeExample = "class A extends A"

      em"""Cyclic inheritance is prohibited in Dotty.
          |Consider the following example:
          |
          |$codeExample
          |
          |The example mentioned above would fail because this type of inheritance hierarchy
          |creates a "cycle" where a not yet defined class A extends itself which makes
          |impossible to instantiate an object of this class"""
    }
  }

  class BadSymbolicReference(denot: SymDenotation)(implicit ctx: Context)
  extends ReferenceMsg(BadSymbolicReferenceID) {
    def msg = {
      val denotationOwner = denot.owner
      val denotationName = ctx.fresh.setSetting(ctx.settings.YdebugNames, true).printer.nameString(denot.name)
      val file = denot.symbol.associatedFile
      val (location, src) =
        if (file != null) (s" in $file", file.toString)
        else ("", "the signature")

      em"""Bad symbolic reference. A signature$location
          |refers to $denotationName in ${denotationOwner.showKind} ${denotationOwner.showFullName} which is not available.
          |It may be completely missing from the current classpath, or the version on
          |the classpath might be incompatible with the version used when compiling $src."""
    }

    def explain = ""
  }

  class UnableToExtendSealedClass(pclazz: Symbol)(implicit ctx: Context) extends SyntaxMsg(UnableToExtendSealedClassID) {
    def msg = em"Cannot extend ${hl("sealed")} $pclazz in a different source file"
    def explain = "A sealed class or trait can only be extended in the same file as its declaration"
  }

  class SymbolHasUnparsableVersionNumber(symbol: Symbol, migrationMessage: => String)(implicit ctx: Context)
  extends SyntaxMsg(SymbolHasUnparsableVersionNumberID) {
    def msg = em"${symbol.showLocated} has an unparsable version number: $migrationMessage"
    def explain =
      em"""$migrationMessage
          |
          |The ${symbol.showLocated} is marked with ${hl("@migration")} indicating it has changed semantics
          |between versions and the ${hl("-Xmigration")} settings is used to warn about constructs
          |whose behavior may have changed since version change."""
  }

  class SymbolChangedSemanticsInVersion(
    symbol: Symbol,
    migrationVersion: ScalaVersion
  )(implicit ctx: Context) extends SyntaxMsg(SymbolChangedSemanticsInVersionID) {
    def msg = em"${symbol.showLocated} has changed semantics in version $migrationVersion"
    def explain = {
      em"""The ${symbol.showLocated} is marked with ${hl("@migration")} indicating it has changed semantics
          |between versions and the ${hl("-Xmigration")} settings is used to warn about constructs
          |whose behavior may have changed since version change."""
    }
  }

  class UnableToEmitSwitch(tooFewCases: Boolean)(implicit ctx: Context)
  extends SyntaxMsg(UnableToEmitSwitchID) {
    def tooFewStr: String = if (tooFewCases) " since there are not enough cases" else ""
    def msg = em"Could not emit switch for ${hl("@switch")} annotated match$tooFewStr"
    def explain = {
      val codeExample =
        """val ConstantB = 'B'
          |final val ConstantC = 'C'
          |def tokenMe(ch: Char) = (ch: @switch) match {
          |  case '\t' | '\n' => 1
          |  case 'A'         => 2
          |  case ConstantB   => 3  // a non-literal may prevent switch generation: this would not compile
          |  case ConstantC   => 4  // a constant value is allowed
          |  case _           => 5
          |}""".stripMargin

      em"""If annotated with ${hl("@switch")}, the compiler will verify that the match has been compiled to a
          |tableswitch or lookupswitch and issue an error if it instead compiles into a series of conditional
          |expressions. Example usage:
          |
          |$codeExample
          |
          |The compiler will not apply the optimisation if:
          |- the matched value is not of type ${hl("Int")}, ${hl("Byte")}, ${hl("Short")} or ${hl("Char")}
          |- the matched value is not a constant literal
          |- there are less than three cases"""
    }
  }

  class MissingCompanionForStatic(member: Symbol)(implicit ctx: Context)
  extends SyntaxMsg(MissingCompanionForStaticID) {
    def msg = em"${member.owner} does not have a companion class"
    def explain =
      em"An object that contains ${hl("@static")} members must have a companion class."
  }

  class PolymorphicMethodMissingTypeInParent(rsym: Symbol, parentSym: Symbol)(implicit ctx: Context)
  extends SyntaxMsg(PolymorphicMethodMissingTypeInParentID) {
    def msg = em"Polymorphic refinement $rsym without matching type in parent $parentSym is no longer allowed"
    def explain =
      em"""Polymorphic $rsym is not allowed in the structural refinement of $parentSym because
          |$rsym does not override any method in $parentSym. Structural refinement does not allow for
          |polymorphic methods."""
  }

  class ParamsNoInline(owner: Symbol)(implicit ctx: Context)
    extends SyntaxMsg(ParamsNoInlineID) {
    def msg = em"""${hl("inline")} modifier can only be used for parameters of inline methods"""
    def explain = ""
  }

  class JavaSymbolIsNotAValue(symbol: Symbol)(implicit ctx: Context) extends TypeMsg(JavaSymbolIsNotAValueID) {
    def msg = {
      val kind =
        if (symbol is Package) em"$symbol"
        else em"Java defined ${hl("class " + symbol.name)}"

      s"$kind is not a value"
    }
    def explain = ""
  }

  class DoubleDefinition(decl: Symbol, previousDecl: Symbol, base: Symbol)(implicit ctx: Context) extends NamingMsg(DoubleDefinitionID) {
    def msg = {
      def nameAnd = if (decl.name != previousDecl.name) " name and" else ""
      def details(implicit ctx: Context): String =
        if (decl.isRealMethod && previousDecl.isRealMethod) {
          // compare the signatures when both symbols represent methods
          decl.signature.matchDegree(previousDecl.signature) match {
            case Signature.MatchDegree.NoMatch =>
              // If the signatures don't match at all at the current phase, then
              // they might match after erasure.
              val elimErasedCtx = ctx.withPhaseNoEarlier(ctx.elimErasedValueTypePhase.next)
              if (elimErasedCtx != ctx)
                details(elimErasedCtx)
              else
                "" // shouldn't be reachable
            case Signature.MatchDegree.ParamMatch =>
              "have matching parameter types."
            case Signature.MatchDegree.FullMatch =>
              i"have the same$nameAnd type after erasure."
          }
        }
        else ""
      def symLocation(sym: Symbol) = {
        val lineDesc =
          if (sym.span.exists && sym.span != sym.owner.span)
            s" at line ${sym.sourcePos.line + 1}"
          else ""
        i"in ${sym.owner}${lineDesc}"
      }
      val clashDescription =
        if (decl.owner eq previousDecl.owner)
          "Double definition"
        else if ((decl.owner eq base) || (previousDecl eq base))
          "Name clash between defined and inherited member"
        else
          "Name clash between inherited members"

      em"""$clashDescription:
          |${previousDecl.showDcl} ${symLocation(previousDecl)} and
          |${decl.showDcl} ${symLocation(decl)}
          |""" + details
    }
    def explain = ""
  }

  class ImportRenamedTwice(ident: untpd.Ident)(implicit ctx: Context) extends SyntaxMsg(ImportRenamedTwiceID) {
    def msg = s"${ident.show} is renamed twice on the same import line."
    def explain = ""
  }

  class TypeTestAlwaysSucceeds(scrutTp: Type, testTp: Type)(implicit ctx: Context) extends SyntaxMsg(TypeTestAlwaysSucceedsID) {
    def msg = {
      val addendum =
        if (scrutTp != testTp) s" is a subtype of ${testTp.show}"
        else " is the same as the tested type"
      s"The highlighted type test will always succeed since the scrutinee type ($scrutTp.show)" + addendum
    }
    def explain = ""
  }

  // Relative of CyclicReferenceInvolvingImplicit and RecursiveValueNeedsResultType
  class TermMemberNeedsResultTypeForImplicitSearch(cycleSym: Symbol)(implicit ctx: Context)
    extends CyclicMsg(TermMemberNeedsNeedsResultTypeForImplicitSearchID) {
    def msg = em"""$cycleSym needs result type because its right-hand side attempts implicit search"""
    def explain =
      em"""|The right hand-side of $cycleSym's definition requires an implicit search at the highlighted position.
           |To avoid this error, give `$cycleSym` an explicit type.
           |""".stripMargin
  }

  class ClassCannotExtendEnum(cls: Symbol, parent: Symbol)(implicit ctx: Context) extends SyntaxMsg(ClassCannotExtendEnumID) {
    def msg = em"""$cls in ${cls.owner} extends enum ${parent.name}, but extending enums is prohibited."""
    def explain = ""
  }

  class NotAnExtractor(tree: untpd.Tree)(implicit ctx: Context) extends SyntaxMsg(NotAnExtractorID) {
    def msg = em"$tree cannot be used as an extractor in a pattern because it lacks an unapply or unapplySeq method"
    def explain =
      em"""|An ${hl("unapply")} method should be defined in an ${hl("object")} as follow:
           |  - If it is just a test, return a ${hl("Boolean")}. For example ${hl("case even()")}
           |  - If it returns a single sub-value of type T, return an ${hl("Option[T]")}
           |  - If it returns several sub-values T1,...,Tn, group them in an optional tuple ${hl("Option[(T1,...,Tn)]")}
           |
           |Sometimes, the number of sub-values isn't fixed and we would like to return a sequence.
           |For this reason, you can also define patterns through ${hl("unapplySeq")} which returns ${hl("Option[Seq[T]]")}.
           |This mechanism is used for instance in pattern ${hl("case List(x1, ..., xn)")}""".stripMargin
  }

  class MemberWithSameNameAsStatic()(using ctx: Context)
    extends SyntaxMsg(MemberWithSameNameAsStaticID) {
    def msg = em"Companion classes cannot define members with same name as a ${hl("@static")} member"
    def explain = ""
  }

  class PureExpressionInStatementPosition(stat: untpd.Tree, val exprOwner: Symbol)(implicit ctx: Context)
    extends Message(PureExpressionInStatementPositionID) {
    def kind = "Potential Issue"
    def msg = "A pure expression does nothing in statement position; you may be omitting necessary parentheses"
    def explain =
      em"""The pure expression $stat doesn't have any side effect and its result is not assigned elsewhere.
          |It can be removed without changing the semantics of the program. This may indicate an error.""".stripMargin
  }

  class TraitCompanionWithMutableStatic()(using ctx: Context)
    extends SyntaxMsg(TraitCompanionWithMutableStaticID) {
    def msg = em"Companion of traits cannot define mutable @static fields"
    def explain = ""
  }

  class LazyStaticField()(using ctx: Context)
    extends SyntaxMsg(LazyStaticFieldID) {
    def msg = em"Lazy @static fields are not supported"
    def explain = ""
  }

  class StaticOverridingNonStaticMembers()(using ctx: Context)
    extends SyntaxMsg(StaticOverridingNonStaticMembersID) {
    def msg = em"${hl("@static")} members cannot override or implement non-static ones"
    def explain = ""
  }

  class OverloadInRefinement(rsym: Symbol)(using ctx: Context)
    extends DeclarationMsg(OverloadInRefinementID) {
    def msg = "Refinements cannot introduce overloaded definitions"
    def explain =
      em"""The refinement `$rsym` introduces an overloaded definition.
          |Refinements cannot contain overloaded definitions.""".stripMargin
  }

  class NoMatchingOverload(val alternatives: List[SingleDenotation], pt: Type)(
    err: Errors)(using ctx: Context)
    extends TypeMismatchMsg(NoMatchingOverloadID) {
    def msg =
      em"""None of the ${err.overloadedAltsStr(alternatives)}
          |match ${err.expectedTypeStr(pt)}"""
    def explain = ""
  }
  class StableIdentPattern(tree: untpd.Tree, pt: Type)(using ctx: Context)
    extends TypeMsg(StableIdentPatternID) {
    def msg =
      em"""Stable identifier required, but $tree found"""
    def explain = ""
  }

  class IllegalSuperAccessor(base: Symbol, memberName: Name,
      acc: Symbol, accTp: Type,
      other: Symbol, otherTp: Type)(using ctx: Context) extends DeclarationMsg(IllegalSuperAccessorID) {
    def msg = {
      // The mixin containing a super-call that requires a super-accessor
      val accMixin = acc.owner
      // The class or trait that the super-accessor should resolve too in `base`
      val otherMixin = other.owner
      // The super-call in `accMixin`
      val superCall = hl(i"super.$memberName")
      // The super-call that the super-accesors in `base` forwards to
      val resolvedSuperCall = hl(i"super[${otherMixin.name}].$memberName")
      // The super-call that we would have called if `super` in traits behaved like it
      // does in classes, i.e. followed the linearization of the trait itself.
      val staticSuperCall = {
        val staticSuper = accMixin.asClass.info.parents.reverse
          .find(_.nonPrivateMember(memberName).matchingDenotation(accMixin.thisType, acc.info).exists)
        val staticSuperName = staticSuper match {
          case Some(parent) =>
            parent.classSymbol.name.show
          case None => // Might be reachable under separate compilation
            "SomeParent"
        }
        hl(i"super[$staticSuperName].$memberName")
      }
      ex"""$base cannot be defined due to a conflict between its parents when
          |implementing a super-accessor for $memberName in $accMixin:
          |
          |1. One of its parent (${accMixin.name}) contains a call $superCall in its body,
          |   and when a super-call in a trait is written without an explicit parent
          |   listed in brackets, it is implemented by a generated super-accessor in
          |   the class that extends this trait based on the linearization order of
          |   the class.
          |2. Because ${otherMixin.name} comes before ${accMixin.name} in the linearization
          |   order of ${base.name}, and because ${otherMixin.name} overrides $memberName,
          |   the super-accessor in ${base.name} is implemented as a call to
          |   $resolvedSuperCall.
          |3. However,
          |   ${otherTp.widenExpr} (the type of $resolvedSuperCall in ${base.name})
          |   is not a subtype of
          |   ${accTp.widenExpr} (the type of $memberName in $accMixin).
          |   Hence, the super-accessor that needs to be generated in ${base.name}
          |   is illegal.
          |
          |Here are two possible ways to resolve this:
          |
          |1. Change the linearization order of ${base.name} such that
          |   ${accMixin.name} comes before ${otherMixin.name}.
          |2. Alternatively, replace $superCall in the body of $accMixin by a
          |   super-call to a specific parent, e.g. $staticSuperCall
          |""".stripMargin
    }
    def explain = ""
  }

  class TraitParameterUsedAsParentPrefix(cls: Symbol)(using ctx: Context)
    extends DeclarationMsg(TraitParameterUsedAsParentPrefixID) {
    def msg =
      s"${cls.show} cannot extend from a parent that is derived via its own parameters"
    def explain =
      ex"""
          |The parent class/trait that ${cls.show} extends from is obtained from
          |the parameter of ${cls.show}. This is disallowed in order to prevent
          |outer-related Null Pointer Exceptions in Scala.
          |
          |In order to fix this issue consider directly extending from the parent rather
          |than obtaining it from the parameters of ${cls.show}.
          |""".stripMargin
  }

  class UnknownNamedEnclosingClassOrObject(name: TypeName)(using ctx: Context)
    extends ReferenceMsg(UnknownNamedEnclosingClassOrObjectID) {
    def msg =
      em"""no enclosing class or object is named '${hl(name.show)}'"""
    def explain =
      ex"""
      |The class or object named '${hl(name.show)}' was used as a visibility
      |modifier, but could not be resolved. Make sure that
      |'${hl(name.show)}' is not misspelled and has been imported into the
      |current scope.
      """.stripMargin
    }

  class IllegalCyclicTypeReference(sym: Symbol, where: String, lastChecked: Type)(using ctx: Context)
    extends CyclicMsg(IllegalCyclicTypeReferenceID) {
    def msg = i"illegal cyclic type reference: ${where} ${hl(lastChecked.show)} of $sym refers back to the type itself"
    def explain = ""
  }

  class ErasedTypesCanOnlyBeFunctionTypes()(using ctx: Context)
    extends SyntaxMsg(ErasedTypesCanOnlyBeFunctionTypesID) {
    def msg = "Types with erased keyword can only be function types `(erased ...) => ...`"
    def explain = ""
  }

  class CaseClassMissingNonImplicitParamList(cdef: untpd.TypeDef)(implicit ctx: Context)
    extends SyntaxMsg(CaseClassMissingNonImplicitParamListID) {
    def msg =
      em"""|A ${hl("case class")} must have at least one non-implicit parameter list"""

    def explain =
      em"""|${cdef.name} must have at least one non-implicit parameter list,
           | if you're aiming to have a case class parametrized only by implicit ones, you should
           | add an explicit ${hl("()")} as a parameter list to ${cdef.name}.""".stripMargin
  }

  class EnumerationsShouldNotBeEmpty(cdef: untpd.TypeDef)(implicit ctx: Context)
    extends SyntaxMsg(EnumerationsShouldNotBeEmptyID) {
    def msg = "Enumerations must contain at least one case"

    def explain =
      em"""|Enumeration ${cdef.name} must contain at least one case
           |Example Usage:
           | ${hl("enum")} ${cdef.name} {
           |    ${hl("case")} Option1, Option2
           | }
           |""".stripMargin
  }

  class AbstractCannotBeUsedForObjects(mdef: untpd.ModuleDef)(implicit ctx: Context)
    extends SyntaxMsg(AbstractCannotBeUsedForObjectsID) {
    def msg = em"${hl("abstract")} modifier cannot be used for objects"

    def explain =
      em"""|Objects are final and cannot be extended, thus cannot have the ${hl("abstract")} modifier
           |
           |You may want to define an abstract class:
           | ${hl("abstract")} ${hl("class")} Abstract${mdef.name} { }
           |
           |And extend it in an object:
           | ${hl("object")} ${mdef.name} ${hl("extends")} Abstract${mdef.name} { }
           |""".stripMargin
  }

  class ModifierRedundantForObjects(mdef: untpd.ModuleDef, modifier: String)(implicit ctx: Context)
    extends SyntaxMsg(ModifierRedundantForObjectsID) {
    def msg = em"${hl(modifier)} modifier is redundant for objects"

    def explain =
      em"""|Objects cannot be extended making the ${hl(modifier)} modifier redundant.
           |You may want to define the object without it:
           | ${hl("object")} ${mdef.name} { }
           |""".stripMargin
  }

  class TypedCaseDoesNotExplicitlyExtendTypedEnum(enumDef: Symbol, caseDef: untpd.TypeDef)(implicit ctx: Context)
    extends SyntaxMsg(TypedCaseDoesNotExplicitlyExtendTypedEnumID) {
    def msg = i"explicit extends clause needed because both enum case and enum class have type parameters"

    def explain =
      em"""Enumerations where the enum class as well as the enum case have type parameters need
          |an explicit extends.
          |for example:
          | ${hl("enum")} ${enumDef.name}[T] {
          |  ${hl("case")} ${caseDef.name}[U](u: U) ${hl("extends")} ${enumDef.name}[U]
          | }
          |""".stripMargin
  }

  class IllegalRedefinitionOfStandardKind(kindType: String, name: Name)(implicit ctx: Context)
    extends SyntaxMsg(IllegalRedefinitionOfStandardKindID) {
    def msg = em"illegal redefinition of standard $kindType $name"
    def explain =
      em"""| "$name" is a standard Scala core `$kindType`
           | Please choose a different name to avoid conflicts
           |""".stripMargin
  }

  class NoExtensionMethodAllowed(mdef: untpd.DefDef)(implicit ctx: Context)
    extends SyntaxMsg(NoExtensionMethodAllowedID) {
    def msg = em"No extension method allowed here, since collective parameters are given"
    def explain =
      em"""|Extension method:
           |  `${mdef}`
           |is defined inside an extension clause which has collective parameters.
           |""".stripMargin
  }

  class ExtensionMethodCannotHaveTypeParams(mdef: untpd.DefDef)(implicit ctx: Context)
    extends SyntaxMsg(ExtensionMethodCannotHaveTypeParamsID) {
    def msg = i"Extension method cannot have type parameters since some were already given previously"

    def explain =
      em"""|Extension method:
           |  `${mdef}`
           |has type parameters `[${mdef.tparams.map(_.show).mkString(",")}]`, while the extension clause has
           |it's own type parameters. Please consider moving these to the extension clause's type parameter list.
           |""".stripMargin
  }

  class ExtensionCanOnlyHaveDefs(mdef: untpd.Tree)(implicit ctx: Context)
    extends SyntaxMsg(ExtensionCanOnlyHaveDefsID) {
    def msg = em"Only methods allowed here, since collective parameters are given"
    def explain =
      em"""Extension clauses can only have `def`s
          | `${mdef.show}` is not a valid expression here.
          |""".stripMargin
  }

  class UnexpectedPatternForSummonFrom(tree: Tree[_])(implicit ctx: Context)
    extends SyntaxMsg(UnexpectedPatternForSummonFromID) {
    def msg = em"Unexpected pattern for summonFrom. Expected ${hl("`x: T`")} or ${hl("`_`")}"
    def explain =
      em"""|The pattern "${tree.show}" provided in the ${hl("case")} expression of the ${hl("summonFrom")},
           | needs to be of the form ${hl("`x: T`")} or ${hl("`_`")}.
           |
           | Example usage:
           | inline def a = summonFrom {
           |  case x: T => ???
           | }
           |
           | or
           | inline def a = summonFrom {
           |  case _ => ???
           | }
           |""".stripMargin
  }

  class AnonymousInstanceCannotBeEmpty(impl:  untpd.Template)(implicit ctx: Context)
    extends SyntaxMsg(AnonymousInstanceCannotBeEmptyID) {
    def msg = i"anonymous instance must implement a type or have at least one extension method"
    def explain =
      em"""|Anonymous instances cannot be defined with an empty body. The block
           |`${impl.show}` should either contain an implemented type or at least one extension method.
           |""".stripMargin
  }

  class TypeSpliceInValPattern(expr:  untpd.Tree)(implicit ctx: Context)
    extends SyntaxMsg(TypeSpliceInValPatternID) {
    def msg = "Type splices cannot be used in val patterns. Consider using `match` instead."
    def explain =
      em"""|Type splice: `$$${expr.show}` cannot be used in a `val` pattern. Consider rewriting the `val` pattern
           |as a `match` with a corresponding `case` to replace the `val`.
           |""".stripMargin
  }

  class ModifierNotAllowedForDefinition(flag: Flag)(implicit ctx: Context)
    extends SyntaxMsg(ModifierNotAllowedForDefinitionID) {
    def msg = s"Modifier `${flag.flagsString}` is not allowed for this definition"
    def explain = ""
  }
}
