/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.test;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.TestException;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.EventSourceWeakSupport;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** The test vector of one circuit and the results of its last run. Change it on the GUI thread. */
public class Model {

  public enum Status {
    /** No test vector file belongs to the circuit. */
    NO_VECTOR,
    /** The vector file could not be read or does not match the circuit. */
    ERROR,
    NOT_RUN,
    RUNNING,
    /** Stopped before every row ran. */
    STOPPED,
    PASSED,
    FAILED,
    /** The design or the vector file changed after the last run. */
    OUTDATED
  }

  private final EventSourceWeakSupport<ModelListener> listeners;
  private final Project project;
  private final Circuit circuit;
  private final Supplier<String> designDigest;
  private final UpdateResultSort myUpdateResultSort = new UpdateResultSort();
  /** Set while an update of the sorted rows waits on the GUI thread, so rows share one update. */
  private final AtomicBoolean sortPending = new AtomicBoolean();
  private final ArrayList<Integer> failed = new ArrayList<>();
  private final ArrayList<Integer> passed = new ArrayList<>();
  private final ArrayList<Integer> sortedIndices = new ArrayList<>();
  private final ArrayList<Integer> allIndices = new ArrayList<>();
  private final ArrayList<Integer> failedSortedIndices = new ArrayList<>();
  private boolean running;
  private TestEngine.Job job;
  /** Receives the rows of the current run; results of older runs are ignored. */
  private volatile Sink sink;
  private int numPass = 0;
  private int numFail = 0;
  private TestVector vec = null;
  private ArrayList<TestVectorEvaluator.LineReport>[] results;

  private File vectorFile;
  private long vectorStamp;
  private String error;
  /** Digest of the design when the last run started, null before the first run. */
  private String runDigest;
  private boolean outdated;
  private boolean completed;
  private long runStarted;
  private long runNanos;

  public Model(Project proj, Circuit circuit) {
    this(proj, circuit, () -> DesignDigest.of(proj.getLogisimFile(), circuit));
  }

  Model(Project proj, Circuit circuit, Supplier<String> designDigest) {
    listeners = new EventSourceWeakSupport<>();
    this.circuit = circuit;
    this.project = proj;
    this.designDigest = designDigest;
  }

  public void addModelListener(ModelListener l) {
    listeners.add(l);
  }

  public void removeModelListener(ModelListener l) {
    listeners.remove(l);
  }

  /** Forgets the results of the last run. */
  public void clearResults() {
    stop();
    synchronized (this) {
      completed = false;
      outdated = false;
      runDigest = null;
      runNanos = 0;
      if (vec == null || results == null) return;
      numPass = numFail = 0;
      failed.clear();
      passed.clear();
      Arrays.fill(results, null);
      updateSortedIndices();
    }
    fireTestResultsChanged();
    fireTestingChanged();
  }

  private void fireTestingChanged() {
    for (ModelListener listener : listeners) {
      listener.testingChanged();
    }
  }

  private void fireTestResultsChanged() {
    for (ModelListener listener : listeners) {
      listener.testResultsChanged(numPass, numFail);
    }
  }

  private void fireVectorChanged() {
    for (ModelListener listener : listeners) {
      listener.vectorChanged();
    }
  }

  public Circuit getCircuit() {
    return circuit;
  }

  public int getFail() {
    return numFail;
  }

  public int getPass() {
    return numPass;
  }

  public Project getProject() {
    return project;
  }

  public ArrayList<TestVectorEvaluator.LineReport>[] getResults() {
    return results;
  }

  public TestVector getVector() {
    return vec;
  }

  public File getVectorFile() {
    return vectorFile;
  }

  /** Why the vector file could not be used, or null. */
  public String getError() {
    return error;
  }

  /** Number of rows in the vector, 0 when there is none. */
  public int getRowCount() {
    final var v = vec;
    return v == null ? 0 : v.data.size();
  }

  /** Duration of the last run that went through every row, in nanoseconds. */
  public long getRunNanos() {
    return runNanos;
  }

  public boolean isOutdated() {
    return outdated;
  }

  public Status getStatus() {
    if (vectorFile == null && vec == null) return Status.NO_VECTOR;
    if (error != null) return Status.ERROR;
    if (running) return Status.RUNNING;
    if (runDigest == null) return Status.NOT_RUN;
    if (outdated) return Status.OUTDATED;
    if (!completed) return Status.STOPPED;
    return numFail == 0 ? Status.PASSED : Status.FAILED;
  }

  /**
   * Uses the given vector file, read again before every run when it changed on disk. Null removes
   * the vector.
   */
  public void setVectorFile(File file) {
    stop();
    vectorFile = file;
    vectorStamp = 0;
    error = null;
    if (file == null) {
      setVector(null);
    } else {
      reloadVectorIfChanged();
    }
  }

