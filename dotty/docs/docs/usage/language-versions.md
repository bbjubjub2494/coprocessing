---
layout: doc-page
title: "Language Versions"
---

The default Scala language version currently supported by the Dotty compiler is `3.0`. There are also other language versions that can be specified instead:

 - `3.1`: A preview of changes introduced in the next version after 3.0. Some Scala-2 specific idioms will be dropped in this version. The feature set supported by this version will be refined over time  as we approach its release.

 - `3.0-migration`: Same as `3.0` but with a Scala 2 compatibility mode that helps moving Scala 2.13 sources over to Scala 3. In particular, it

    - flags some Scala 2 constructs that are disallowed in Scala 3 as migration warnings instead of hard errors,
    - changes some rules to be more lenient and backwards compatible with Scala 2.13
    - gives some additional warnings where the semantics has changed between Scala 2.13 and 3.0
    - in conjunction with `-rewrite`, offer code rewrites from Scala 2.13 to 3.0.

 - `3.1-migration`: Same as `3.1` but with additional helpers to migrate from `3.0`. Similarly to the helpers available under `3.0-migration`, these include migration warnings and optional rewrites.

There are two ways to specify a language version.

 - With a `-source` command line setting, e.g. `-source 3.0-migration`.
 - With a `scala.language` import at the top of a compilation unit, e.g:

```scala
package p
import scala.language.`3.1`

class C { ... }
```

Language imports supersede command-line settings in the compilation units where they are specified. Only one language import is allowed in a compilation unit, and it must come before any definitions in that unit.
