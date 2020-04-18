---
layout: doc-page
title: "Scala Syntax Summary"
---

The following descriptions of Scala tokens uses literal characters `‘c’` when
referring to the ASCII fragment `\u0000` – `\u007F`.

_Unicode escapes_ are used to represent the Unicode character with the given
hexadecimal code:

```ebnf
UnicodeEscape ::= ‘\’ ‘u’ {‘u’} hexDigit hexDigit hexDigit hexDigit
hexDigit      ::= ‘0’ | … | ‘9’ | ‘A’ | … | ‘F’ | ‘a’ | … | ‘f’
```

Informal descriptions are typeset as `“some comment”`.

### Lexical Syntax
The lexical syntax of Scala is given by the following grammar in EBNF
form.

```ebnf
whiteSpace       ::=  ‘\u0020’ | ‘\u0009’ | ‘\u000D’ | ‘\u000A’
upper            ::=  ‘A’ | … | ‘Z’ | ‘\$’ | ‘_’  “… and Unicode category Lu”
lower            ::=  ‘a’ | … | ‘z’ “… and Unicode category Ll”
letter           ::=  upper | lower “… and Unicode categories Lo, Lt, Nl”
digit            ::=  ‘0’ | … | ‘9’
paren            ::=  ‘(’ | ‘)’ | ‘[’ | ‘]’ | ‘{’ | ‘}’ | ‘'(’ | ‘'[’ | ‘'{’
delim            ::=  ‘`’ | ‘'’ | ‘"’ | ‘.’ | ‘;’ | ‘,’
opchar           ::=  “printableChar not matched by (whiteSpace | upper | lower |
                       letter | digit | paren | delim | opchar | Unicode_Sm |
                       Unicode_So)”
printableChar    ::=  “all characters in [\u0020, \u007F] inclusive”
charEscapeSeq    ::=  ‘\’ (‘b’ | ‘t’ | ‘n’ | ‘f’ | ‘r’ | ‘"’ | ‘'’ | ‘\’)

op               ::=  opchar {opchar}
varid            ::=  lower idrest
alphaid          ::=  upper idrest
                   |  varid
plainid          ::=  alphaid
                   |  op
id               ::=  plainid
                   |  ‘`’ { charNoBackQuoteOrNewline | UnicodeEscape | charEscapeSeq } ‘`’
                   |  INT                           // interpolation id, only for quasi-quotes
idrest           ::=  {letter | digit} [‘_’ op]
quoteId          ::=  ‘'’ alphaid

integerLiteral   ::=  (decimalNumeral | hexNumeral) [‘L’ | ‘l’]
decimalNumeral   ::=  ‘0’ | nonZeroDigit [{digit | ‘_’} digit]
hexNumeral       ::=  ‘0’ (‘x’ | ‘X’) hexDigit [{hexDigit | ‘_’} hexDigit]
nonZeroDigit     ::=  ‘1’ | … | ‘9’

floatingPointLiteral
                 ::=  [decimalNumeral] ‘.’ digit [{digit | ‘_’} digit] [exponentPart] [floatType]
                   |  decimalNumeral exponentPart [floatType]
                   |  decimalNumeral floatType
exponentPart     ::=  (‘E’ | ‘e’) [‘+’ | ‘-’] digit [{digit | ‘_’} digit]
floatType        ::=  ‘F’ | ‘f’ | ‘D’ | ‘d’

booleanLiteral   ::=  ‘true’ | ‘false’

characterLiteral ::=  ‘'’ (printableChar | charEscapeSeq) ‘'’

stringLiteral    ::=  ‘"’ {stringElement} ‘"’
                   |  ‘"""’ multiLineChars ‘"""’
stringElement    ::=  printableChar \ (‘"’ | ‘\’)
                   |  UnicodeEscape
                   |  charEscapeSeq
multiLineChars   ::=  {[‘"’] [‘"’] char \ ‘"’} {‘"’}
processedStringLiteral
                 ::=  alphaid ‘"’ {printableChar \ (‘"’ | ‘$’) | escape} ‘"’
                   |  alphaid ‘"""’ {[‘"’] [‘"’] char \ (‘"’ | ‘$’) | escape} {‘"’} ‘"""’
escape           ::=  ‘$$’
                   |  ‘$’ letter { letter | digit }
                   |  ‘{’ Block  [‘;’ whiteSpace stringFormat whiteSpace] ‘}’
