# What's this?

Coprocessing
is an attempt to interface
Processing, the well-known Computer Graphics programming environment,
with the upcoming Scala 3 language and its ecosystem.

# Why?

- Ergonomy and expressivity:
the Processing language is,
at the time of writing,
based on Java 1.5
which came out in 2004,
the same year that Scala 1.0 was released.
Knowing that,
one could wonder if
Processing is taking full advantage of the
advances that have been made in programming language design in recent decades.
In my opinion, it largely doesn't.
Scala 3 is a potent programming language that can bring those advances
while remaining fully binary-compatible with Processing as it currently stands.

- Standardization:
Coprocessing will not include a preprocessor nor a dedicated development environment.
Instead,
the stock Scala language and any programming environment that works with it
will be able to be used.

- Robustness:
Functional programming concepts
such as
immutability and
expressive types
can be used to produce an API that eliminates many of Processing's existing gotchas at compile-time.
This can decrease the amount of time spent on headscratching
and allow programmers to focus on more exciting aspects of Computer Graphics.