  /** Reads the vector file again when it changed since it was last read. Returns false on error. */
  public boolean reloadVectorIfChanged() {
    if (vectorFile == null) return vec != null;
    final var stamp = stampOf(vectorFile);
    if (vec != null && stamp == vectorStamp && error == null) return true;
    try {
      final var v = new TestVector(vectorFile);
      // Checks that the circuit has the vector's pins, without touching the project's states.
      new TestVectorEvaluator(CircuitState.createRootState(project, circuit), v);
      error = null;
      vectorStamp = stamp;
      setVector(v);
      return true;
    } catch (Exception e) {
      error = e.getMessage() == null ? e.toString() : e.getMessage();
      vectorStamp = stamp;
      setVector(null);
      return false;
    }
  }

  /** Whether the vector file changed on disk since it was read. */
  public boolean isVectorFileChanged() {
    return vectorFile != null && stampOf(vectorFile) != vectorStamp;
  }

  private static long stampOf(File file) {
    return file.lastModified() * 31 + file.length();
  }

  @SuppressWarnings("unchecked")
  public void setVector(TestVector v) {
    stop();
    synchronized (this) {
      vec = v;
      updateAllIndices();
      results = ((v != null) ? (new ArrayList[v.data.size()]) : null);
      numPass = numFail = 0;
      failed.clear();
      passed.clear();
      sortedIndices.clear();
      failedSortedIndices.clear();
      completed = false;
      outdated = false;
      runDigest = null;
      runNanos = 0;
      if (v != null) {
        updateSortedIndices();
      }
    }
    fireVectorChanged();
    fireTestResultsChanged();
    fireTestingChanged();
  }

  /**
   * Marks the results outdated when the vector file changed on disk or, given the design's current
   * digest, when the design changed since the run started.
   */
  public void checkOutdated(Supplier<String> currentDigest) {
    if (runDigest == null || outdated) return;
    if (!isVectorFileChanged()
        && (currentDigest == null || runDigest.equals(currentDigest.get()))) return;
    stop();
    outdated = true;
    fireTestingChanged();
  }

  public boolean isRunning() {
    return running;
  }

  public boolean setResult(TestVector v, int idx, ArrayList<TestVectorEvaluator.LineReport> report) {
    synchronized (this) {
      if (v != vec || idx < 0 || idx >= results.length || idx != numPass + numFail) return false;
      if (report == null || report.isEmpty()) {
        results[idx] = null;
        numPass++;
      } else {
        results[idx] = report;
        numFail++;
      }
    }
    if (!SwingUtilities.isEventDispatchThread()) {
      if (!sortPending.getAndSet(true)) SwingUtilities.invokeLater(myUpdateResultSort);
    } else {
      updateResultSort();
    }
    return true;
  }

