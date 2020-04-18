---
title: Coprocessing Documentation
layout: doc-page
---

# Coprocessing
## Scala 3 interface to Processing
The Coprocessing library
allows one to write
[Processing](https://processing.org) applications
in the [Scala](https://dotty.epfl.ch) Programming Language.

## Try it now!
Coming soon

## Contribute
Development is hosted on [Github](https://github.com/bbjubjub2494/coprocessing)

## Goals

<dl>

<dt>Robustness
<dd>
Functional programming concepts
such as
immutability and
expressive types
can be used to produce an API that eliminates many of Processing's existing gotchas at compile-time.
This can decrease the amount of time spent on headscratching
and allow programmers to focus on more exciting aspects of Computer Graphics.

<dt>Standardization
<dd>
Coprocessing will not include a preprocessor nor a dedicated development environment.
Instead,
the stock Scala language and any programming environment that works with it
will be able to be used.
<dt>Ergonomy and expressivity</dt>
<dd>
the main Processing language is,
at the time of writing,
based on Java 1.5,
a language that, due to its age, lacks a lot of the features that programmers have come to expect.
Scala 3 is a potent programming language that can bring those features
while remaining fully binary-compatible with Processing as it currently stands.
</dl>

## License
Coprocessing is licensed under the GNU LGPL 3 license.
