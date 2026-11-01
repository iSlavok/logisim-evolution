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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs test vectors on a pool of threads.
 *
 * <p>The evaluator resets the circuit before every combinational row and before the first row of
 * every sequence, so the rows between two resets form a unit that depends on nothing before it. A
 * vector is cut into chunks of whole units, and each thread runs its chunks in its own state of the
 * circuit, made and used only by that thread. Results reach the {@link RowSink} in the order of the
 * file. This gives the same results as one thread only when a reset really forgets everything, so
 * circuits that {@link ParallelSafety} does not vouch for run as one chunk, with no other test
 * running at the same time.
 */
public final class TestEngine {

  /** Receives the results of a run. Called from the engine's threads. */
  public interface RowSink {
    /** The result of one row, in the order of the file; return false to stop the run. */
    boolean rowDone(int row, ArrayList<TestVectorEvaluator.LineReport> report);

    /** The run ended: every row ran, it was stopped, or the simulation failed with an error. */
    void finished(boolean allRowsRan, String error);
  }

  private static final Logger logger = LoggerFactory.getLogger(TestEngine.class);
  /** Chunks per thread for one vector, so that threads finishing early find more work. */
  private static final int CHUNKS_PER_THREAD = 8;
  private static TestEngine shared;

  private final Object lock = new Object();
  private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
  private final int threads;
  private int running;
  private boolean exclusiveRunning;

  /** One run of a vector. */
  public static final class Job {
    private final Project project;
    private final Circuit circuit;
    private final TestVector vector;
    private final RowSink sink;
    private final boolean exclusive;
    private final ArrayList<TestVectorEvaluator.LineReport>[] reports;
    private final boolean[] done;
    private int next;
    private int chunksLeft;
    private volatile boolean finished;
    private boolean ending;
    private String error;
    private volatile boolean canceled;
    private volatile long startedNanos;
    private long endedNanos;
    private TestEngine engine;

    @SuppressWarnings("unchecked")
    private Job(Project project, Circuit circuit, TestVector vector, RowSink sink, boolean exclusive) {
      this.project = project;
      this.circuit = circuit;
      this.vector = vector;
      this.sink = sink;
      this.exclusive = exclusive;
      reports = new ArrayList[vector.data.size()];
      done = new boolean[vector.data.size()];
    }

    /** Stops the run; rows already reported keep their results. */
    public void cancel() {
      canceled = true;
      final var e = engine;
      if (e != null) {
        synchronized (e.lock) {
          e.lock.notifyAll();
        }
      }
    }

    /** Time from the start of the first chunk to the end of the run, in nanoseconds. */
    public synchronized long getNanos() {
      return startedNanos == 0 ? 0 : (ending ? endedNanos : System.nanoTime()) - startedNanos;
    }

    /** Whether rows of this vector run in several threads. */
    public boolean isParallel() {
      return !exclusive;
    }

    /** Waits until the run ended. */
    public synchronized void await() throws InterruptedException {
      while (!finished) wait();
    }

    private void rowDone(int row, ArrayList<TestVectorEvaluator.LineReport> report) {
      synchronized (this) {
        reports[row] = report;
        done[row] = true;
        while (next < done.length && done[next] && !canceled) {
          final var ok = sink.rowDone(next, reports[next]);
          reports[next] = null;
          next++;
          if (!ok) canceled = true;
        }
      }
    }

    private void chunkEnded(String chunkError) {
      final boolean allRowsRan;
      final String err;
      synchronized (this) {
        if (chunkError != null && error == null) {
          error = chunkError;
          canceled = true;
        }
        chunksLeft--;
        if (chunksLeft > 0 || ending) return;
        ending = true;
        endedNanos = System.nanoTime();
        allRowsRan = !canceled && next == done.length;
        err = error;
      }
      try {
        sink.finished(allRowsRan, err);
      } finally {
        // only now, so that await() returns after the sink heard of the end
        synchronized (this) {
          finished = true;
          notifyAll();
        }
      }
    }
  }

  private record Chunk(Job job, ArrayList<Integer> rows) {}

  /** A chunk taken from the queue; counted when it was counted as running. */
  private record Taken(Chunk chunk, boolean counted) {}

  public TestEngine(int threads) {
    this.threads = Math.max(1, threads);
    for (var i = 0; i < this.threads; i++) {
      final var worker = new Thread(this::work, "TestEngine-" + i);
      worker.setDaemon(true);
      worker.setPriority(Thread.NORM_PRIORITY - 1);
      worker.start();
    }
  }

  /** The engine of the GUI, with a thread per processor core but one, kept for the GUI. */
  public static synchronized TestEngine shared() {
    if (shared == null) shared = new TestEngine(Runtime.getRuntime().availableProcessors() - 1);
    return shared;
  }

