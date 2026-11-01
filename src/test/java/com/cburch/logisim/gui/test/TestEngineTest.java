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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.memory.Ram;
import com.cburch.logisim.std.wiring.Pin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestEngineTest {

  @TempDir Path dir;

  private final LogisimFile file = LogisimFile.createNew(new Loader(null), null);
  private final Project project = new Project(file);
  private final Circuit circuit = file.getMainCircuit();

  TestEngineTest() {
    circuit.setProject(project);
    final var in = Pin.FACTORY.createAttributeSet();
    in.setValue(StdAttr.LABEL, "a");
    final var out = Pin.FACTORY.createAttributeSet();
    out.setValue(Pin.ATTR_TYPE, Pin.OUTPUT);
    out.setValue(StdAttr.LABEL, "y");
    add(Pin.FACTORY.createComponent(Location.create(100, 100, true), in));
    add(Pin.FACTORY.createComponent(Location.create(200, 100, true), out));
    add(Wire.create(Location.create(100, 100, true), Location.create(200, 100, true)));
  }

  private void add(Component component) {
    final var mutation = new CircuitMutation(circuit);
    mutation.add(component);
    mutation.execute();
  }

  private TestVector vector(String text) throws Exception {
    final var path = dir.resolve("v" + System.nanoTime() + ".txt");
    Files.writeString(path, text);
    return new TestVector(path.toFile());
  }

  /** Runs a vector and returns each row's report as text, in the order the sink received them. */
  private List<String> run(TestEngine engine, TestVector vector, boolean[] parallel) throws Exception {
    final var rows = new ArrayList<String>();
    final var ended = new AtomicReference<String>();
    final var job = engine.submit(project, circuit, vector, new TestEngine.RowSink() {
      @Override
      public boolean rowDone(int row, ArrayList<TestVectorEvaluator.LineReport> report) {
        synchronized (rows) {
          rows.add(row + ":" + (report == null || report.isEmpty() ? "pass" : "fail"));
        }
        return true;
      }

      @Override
      public void finished(boolean allRowsRan, String error) {
        ended.set(allRowsRan + " " + error);
      }
    });
    job.await();
    if (parallel != null) parallel[0] = job.isParallel();
    assertEquals("true null", ended.get());
    return rows;
  }

  @Test
  void parallelRunGivesTheResultsOfOneThreadInFileOrder() throws Exception {
    final var text = new StringBuilder("<set> <seq> a y\n");
    for (var i = 0; i < 200; i++) {
      final var bit = (i * 7) % 3 == 0 ? 1 : 0;
      final var expected = i % 13 == 0 ? 1 - bit : bit; // some rows fail
      final var seq = i < 100 ? 0 : (i % 10) + 1; // combinational rows, then sequences of ten
      final var set = i < 100 ? 0 : i / 10;
      text.append(set).append(' ').append(seq).append(' ').append(bit).append(' ').append(expected).append('\n');
    }
    final var vector = vector(text.toString());
    final var parallel = new boolean[1];
    final var many = run(new TestEngine(6), vector, parallel);
    assertTrue(parallel[0]);
    final var one = run(new TestEngine(1), vector, null);
    assertEquals(one, many);
    assertEquals(200, many.size());
    for (var i = 0; i < many.size(); i++) assertTrue(many.get(i).startsWith(i + ":"));
  }

  @Test
  void rowsAreSplitOnlyWhereTheEvaluatorResets() throws Exception {
    final var vector = vector("<set> <seq> a y\n0 0 0 0\n0 0 1 1\n1 1 0 0\n1 2 1 1\n2 1 0 0\n2 2 0 0\n2 3 1 1\n");
    final var starts = new ArrayList<Integer>();
    for (var row = 0; row < vector.data.size(); row++) {
      if (TestEngine.startsUnit(vector, row)) starts.add(row);
    }
    assertEquals(List.of(0, 1, 2, 4), starts);
  }

  @Test
  void stoppedRunEndsWithoutAllRows() throws Exception {
    final var text = new StringBuilder("a y\n");
    for (var i = 0; i < 2000; i++) text.append("0 0\n");
    final var vector = vector(text.toString());
    final var ended = new AtomicReference<Boolean>();
    final var engine = new TestEngine(4);
    final var count = new int[1];
    final var job = engine.submit(project, circuit, vector, new TestEngine.RowSink() {
      @Override
      public boolean rowDone(int row, ArrayList<TestVectorEvaluator.LineReport> report) {
        return ++count[0] < 10; // asks to stop after ten rows
      }

      @Override
      public void finished(boolean allRowsRan, String error) {
        ended.set(allRowsRan);
        assertNull(error);
      }
    });
    job.await();
    assertFalse(ended.get());
    assertEquals(10, count[0]);
  }

  @Test
  void circuitWithNonVolatileRamRunsInOneThread() throws Exception {
    final var ram = new Ram();
    final var attrs = ram.createAttributeSet();
    add(ram.createComponent(Location.create(400, 400, true), attrs));
    final var type = attrs.getAttributes().stream().filter(a -> a.getName().equals("type")).findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    final var typed = (Attribute<Object>) type;
    attrs.setValue(typed, typed.parse("nonvolatile"));
    assertTrue(ram.keepsContentsOnReset(attrs));
    assertFalse(ParallelSafety.isParallelSafe(circuit));
    final var exclusive = new boolean[1];
    run(new TestEngine(4), vector("a y\n0 0\n1 1\n"), exclusive);
    assertFalse(exclusive[0]);

    attrs.setValue(typed, typed.parse("volatile"));
    assertFalse(ram.keepsContentsOnReset(attrs));
    assertTrue(ParallelSafety.isParallelSafe(circuit));
    final var parallel = new boolean[1];
    run(new TestEngine(4), vector("a y\n0 0\n1 1\n"), parallel);
    assertTrue(parallel[0]);
  }
}
