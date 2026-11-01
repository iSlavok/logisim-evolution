/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.test;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Runs the test vector of every circuit of a project in one go, for the command line: prints a
 * line per circuit and a summary, and can write a JUnit XML report.
 */
public final class BatchTestRunner {

  /** Exit code when every test passed. */
  public static final int EXIT_PASSED = 0;
  /** Exit code when a row failed. */
  public static final int EXIT_FAILED = 1;
  /** Exit code when a vector could not be used or no vector was found. */
  public static final int EXIT_ERROR = 2;
  /** Failed rows printed per circuit. */
  static final int MAX_FAILURES_SHOWN = 10;

  /** Outcome of one circuit's test. */
  public record Result(String circuit, File vector, int rows, int passed, int failed, long nanos,
      String error, List<String> failures) {}

  private BatchTestRunner() {}

  /**
   * Finds the vector of every circuit and runs it.
   *
   * @param project the loaded project
   * @param testDir folder holding {@code <circuit>.txt} files, or null for the project's bindings
   *     ({@code tests/<circuit>.txt} next to the project and the choices kept in its .tests file)
   * @param junitFile where to write a JUnit XML report, or null
   * @param out where to print progress and results
   * @return one of the exit codes
   */
  public static int run(Project project, File testDir, File junitFile, PrintStream out) throws IOException {
    final var file = project.getLogisimFile();
    final var bindings = TestBindings.load(file.getLoader().getMainFile());
    final var results = new ArrayList<Result>();
    final var tested = new HashSet<String>();
    final var start = System.nanoTime();
    for (final var circuit : file.getCircuits()) {
      final var vector = testDir == null
          ? bindings.getVectorFile(circuit.getName())
          : existing(new File(testDir, circuit.getName() + TestBindings.VECTOR_SUFFIX));
      if (vector == null) continue;
      tested.add(vector.getName());
      final var result = runOne(project, circuit, vector);
      results.add(result);
      print(out, result);
    }
    if (testDir != null) {
      final var unmatched = testDir.listFiles((dir, name) -> name.endsWith(TestBindings.VECTOR_SUFFIX));
      if (unmatched != null) {
        Arrays.sort(unmatched);
        for (final var vector : unmatched) {
          if (tested.contains(vector.getName())) continue;
          final var name = vector.getName().substring(0, vector.getName().length() - TestBindings.VECTOR_SUFFIX.length());
          final var result = new Result(name, vector, 0, 0, 0, 0, S.get("batchTestNoCircuit"), List.of());
          results.add(result);
          print(out, result);
        }
      }
    }
    final var nanos = System.nanoTime() - start;
    if (results.isEmpty()) {
      out.println(S.get("batchTestNone"));
      return EXIT_ERROR;
    }
    var passed = 0;
    var failed = 0;
    var errors = 0;
    for (final var result : results) {
      if (result.error() != null) errors++;
      else if (result.failed() > 0) failed++;
      else passed++;
    }
    out.println(S.get("batchTestSummary", Integer.toString(results.size()), Integer.toString(passed),
        Integer.toString(failed), Integer.toString(errors), TestFrame.formatDuration(nanos)));
    if (junitFile != null) Files.writeString(junitFile.toPath(), junitXml(file.getName(), results, nanos), StandardCharsets.UTF_8);
    return errors > 0 ? EXIT_ERROR : failed > 0 ? EXIT_FAILED : EXIT_PASSED;
  }

  private static File existing(File file) {
    return file.isFile() ? file : null;
  }

  /** Runs one circuit's vector, in a fresh state of the circuit, on this thread. */
  public static Result runOne(Project project, Circuit circuit, File vectorFile) {
    final var start = System.nanoTime();
    final TestVector vector;
    final TestVectorEvaluator evaluator;
    try {
      vector = new TestVector(vectorFile);
      final var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
      evaluator = new TestVectorEvaluator(state, vector);
    } catch (Exception e) {
      return new Result(circuit.getName(), vectorFile, 0, 0, 0, System.nanoTime() - start,
          e.getMessage() == null ? e.toString() : e.getMessage(), List.of());
    }
    final var failures = new ArrayList<String>();
    final var counts = evaluator.evaluate((row, report) -> {
      if (report == null || report.isEmpty() || failures.size() >= MAX_FAILURES_SHOWN) return;
      final var what = new StringBuilder();
      for (final var e : report) {
        if (what.length() > 0) what.append("; ");
        final var radix = vector.columnRadix[e.column()];
        what.append(e.oscillating()
            ? S.get("testDetailsOscillating", e.columnName())
            : S.get("testDetailsFailedOutput", e.columnName(),
                e.expected().toDisplayString(radix), e.computed().toDisplayString(radix)));
      }
      failures.add(S.get("batchTestFailure", Integer.toString(row + 1),
          Integer.toString(vector.lineNumbers[row]), what.toString()));
    });
    return new Result(circuit.getName(), vectorFile, vector.data.size(), counts[0], counts[1],
        System.nanoTime() - start, null, failures);
  }

  private static void print(PrintStream out, Result result) {
    if (result.error() != null) {
      out.println(S.get("batchTestError", result.circuit(), result.error()));
      return;
    }
    out.println(S.get("batchTestCircuit", result.circuit(), Integer.toString(result.rows()),
        Integer.toString(result.passed()), Integer.toString(result.failed()),
        TestFrame.formatDuration(result.nanos())));
    for (final var failure : result.failures()) out.println("    " + failure);
    if (result.failed() > result.failures().size()) {
      out.println("    " + S.get("batchTestMoreFailures", Integer.toString(result.failed() - result.failures().size())));
    }
  }

  static String junitXml(String projectName, List<Result> results, long nanos) {
    var failed = 0;
    var errors = 0;
    for (final var result : results) {
      if (result.error() != null) errors++;
      else if (result.failed() > 0) failed++;
    }
    final var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<testsuites>\n");
    xml.append("  <testsuite name=\"").append(escape(projectName)).append("\" tests=\"").append(results.size())
        .append("\" failures=\"").append(failed).append("\" errors=\"").append(errors)
        .append("\" time=\"").append(seconds(nanos)).append("\">\n");
    for (final var result : results) {
      xml.append("    <testcase classname=\"").append(escape(projectName)).append("\" name=\"")
          .append(escape(result.circuit())).append("\" time=\"").append(seconds(result.nanos())).append('"');
      if (result.error() != null) {
        xml.append(">\n      <error message=\"").append(escape(result.error())).append("\"/>\n    </testcase>\n");
      } else if (result.failed() > 0) {
        xml.append(">\n      <failure message=\"").append(escape(S.get("testResults",
            Integer.toString(result.passed()), Integer.toString(result.failed())))).append("\">");
        xml.append(escape(String.join("\n", result.failures()))).append("</failure>\n    </testcase>\n");
      } else {
        xml.append("/>\n");
      }
    }
    xml.append("  </testsuite>\n</testsuites>\n");
    return xml.toString();
  }

  private static String seconds(long nanos) {
    return String.format(Locale.ROOT, "%.3f", nanos / 1e9);
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