  public int getThreads() {
    return threads;
  }

  /**
   * Starts running a vector. Call it on the thread that edits circuits (the GUI thread), since it
   * reads the circuit to check the vector's pins and to decide how to split the rows.
   */
  public Job submit(Project project, Circuit circuit, TestVector vector, RowSink sink) throws TestException {
    // Fails here, on the caller's thread, when the vector does not match the circuit.
    new TestVectorEvaluator(CircuitState.createRootState(project, circuit), vector);
    final var parallel = threads > 1 && ParallelSafety.isParallelSafe(circuit);
    final var job = new Job(project, circuit, vector, sink, !parallel);
    job.engine = this;
    final var chunks = parallel ? split(job) : List.of(new Chunk(job, allRows(vector)));
    job.chunksLeft = chunks.size();
    if (chunks.isEmpty()) {
      job.chunksLeft = 1;
      job.chunkEnded(null);
      return job;
    }
    synchronized (lock) {
      queue.addAll(chunks);
      lock.notifyAll();
    }
    return job;
  }

  private static ArrayList<Integer> allRows(TestVector vector) {
    final var rows = new ArrayList<Integer>(vector.data.size());
    for (var i = 0; i < vector.data.size(); i++) rows.add(i);
    return rows;
  }

  /** Cuts the rows into chunks that each begin where the evaluator resets the circuit. */
  private List<Chunk> split(Job job) {
    final var vector = job.vector;
    final var n = vector.data.size();
    final var target = Math.max(1, n / (threads * CHUNKS_PER_THREAD));
    final var chunks = new ArrayList<Chunk>();
    var rows = new ArrayList<Integer>();
    for (var row = 0; row < n; row++) {
      if (startsUnit(vector, row) && rows.size() >= target) {
        chunks.add(new Chunk(job, rows));
        rows = new ArrayList<>();
      }
      rows.add(row);
    }
    if (!rows.isEmpty()) chunks.add(new Chunk(job, rows));
    return chunks;
  }

  /** Whether the evaluator resets the circuit before this row when it runs the rows in order. */
  static boolean startsUnit(TestVector vector, int row) {
    if (row == 0) return true;
    final var seq = vector.seqNumbers[row];
    final var prevSeq = vector.seqNumbers[row - 1];
    return seq == 0 || prevSeq == 0 || vector.setNumbers[row] != vector.setNumbers[row - 1];
  }

  private Taken take() throws InterruptedException {
    synchronized (lock) {
      while (true) {
        final var head = queue.peek();
        if (head != null && head.job.canceled) {
          return new Taken(queue.poll(), false); // ends without running
        }
        if (head != null && !exclusiveRunning && (!head.job.exclusive || running == 0)) {
          queue.poll();
          running++;
          if (head.job.startedNanos == 0) head.job.startedNanos = System.nanoTime();
          if (head.job.exclusive) exclusiveRunning = true;
          return new Taken(head, true);
        }
        lock.wait();
      }
    }
  }

  private static void runChunk(Chunk chunk, Map<Job, TestVectorEvaluator> evaluators) throws TestException {
    final var job = chunk.job;
    evaluators.keySet().removeIf(j -> j.finished);
    var evaluator = evaluators.get(job);
    if (evaluator == null) {
      final var state = CircuitState.createRootState(job.project, job.circuit, Thread.currentThread());
      // Builds the states of the subcircuits, as the first row of a run in one thread does.
      state.getPropagator().propagate();
      evaluator = new TestVectorEvaluator(state, job.vector);
      evaluators.put(job, evaluator);
    }
    final var ev = evaluator;
    ev.evaluate(chunk.rows, (row, report) -> {
      if (job.canceled) ev.setCanceled(true);
      else job.rowDone(row, report);
    });
  }

  private void work() {
    // States of the circuits this thread is testing, made by and used only by this thread.
    final Map<Job, TestVectorEvaluator> evaluators = new HashMap<>();
    while (true) {
      final Taken taken;
      try {
        taken = take();
      } catch (InterruptedException e) {
        return;
      }
      final var chunk = taken.chunk();
      final var job = chunk.job;
      if (!taken.counted()) {
        job.chunkEnded(null);
        continue;
      }
      String error = null;
      try {
        if (!job.canceled) runChunk(chunk, evaluators);
      } catch (Throwable t) {
        logger.error("Test of {} failed", job.circuit.getName(), t);
        error = t.getMessage() == null ? t.toString() : t.getMessage();
      } finally {
        synchronized (lock) {
          running--;
          if (job.exclusive) exclusiveRunning = false;
          lock.notifyAll();
        }
      }
      job.chunkEnded(error);
    }
  }
}
