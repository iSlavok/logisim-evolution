/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestSuiteTest {

  @TempDir Path dir;

  /** {@code inner} copies a to y; {@code outer} does too and holds an inner; {@code other} is apart. */
  private static final class Fixture {
    final LogisimFile file = LogisimFile.createNew(new Loader(null), null);
    final Project project = new Project(file);
    final Circuit inner = new Circuit("inner", file, project);
    final Circuit outer = new Circuit("outer", file, project);
    final Circuit other = new Circuit("other", file, project);

    Fixture() {
      file.getMainCircuit().setProject(project);
      for (final var circuit : List.of(inner, outer, other)) {
        file.addCircuit(circuit);
        copyPin(circuit);
      }
      add(outer, inner.getSubcircuitFactory().createComponent(
          Location.create(400, 300, true), inner.getSubcircuitFactory().createAttributeSet()));
      project.setCurrentCircuit(file.getMainCircuit());
    }

    private static void copyPin(Circuit circuit) {
      final var in = Pin.FACTORY.createAttributeSet();
      in.setValue(StdAttr.LABEL, "a");
      final var out = Pin.FACTORY.createAttributeSet();
      out.setValue(Pin.ATTR_TYPE, Pin.OUTPUT);
      out.setValue(StdAttr.LABEL, "y");
      add(circuit, Pin.FACTORY.createComponent(Location.create(100, 100, true), in));
      add(circuit, Pin.FACTORY.createComponent(Location.create(200, 100, true), out));
      add(circuit, Wire.create(Location.create(100, 100, true), Location.create(200, 100, true)));
    }
  }

  private static void add(Circuit circuit, Component component) {
    final var mutation = new CircuitMutation(circuit);
    mutation.add(component);
    mutation.execute();
  }

  private File vector(String name, String text) throws Exception {
    final var file = dir.resolve(name).toFile();
    Files.writeString(file.toPath(), text);
    return file;
  }

  private static void edt(Runnable r) throws Exception {
    SwingUtilities.invokeAndWait(r);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    for (var i = 0; i < 300; i++) {
      final var done = new boolean[1];
      edt(() -> done[0] = condition.getAsBoolean());
      if (done[0]) return;
      Thread.sleep(20);
    }
    throw new AssertionError("timed out");
  }

  private static Model.Status status(TestSuite suite, Circuit circuit) throws Exception {
    final var status = new Model.Status[1];
    edt(() -> status[0] = suite.getModel(circuit).getStatus());
    return status[0];
  }

  @Test
  void runsSeveralCircuitsAndTracksWhatAnEditMakesOutdated() throws Exception {
    final var fixture = new Fixture();
    final var pass = vector("pass.txt", "a y\n0 0\n1 1\n");
    final var suite = new TestSuite[1];
    edt(() -> {
      suite[0] = fixture.project.getTestSuite();
      for (final var circuit : List.of(fixture.inner, fixture.outer, fixture.other)) {
        suite[0].setVectorFile(circuit, pass);
      }
      suite[0].run(List.of(fixture.inner, fixture.outer, fixture.other));
    });
    await(() -> !suite[0].isQueueRunning());
    // Running outer creates the state of its inner instance; that is not an edit.
    edt(() -> suite[0].checkOutdated(true));
    for (final var circuit : List.of(fixture.inner, fixture.outer, fixture.other)) {
      assertEquals(Model.Status.PASSED, status(suite[0], circuit), circuit.getName());
    }
    edt(() -> assertEquals(2, suite[0].getModel(fixture.outer).getPass()));

    add(fixture.inner, Wire.create(Location.create(500, 500, true), Location.create(600, 500, true)));
    edt(() -> suite[0].checkOutdated(true));
    assertEquals(Model.Status.OUTDATED, status(suite[0], fixture.inner));
    assertEquals(Model.Status.OUTDATED, status(suite[0], fixture.outer));
    assertEquals(Model.Status.PASSED, status(suite[0], fixture.other));
  }

  @Test
  void reportsFailuresAndReadsChangedVectorFiles() throws Exception {
    final var fixture = new Fixture();
    final var vector = vector("v.txt", "a y\n0 1\n1 1\n");
    final var suite = new TestSuite[1];
    edt(() -> {
      suite[0] = fixture.project.getTestSuite();
      suite[0].setVectorFile(fixture.inner, vector);
      suite[0].run(List.of(fixture.inner));
    });
    await(() -> !suite[0].isQueueRunning());
    assertEquals(Model.Status.FAILED, status(suite[0], fixture.inner));
    edt(() -> {
      final var model = suite[0].getModel(fixture.inner);
      assertEquals(1, model.getFail());
      assertEquals(List.of(0), model.getFailedSortedIndices());
    });

    Files.writeString(vector.toPath(), "a y\n0 0\n1 1\n0 0\n");
    assertTrue(vector.setLastModified(vector.lastModified() + 2000));
    edt(() -> suite[0].checkOutdated(false));
    assertEquals(Model.Status.OUTDATED, status(suite[0], fixture.inner));
    edt(() -> suite[0].run(List.of(fixture.inner)));
    await(() -> !suite[0].isQueueRunning());
    assertEquals(Model.Status.PASSED, status(suite[0], fixture.inner));
    edt(() -> assertEquals(3, suite[0].getModel(fixture.inner).getPass()));
  }

  @Test
  void vectorThatDoesNotMatchTheCircuitIsAnError() throws Exception {
    final var fixture = new Fixture();
    final var vector = vector("bad.txt", "a missing\n0 0\n");
    final var suite = new TestSuite[1];
    edt(() -> {
      suite[0] = fixture.project.getTestSuite();
      suite[0].setVectorFile(fixture.inner, vector);
      suite[0].run(List.of(fixture.inner));
    });
    await(() -> !suite[0].isQueueRunning());
    assertEquals(Model.Status.ERROR, status(suite[0], fixture.inner));
    edt(() -> assertTrue(suite[0].getModel(fixture.inner).getError().contains("missing")));
  }

  @Test
  void digestIgnoresSimulationButNotEdits() throws Exception {
    final var fixture = new Fixture();
    final var before = DesignDigest.of(fixture.file, fixture.outer);
    final var state = CircuitState.createRootState(fixture.project, fixture.outer, Thread.currentThread());
    state.getPropagator().propagate();
    assertEquals(before, DesignDigest.of(fixture.file, fixture.outer));
    assertEquals(DesignDigest.of(fixture.file, fixture.other), DesignDigest.of(fixture.file, fixture.other));

    final var otherBefore = DesignDigest.of(fixture.file, fixture.other);
    add(fixture.inner, Wire.create(Location.create(500, 500, true), Location.create(600, 500, true)));
    assertNotEquals(before, DesignDigest.of(fixture.file, fixture.outer));
    assertEquals(otherBefore, DesignDigest.of(fixture.file, fixture.other));
  }

  @Test
  void batchRunnerReportsEachCircuit() throws Exception {
    final var fixture = new Fixture();
    final var tests = dir.resolve("vectors");
    Files.createDirectories(tests);
    Files.writeString(tests.resolve("inner.txt"), "a y\n0 0\n1 1\n");
    Files.writeString(tests.resolve("outer.txt"), "a y\n0 1\n");
    Files.writeString(tests.resolve("ghost.txt"), "a y\n0 0\n");
    final var junit = dir.resolve("junit.xml").toFile();
    final var out = new ByteArrayOutputStream();

    final var code = BatchTestRunner.run(fixture.project, tests.toFile(), junit,
        new PrintStream(out, true, StandardCharsets.UTF_8));

    assertEquals(BatchTestRunner.EXIT_ERROR, code);
    final var text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("inner: 2 rows, 2 passed, 0 failed"), text);
    assertTrue(text.contains("outer: 1 rows, 0 passed, 1 failed"), text);
    assertTrue(text.contains("y: expected 1, got 0"), text);
    assertTrue(text.contains("ghost: ERROR"), text);
    final var xml = Files.readString(junit.toPath());
    assertTrue(xml.contains("tests=\"3\" failures=\"1\" errors=\"1\""), xml);
    assertFalse(xml.contains("<failure message=\"\""), xml);
  }

  @Test
  void junitReportEscapesText() {
    final var xml = BatchTestRunner.junitXml("a<b", List.of(
        new BatchTestRunner.Result("c&d", null, 1, 0, 0, 0, "bad \"pin\"", List.of())), 0);
    assertTrue(xml.contains("name=\"a&lt;b\""), xml);
    assertTrue(xml.contains("name=\"c&amp;d\""), xml);
    assertTrue(xml.contains("message=\"bad &quot;pin&quot;\""), xml);
  }
}