stringFormat     ::=  {printableChar \ (‘"’ | ‘}’ | ‘ ’ | ‘\t’ | ‘\n’)}

symbolLiteral    ::=  ‘'’ plainid // until 2.13

comment          ::=  ‘/*’ “any sequence of characters; nested comments are allowed” ‘*/’
                   |  ‘//’ “any sequence of characters up to end of line”

nl               ::=  “new line character”
semi             ::=  ‘;’ |  nl {nl}
colonEol         ::=  ": at end of line that can start a tenmplate body"
```

## Keywords

### Regular keywords

```
abstract  case      catch     class     def       do        else      enum
export    extends   false     final     finally   for       given     if
implicit  import    lazy      match     new       null      object    package
private   protected override  return    super     sealed    then      throw
trait     true      try       type      val       var       while     with
yield
:         =         <-        =>        <:        :>        #         @
=>>       ?=>
```

### Soft keywords

```
as        derives   extension inline    on        opaque    open      transparent
using
*         +         -
```

## Context-free Syntax

The context-free syntax of Scala is given by the following EBNF
grammar:

### Literals and Paths
```ebnf
SimpleLiteral     ::=  [‘-’] integerLiteral
                    |  [‘-’] floatingPointLiteral
                    |  booleanLiteral
                    |  characterLiteral
                    |  stringLiteral
Literal           ::=  SimpleLiteral
                    |  processedStringLiteral
                    |  symbolLiteral
                    |  ‘null’

QualId            ::=  id {‘.’ id}
ids               ::=  id {‘,’ id}

Path              ::=  StableId
                    |  [id ‘.’] ‘this’
StableId          ::=  id
                    |  Path ‘.’ id
                    |  [id ‘.’] ‘super’ [ClassQualifier] ‘.’ id
ClassQualifier    ::=  ‘[’ id ‘]’
```

### Types
```ebnf
Type              ::=  FunType
                    |  HkTypeParamClause ‘=>>’ Type                             TypeLambda(ps, t)
                    |  MatchType
                    |  InfixType
FunType           ::=  FunArgTypes (‘=>’ | ‘?=>’) Type                          Function(ts, t)
                    |  HKTypeParamClause '=>' Type                              PolyFunction(ps, t)
FunArgTypes       ::=  InfixType
                    |  ‘(’ [ FunArgType {‘,’ FunArgType } ] ‘)’
                    |  ‘(’ TypedFunParam {‘,’ TypedFunParam } ‘)’
TypedFunParam     ::=  id ‘:’ Type
MatchType         ::=  InfixType `match` ‘{’ TypeCaseClauses ‘}’
InfixType         ::=  RefinedType {id [nl] RefinedType}                        InfixOp(t1, op, t2)
RefinedType       ::=  WithType {[nl | colonEol] Refinement}                    RefinedTypeTree(t, ds)
WithType          ::=  AnnotType {‘with’ AnnotType}                             (deprecated)
AnnotType         ::=  SimpleType {Annotation}                                  Annotated(t, annot)
SimpleType        ::=  SimpleType TypeArgs                                      AppliedTypeTree(t, args)
                    |  SimpleType ‘#’ id                                        Select(t, name)
                    |  StableId
                    |  Path ‘.’ ‘type’                                          SingletonTypeTree(p)
                    |  ‘(’ ArgTypes ‘)’                                         Tuple(ts)
                    |  ‘?’ SubtypeBounds
                    |  Refinement                                               RefinedTypeTree(EmptyTree, refinement)
                    |  SimpleLiteral                                            SingletonTypeTree(l)
                    |  ‘$’ ‘{’ Block ‘}’
ArgTypes          ::=  Type {‘,’ Type}
                    |  NamedTypeArg {‘,’ NamedTypeArg}
FunArgType        ::=  Type
                    |  ‘=>’ Type                                                PrefixOp(=>, t)
ParamType         ::=  [‘=>’] ParamValueType
ParamValueType    ::=  Type [‘*’]                                               PostfixOp(t, "*")
TypeArgs          ::=  ‘[’ ArgTypes ‘]’                                         ts
NamedTypeArg      ::=  id ‘=’ Type                                              NamedArg(id, t)
NamedTypeArgs     ::=  ‘[’ NamedTypeArg {‘,’ NamedTypeArg} ‘]’                  nts
Refinement        ::=  ‘{’ [RefineDcl] {semi [RefineDcl]} ‘}’                   ds
SubtypeBounds     ::=  [‘>:’ Type] [‘<:’ Type] | INT                            TypeBoundsTree(lo, hi)
TypeParamBounds   ::=  SubtypeBounds {‘:’ Type}                                 ContextBounds(typeBounds, tps)
Types             ::=  Type {‘,’ Type}
```

### Expressions
```ebnf
Expr              ::=  FunParams (‘=>’ | ‘?=>’) Expr                            Function(args, expr), Function(ValDef([implicit], id, TypeTree(), EmptyTree), expr)
                    |  Expr1
BlockResult       ::=  FunParams (‘=>’ | ‘?=>’) Block
                    |  Expr1
FunParams         ::=  Bindings
                    |  id
                    |  ‘_’
Expr1             ::=  [‘inline’] ‘if’ ‘(’ Expr ‘)’ {nl} Expr [[semi] ‘else’ Expr] If(Parens(cond), thenp, elsep?)
                    |  [‘inline’] ‘if’  Expr ‘then’ Expr [[semi] ‘else’ Expr]    If(cond, thenp, elsep?)
                    |  ‘while’ ‘(’ Expr ‘)’ {nl} Expr                           WhileDo(Parens(cond), body)
                    |  ‘while’ Expr ‘do’ Expr                                   WhileDo(cond, body)
                    |  ‘try’ Expr Catches [‘finally’ Expr]                      Try(expr, catches, expr?)
                    |  ‘try’ Expr [‘finally’ Expr]                              Try(expr, Nil, expr?)
                    |  ‘throw’ Expr                                             Throw(expr)
                    |  ‘return’ [Expr]                                          Return(expr?)
                    |  ForExpr
                    |  HkTypeParamClause ‘=>’ Expr                              PolyFunction(ts, expr)
                    |  [SimpleExpr ‘.’] id ‘=’ Expr                             Assign(expr, expr)
                    |  SimpleExpr1 ArgumentExprs ‘=’ Expr                       Assign(expr, expr)
                    |  PostfixExpr [Ascription]
                    |  ‘inline’ InfixExpr MatchClause
Ascription        ::=  ‘:’ InfixType                                            Typed(expr, tp)
                    |  ‘:’ Annotation {Annotation}                              Typed(expr, Annotated(EmptyTree, annot)*)
Catches           ::=  ‘catch’ (Expr | ExprCaseClause)
PostfixExpr       ::=  InfixExpr [id]                                           PostfixOp(expr, op)
InfixExpr         ::=  PrefixExpr
                    |  InfixExpr id [nl] InfixExpr                              InfixOp(expr, op, expr)
                    |  InfixExpr MatchClause
MatchClause       ::=  ‘match’ ‘{’ CaseClauses ‘}’                              Match(expr, cases)
PrefixExpr        ::=  [‘-’ | ‘+’ | ‘~’ | ‘!’] SimpleExpr                       PrefixOp(expr, op)
SimpleExpr        ::=  Path
                    |  Literal
                    |  ‘_’
                    |  BlockExpr
                    |  ‘$’ ‘{’ Block ‘}’
                    |  Quoted
                    |  quoteId     // only inside splices
                    |  ‘new’ ConstrApp {‘with’ ConstrApp} [TemplateBody]        New(constr | templ)
                    |  ‘new’ TemplateBody
                    |  ‘(’ ExprsInParens ‘)’                                    Parens(exprs)
                    |  SimpleExpr ‘.’ id                                        Select(expr, id)
                    |  SimpleExpr ‘.’ MatchClause
                    |  SimpleExpr TypeArgs                                      TypeApply(expr, args)
                    |  SimpleExpr ArgumentExprs                                 Apply(expr, args)
                    |  SimpleExpr ‘_’                                           PostfixOp(expr, _)
                    |  XmlExpr
Quoted            ::=  ‘'’ ‘{’ Block ‘}’
                    |  ‘'’ ‘[’ Type ‘]’
ExprsInParens     ::=  ExprInParens {‘,’ ExprInParens}
ExprInParens      ::=  PostfixExpr ‘:’ Type                                     -- normal Expr allows only RefinedType here
                    |  Expr
ParArgumentExprs  ::=  ‘(’ [‘using’] ExprsInParens ‘)’                          exprs
                    |  ‘(’ [ExprsInParens ‘,’] PostfixExpr ‘:’ ‘_’ ‘*’ ‘)’      exprs :+ Typed(expr, Ident(wildcardStar))
ArgumentExprs     ::=  ParArgumentExprs
                    |  [nl] BlockExpr
BlockExpr         ::=  ‘{’ (CaseClauses | Block) ‘}’
Block             ::=  {BlockStat semi} [BlockResult]                           Block(stats, expr?)
BlockStat         ::=  Import
                    |  {Annotation [nl]} [‘implicit’ | ‘lazy’] Def
                    |  {Annotation [nl]} {LocalModifier} TmplDef
                    |  Expr1

ForExpr           ::=  ‘for’ (‘(’ Enumerators ‘)’ | ‘{’ Enumerators ‘}’)        ForYield(enums, expr)
                       {nl} [‘yield’] Expr
                    |  ‘for’ Enumerators (‘do’ Expr | ‘yield’ Expr)             ForDo(enums, expr)
Enumerators       ::=  Generator {semi Enumerator | Guard}
Enumerator        ::=  Generator
                    |  Guard
                    |  Pattern1 ‘=’ Expr                                        GenAlias(pat, expr)
Generator         ::=  [‘case’] Pattern1 ‘<-’ Expr                                       GenFrom(pat, expr)
Guard             ::=  ‘if’ PostfixExpr

CaseClauses       ::=  CaseClause { CaseClause }                                Match(EmptyTree, cases)
CaseClause        ::=  ‘case’ Pattern [Guard] ‘=>’ Block                        CaseDef(pat, guard?, block)   // block starts at =>
ExprCaseClause    ::=  ‘case’ Pattern [Guard] ‘=>’ Expr
ImplicitCaseClauses ::=  ImplicitCaseClause { ImplicitCaseClause }
ImplicitCaseClause  ::=  ‘case’ PatVar [‘:’ RefinedType] [Guard] ‘=>’ Block
TypeCaseClauses   ::=  TypeCaseClause { TypeCaseClause }
TypeCaseClause    ::=  ‘case’ InfixType ‘=>’ Type [nl]

Pattern           ::=  Pattern1 { ‘|’ Pattern1 }                                Alternative(pats)
Pattern1          ::=  Pattern2 [‘:’ RefinedType]                               Bind(name, Typed(Ident(wildcard), tpe))
                    |  ‘given’ PatVar ‘:’ RefinedType
Pattern2          ::=  [id ‘@’] InfixPattern                                    Bind(name, pat)
InfixPattern      ::=  SimplePattern { id [nl] SimplePattern }                  InfixOp(pat, op, pat)
SimplePattern     ::=  PatVar                                                   Ident(wildcard)
                    |  Literal                                                  Bind(name, Ident(wildcard))
                    |  ‘(’ [Patterns] ‘)’                                       Parens(pats) Tuple(pats)
                    |  Quoted
                    |  XmlPattern
                    |  SimplePattern1 [TypeArgs] [ArgumentPatterns]
SimplePattern1    ::=  Path
                    |  SimplePattern1 ‘.’ id
PatVar            ::=  varid
                    |  ‘_’
Patterns          ::=  Pattern {‘,’ Pattern}
ArgumentPatterns  ::=  ‘(’ [Patterns] ‘)’                                       Apply(fn, pats)
                    |  ‘(’ [Patterns ‘,’] Pattern2 ‘:’ ‘_’ ‘*’ ‘)’
```

### Type and Value Parameters
```ebnf
ClsTypeParamClause::=  ‘[’ ClsTypeParam {‘,’ ClsTypeParam} ‘]’
ClsTypeParam      ::=  {Annotation} [‘+’ | ‘-’]                                 TypeDef(Modifiers, name, tparams, bounds)
                       id [HkTypeParamClause] TypeParamBounds                   Bound(below, above, context)

DefTypeParamClause::=  ‘[’ DefTypeParam {‘,’ DefTypeParam} ‘]’
DefTypeParam      ::=  {Annotation} id [HkTypeParamClause] TypeParamBounds

TypTypeParamClause::=  ‘[’ TypTypeParam {‘,’ TypTypeParam} ‘]’
TypTypeParam      ::=  {Annotation} id [HkTypeParamClause] SubtypeBounds

HkTypeParamClause ::=  ‘[’ HkTypeParam {‘,’ HkTypeParam} ‘]’
HkTypeParam       ::=  {Annotation} [‘+’ | ‘-’] (id [HkTypeParamClause] | ‘_’)
                       SubtypeBounds

ClsParamClauses   ::=  {ClsParamClause} [[nl] ‘(’ [‘implicit’] ClsParams ‘)’]
ClsParamClause    ::=  [nl] ‘(’ ClsParams ‘)’
                    |  [nl] ‘(’ ‘using’ (ClsParams | Types) ‘)’
ClsParams         ::=  ClsParam {‘,’ ClsParam}
ClsParam          ::=  {Annotation}                                             ValDef(mods, id, tpe, expr) -- point of mods on val/var
                       [{Modifier} (‘val’ | ‘var’) | ‘inline’] Param
Param             ::=  id ‘:’ ParamType [‘=’ Expr]
                    |  INT

DefParamClauses   ::=  {DefParamClause} [[nl] ‘(’ [‘implicit’] DefParams ‘)’]
DefParamClause    ::=  [nl] ‘(’ DefParams ‘)’ | UsingParamClause
UsingParamClause  ::=  [nl] ‘(’ ‘using’ (DefParams | Types) ‘)’
DefParams         ::=  DefParam {‘,’ DefParam}
DefParam          ::=  {Annotation} [‘inline’] Param                            ValDef(mods, id, tpe, expr) -- point of mods at id.
ClosureMods       ::=  { ‘implicit’ | ‘given’}
```

### Bindings and Imports
```ebnf
Bindings          ::=  ‘(’ [[‘given’] Binding {‘,’ Binding}] ‘)’
Binding           ::=  (id | ‘_’) [‘:’ Type]                                    ValDef(_, id, tpe, EmptyTree)

Modifier          ::=  LocalModifier
                    |  AccessModifier
                    |  ‘override’
                    |  ‘opaque’
LocalModifier     ::=  ‘abstract’
                    |  ‘final’
                    |  ‘sealed’
                    |  ‘open’
                    |  ‘implicit’
                    |  ‘lazy’
                    |  ‘inline’
AccessModifier    ::=  (‘private’ | ‘protected’) [AccessQualifier]
AccessQualifier   ::=  ‘[’ id ‘]’

Annotation        ::=  ‘@’ SimpleType {ParArgumentExprs}                        Apply(tpe, args)

Import            ::=  ‘import’ ImportExpr {‘,’ ImportExpr}
ImportExpr        ::=  StableId ‘.’ ImportSpec                                  Import(expr, sels)
ImportSpec        ::=  id
                    | ‘_’
                    | ‘{’ ImportSelectors) ‘}’
ImportSelectors   ::=  id [‘=>’ id | ‘=>’ ‘_’] [‘,’ ImportSelectors]
                    |  WildCardSelector {‘,’ WildCardSelector}
WildCardSelector  ::=  ‘given’ (‘_' | InfixType)
                    |  ‘_'
Export            ::=  ‘export’ [‘given’] ImportExpr {‘,’ ImportExpr}
```

### Declarations and Definitions
```ebnf
RefineDcl         ::=  ‘val’ ValDcl
                    |  ‘def’ DefDcl
                    |  ‘type’ {nl} TypeDcl
                    |  INT
Dcl               ::=  RefineDcl
                    |  ‘var’ VarDcl
ValDcl            ::=  ids ‘:’ Type                                             PatDef(_, ids, tpe, EmptyTree)
VarDcl            ::=  ids ‘:’ Type                                             PatDef(_, ids, tpe, EmptyTree)
DefDcl            ::=  DefSig ‘:’ Type                                          DefDef(_, name, tparams, vparamss, tpe, EmptyTree)
DefSig            ::=  id [DefTypeParamClause] DefParamClauses
                    |  ExtParamClause [nl] [‘.’] id DefParamClauses
TypeDcl           ::=  id [TypeParamClause] SubtypeBounds [‘=’ Type]           TypeDefTree(_, name, tparams, bound

Def               ::=  ‘val’ PatDef
                    |  ‘var’ VarDef
                    |  ‘def’ DefDef
                    |  ‘type’ {nl} TypeDcl
                    |  TmplDef
                    |  INT
PatDef            ::=  ids [‘:’ Type] ‘=’ Expr
                    |  Pattern2 [‘:’ Type | Ascription] ‘=’ Expr                PatDef(_, pats, tpe?, expr)
VarDef            ::=  PatDef
                    |  ids ‘:’ Type ‘=’ ‘_’
DefDef            ::=  DefSig [(‘:’ | ‘<:’) Type] ‘=’ Expr                      DefDef(_, name, tparams, vparamss, tpe, expr)
                    |  ‘this’ DefParamClause DefParamClauses ‘=’ ConstrExpr     DefDef(_, <init>, Nil, vparamss, EmptyTree, expr | Block)

TmplDef           ::=  ([‘case’] ‘class’ | ‘trait’) ClassDef
                    |  [‘case’] ‘object’ ObjectDef
                    |  ‘enum’ EnumDef
                    |  ‘given’ GivenDef
                    |  ‘extension’ ExtensionDef
ClassDef          ::=  id ClassConstr [Template]                                ClassDef(mods, name, tparams, templ)
ClassConstr       ::=  [ClsTypeParamClause] [ConstrMods] ClsParamClauses        with DefDef(_, <init>, Nil, vparamss, EmptyTree, EmptyTree) as first stat
ConstrMods        ::=  {Annotation} [AccessModifier]
ObjectDef         ::=  id [Template]                                            ModuleDef(mods, name, template)  // no constructor
EnumDef           ::=  id ClassConstr InheritClauses EnumBody                   EnumDef(mods, name, tparams, template)
GivenDef          ::=  [GivenSig] [‘_’ ‘<:’] Type ‘=’ Expr
                    |  [GivenSig] ConstrApps [TemplateBody]
GivenSig          ::=  [id] [DefTypeParamClause] {UsingParamClause} ‘as’
ExtensionDef      ::=  [id] [‘on’ ExtParamClause {UsingParamClause}]
                       TemplateBody
ExtMethods        ::=  [nl] ‘{’ ‘def’ DefDef {semi ‘def’ DefDef} ‘}’
ExtParamClause    ::=  [DefTypeParamClause] ‘(’ DefParam ‘)’
Template          ::=  InheritClauses [TemplateBody]                            Template(constr, parents, self, stats)
InheritClauses    ::=  [‘extends’ ConstrApps] [‘derives’ QualId {‘,’ QualId}]
ConstrApps        ::=  ConstrApp {(‘,’ | ‘with’) ConstrApp}
ConstrApp         ::=  AnnotType {ParArgumentExprs}                             Apply(tp, args)
ConstrExpr        ::=  SelfInvocation
                    |  ‘{’ SelfInvocation {semi BlockStat} ‘}’
SelfInvocation    ::=  ‘this’ ArgumentExprs {ArgumentExprs}

TemplateBody      ::=  [nl | colonEol]
                       ‘{’ [SelfType] TemplateStat {semi TemplateStat} ‘}’      (self, stats)
TemplateStat      ::=  Import
                    |  Export
                    |  {Annotation [nl]} {Modifier} Def
                    |  {Annotation [nl]} {Modifier} Dcl
                    |  Expr1
                    |
SelfType          ::=  id [‘:’ InfixType] ‘=>’                                  ValDef(_, name, tpt, _)
                    |  ‘this’ ‘:’ InfixType ‘=>’

EnumBody          ::=  [nl | colonEol]
                       ‘{’ [SelfType] EnumStat {semi EnumStat} ‘}’
EnumStat          ::=  TemplateStat
                    |  {Annotation [nl]} {Modifier} EnumCase
EnumCase          ::=  ‘case’ (id ClassConstr [‘extends’ ConstrApps]] | ids)

TopStatSeq        ::=  TopStat {semi TopStat}
TopStat           ::=  Import
                    |  Export
                    |  {Annotation [nl]} {Modifier} Def
                    |  Packaging
                    |  PackageObject
                    |
Packaging         ::=  ‘package’ QualId [nl | colonEol] ‘{’ TopStatSeq ‘}’      Package(qid, stats)
PackageObject     ::=  ‘package’ ‘object’ ObjectDef                             object with package in mods.

CompilationUnit   ::=  {‘package’ QualId semi} TopStatSeq                       Package(qid, stats)
```
