---
layout: blog-page
title: Why you don't need Coprocessing
author: Louis Bettens
date: 2020-04-12
---

What if the first post on this blog was
about tearing down the entire point of the project?
What if Coprocessing is useless?
This might be a bold move, but
this is what I will be arguing today.

# Maven Artefacts
In order to re-use the code that is already in Processing 3,
I knew I would need a jar file of it.
It turns out that there is one on Maven.
Unfortunately, it looks like it hasn't been updated diligently,
but I will deal with this issue later
since it is still recent enough to play with.
So, with a simple `libraryDependencies` entry,
I am able to successfully start Processing 3 Sketches
on top of OpenJDK 8.

# Interface passthrough
Since the interface I am creating should not be considered exhaustive,
I made sure to add an operation that allows user code to use the bare Processing API, should it need to.
I called it [legacy](coprocessing.legacy)
and used some term inference magic to make it feel nicer.
It logically follows that
everything that can be done with bare Processing
can also be done in Coprocessing,
even at that early a stage of its development.

# Coprocessing minus minus ?
As we realized in this article,
thanks to the JVM,
Processing can already be interfaced from any Scala version without any wrappers.
At the end of this post is a Ammonite script that demonstrates that.
Coprocessing will still have advantages over Coprocessing--,
as I hope to show in the future,
but it isn't strictly necessary.
Coprocessing-- has,
among other features,
lambda expressions,
static members,
language server support,
and the entire Scala standard library.

# Attachment
```scala
import $ivy.`org.processing:core:3.3.7`

import processing.core._

object Sketch extends PApplet {
  override def settings() = {
    size(1000, 800)
  }
  override def setup() = {
    background(255, 200, 0)
  }
}

// Sketch should flash on the screen then exit
// It is left as an exercise for the reader to find out how to make it stay open
PApplet.runSketch(Array("Sketch"), Sketch)
```
