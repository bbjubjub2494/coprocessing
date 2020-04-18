/* sbt -- Simple Build Tool
 * Copyright 2008, 2009 Mark Harrah
 */
package xsbt;

import java.util.Optional;

import xsbti.Position;
import xsbti.Severity;

import dotty.tools.*;
import dotty.tools.dotc.*;
import dotty.tools.dotc.interfaces.Diagnostic;
import dotty.tools.dotc.util.SourceFile;
import dotty.tools.dotc.util.SourcePosition;
import dotty.tools.dotc.reporting.*;
import dotty.tools.dotc.reporting.Message;
import dotty.tools.dotc.reporting.messages;
import dotty.tools.dotc.core.Contexts.*;

import static dotty.tools.dotc.reporting.Diagnostic.*;

final public class DelegatingReporter extends AbstractReporter {
  private final xsbti.Reporter delegate;

  private static final Position noPosition = new Position() {
    public Optional<java.io.File> sourceFile() {
      return Optional.empty();
    }
    public Optional<String> sourcePath() {
      return Optional.empty();
    }
    public Optional<Integer> line() {
      return Optional.empty();
    }
    public String lineContent() {
      return "";
    }
    public Optional<Integer> offset() {
      return Optional.empty();
    }
    public Optional<Integer> pointer() {
      return Optional.empty();
    }
    public Optional<String> pointerSpace() {
      return Optional.empty();
    }
  };

  public DelegatingReporter(xsbti.Reporter delegate) {
    super();
    this.delegate = delegate;
  }

  @Override
  public void printSummary(Context ctx) {
    delegate.printSummary();
  }

  public void doReport(dotty.tools.dotc.reporting.Diagnostic dia, Context ctx) {
    Severity severity;
    switch (dia.level()) {
      case Diagnostic.ERROR:
        severity = Severity.Error;
        break;
      case Diagnostic.WARNING:
        severity = Severity.Warn;
        break;
      case Diagnostic.INFO:
        severity = Severity.Info;
        break;
      default:
        throw new IllegalArgumentException("Bad diagnostic level: " + dia.level());
    }

    Position position;
    if (dia.pos().exists()) {
      SourcePosition pos = dia.pos();
      SourceFile src = pos.source();
      position = new Position() {
        public Optional<java.io.File> sourceFile() {
          if (!src.exists()) return Optional.empty();
          else return Optional.ofNullable(src.file().file());
        }
        public Optional<String> sourcePath() {
          if (!src.exists()) return Optional.empty();
          else return Optional.ofNullable(src.file().path());
        }
        public Optional<Integer> line() {
          int line = pos.line() + 1;
          if (line == -1) return Optional.empty();
          else return Optional.of(line);
        }
        public String lineContent() {
          String line = pos.lineContent();
          if (line.endsWith("\r\n"))
            return line.substring(0, line.length() - 2);
          else if (line.endsWith("\n") || line.endsWith("\u000c"))
            return line.substring(0, line.length() - 1);
          else
            return line;
        }
        public Optional<Integer> offset() {
          return Optional.of(pos.point());
        }
        public Optional<Integer> pointer() {
          if (!src.exists()) return Optional.empty();
          else return Optional.of(pos.point() - src.startOfLine(pos.point()));
        }
        public Optional<String> pointerSpace() {
          if (!src.exists()) return Optional.empty();
          else {
            String lineContent = this.lineContent();
            int pointer = this.pointer().get();
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < pointer; i++)
              result.append(lineContent.charAt(i) == '\t' ? '\t' : ' ');
            return Optional.of(result.toString());
          }
        }
      };
    } else {
      position = noPosition;
    }

    Message message = dia.msg();
    StringBuilder rendered = new StringBuilder();
    rendered.append(messageAndPos(message, dia.pos(), diagnosticLevel(dia), ctx));
    boolean shouldExplain = dotty.tools.dotc.reporting.Diagnostic.shouldExplain(dia, ctx);
    if (shouldExplain && !message.explanation().isEmpty()) {
      rendered.append(explanation(message, ctx));
    }

    delegate.log(new Problem(position, message.msg(), severity, rendered.toString()));
  }
}