  public void updateResult(TestVector v, int idx, ArrayList<TestVectorEvaluator.LineReport> err) {
    synchronized (this) {
      if (v != vec || idx < 0 || idx >= results.length) return;
      // Update the result, adjusting pass/fail counts if needed
      ArrayList<TestVectorEvaluator.LineReport> oldResult = results[idx];
      boolean wasCounted = (idx < numPass + numFail);

      results[idx] = err;

      if (wasCounted) {
        // The old result was already counted, so adjust counts
        if (oldResult == null && err != null) {
          // Changed from pass to fail
          numPass--;
          numFail++;
        } else if (oldResult != null && err == null) {
          // Changed from fail to pass
          numPass++;
          numFail--;
        }
        // If both are null or both are non-null, counts don't change
      } else {
        // The old result was not counted, so add the new one
        if (err == null) {
          numPass++;
        } else {
          numFail++;
        }
      }
    }
    // Always update sorted indices and fire event, even for updates
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> {
        updateSortedIndices();
        fireTestResultsChanged();
      });
    } else {
      updateSortedIndices();
      fireTestResultsChanged();
    }
  }

  public int sortedIndex(int i) {
    if (i < sortedIndices.size()) {
      return sortedIndices.get(i);
    }
    // Fallback: if sortedIndices not populated, use old behavior
    if (i < failed.size()) return failed.get(i);
    if (i < failed.size() + passed.size()) return passed.get(i - failed.size());
    return i;
  }

  /** Rows that failed, in the order they ran; rows are only added until the results are cleared. */
  public ArrayList<Integer> getFailedRows() {
    return failed;
  }

  /** Rows that failed, in display order. */
  public ArrayList<Integer> getFailedSortedIndices() {
    return failedSortedIndices;
  }

  /** Runs every row of the vector from the start, reading the vector file again if it changed. */
  public void start() throws TestException {
    stop();
    if (!reloadVectorIfChanged() || vec == null) {
      fireTestingChanged();
      return;
    }
    final var digest = designDigest.get();
    final var newSink = new Sink(vec);
    synchronized (this) {
      numPass = numFail = 0;
      failed.clear();
      passed.clear();
      Arrays.fill(results, null);
      updateSortedIndices();
      sink = newSink;
      running = true;
      completed = false;
      outdated = false;
      runDigest = digest;
      runNanos = 0;
      runStarted = System.nanoTime();
    }
    try {
      final var started = TestEngine.shared().submit(project, circuit, vec, newSink);
      synchronized (this) {
        if (sink == newSink) job = started;
        else started.cancel(); // stopped meanwhile
      }
    } catch (TestException e) {
      synchronized (this) {
        running = false;
        sink = null;
        runDigest = null;
      }
      error = e.getMessage();
      fireTestingChanged();
      throw e;
    }
    fireTestResultsChanged();
    fireTestingChanged();
  }

  /** Stops a run; the rows that ran keep their results. */
  public void stop() {
    synchronized (this) {
      if (!running) return;
      running = false;
      if (job != null) job.cancel();
      job = null;
      sink = null;
    }
    fireTestingChanged();
  }

  /** Called when a run ends. */
  private void runFinished(Sink finishedSink, boolean allRowsRan, String simulationError) {
    SwingUtilities.invokeLater(() -> {
      synchronized (this) {
        if (sink != finishedSink) return; // stopped or replaced meanwhile
        updateResultSort();
        running = false;
        // the time the rows ran, not counting the wait for a free thread
        final var nanos = job == null ? System.nanoTime() - runStarted : job.getNanos();
        job = null;
        sink = null;
        completed = allRowsRan;
        if (allRowsRan) runNanos = Math.max(1, nanos);
        if (simulationError != null) error = simulationError;
      }
      fireTestingChanged();
    });
  }

  /** Feeds the rows of one run into this model. */
  private final class Sink implements TestEngine.RowSink {
    private final TestVector vector;

    Sink(TestVector vector) {
      this.vector = vector;
    }

    @Override
    public boolean rowDone(int row, ArrayList<TestVectorEvaluator.LineReport> report) {
      return sink == this && setResult(vector, row, report);
    }

    @Override
    public void finished(boolean allRowsRan, String simulationError) {
      runFinished(this, allRowsRan, simulationError);
    }
  }

  private void updateResultSort() {
    if (vec == null) return;
    if (failed.size() + passed.size() == numPass + numFail) {
      fireTestResultsChanged();
      return;
    }
    for (int i = failed.size() + passed.size(); i < numPass + numFail; i++) {
      if (results[i] == null) passed.add(i);
      else failed.add(i);
    }
    updateSortedIndices();
    fireTestResultsChanged();
  }

  // Call when vec is modified
  private void updateAllIndices() {
    // Create list of all row indices
    allIndices.clear();
    if (vec == null) return;
    for (int i = 0; i < vec.data.size(); i++) {
      allIndices.add(i);
    }
  }

  private void updateSortedIndices() {
    if (vec == null) return;
    sortedIndices.clear();
    // Sort by set first, then by sequence, then by pass/fail status, then by original index
    allIndices.sort((a, b) -> {
      int setA = (vec.setNumbers != null && a < vec.setNumbers.length) ? vec.setNumbers[a] : 0;
      int setB = (vec.setNumbers != null && b < vec.setNumbers.length) ? vec.setNumbers[b] : 0;
      int seqA = (vec.seqNumbers != null && a < vec.seqNumbers.length) ? vec.seqNumbers[a] : 0;
      int seqB = (vec.seqNumbers != null && b < vec.seqNumbers.length) ? vec.seqNumbers[b] : 0;

      // First compare by set
      int setCompare = Integer.compare(setA, setB);
      if (setCompare != 0) return setCompare;

      // Then compare by sequence
      int seqCompare = Integer.compare(seqA, seqB);
      if (seqCompare != 0) return seqCompare;

      // Within same set/seq, show failed tests first (if results available)
      if (results != null && a < results.length && b < results.length) {
        boolean failedA = results[a] != null;
        boolean failedB = results[b] != null;
        if (failedA != failedB) {
          return failedA ? -1 : 1; // failed comes first
        }
      }

      // If set and seq are the same, maintain original order (by index)
      return Integer.compare(a, b);
    });

    sortedIndices.addAll(allIndices);
    failedSortedIndices.clear();
    if (results != null) {
      for (final var i : sortedIndices) {
        if (results[i] != null) failedSortedIndices.add(i);
      }
    }
  }

  private class UpdateResultSort implements Runnable {

    @Override
    public void run() {
      sortPending.set(false);
      updateResultSort();
    }
  }
}
