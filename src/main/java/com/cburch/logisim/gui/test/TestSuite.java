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
import com.cburch.logisim.circuit.CircuitEvent;
import com.cburch.logisim.circuit.CircuitListener;
import com.cburch.logisim.data.TestException;
import com.cburch.logisim.file.LibraryEvent;
import com.cburch.logisim.file.LibraryListener;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectEvent;
import com.cburch.logisim.proj.ProjectListener;
import com.cburch.logisim.proj.Projects;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tests of every circuit of a project: which vector file belongs to which circuit, the result
 * of each circuit's last run, and a queue that runs several circuits one after the other. Use it on
 * the event dispatch thread.
 */
public class TestSuite {

  /** Told when circuits, bindings, or the state of a run change. */
  public interface Listener {
    void suiteChanged();
  }

  private static final Logger logger = LoggerFactory.getLogger(TestSuite.class);
  /** How long to wait after an edit before checking which results it made outdated. */
  private static final int DESIGN_CHECK_DELAY_MS = 400;
  /** How often to look for vector files changed on disk. */
  private static final int FILE_CHECK_PERIOD_MS = 2000;

  private final Project project;
  private final Map<Circuit, Model> models = new HashMap<>();
  private final Map<Circuit, String> circuitNames = new HashMap<>();
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();
  private final ArrayDeque<Model> queue = new ArrayDeque<>();
  private final MyListener myListener = new MyListener();
  private final Timer designCheck;
  private final Timer fileCheck;
  private TestBindings bindings;
  private LogisimFile file;
  private Model queueCurrent;
  private int queueTotal;
  private int queueDone;
  /** Set while the queue starts a model, whose events then do not mean the run ended. */
  private boolean starting;

  public TestSuite(Project project) {
    this.project = project;
    designCheck = new Timer(DESIGN_CHECK_DELAY_MS, e -> checkOutdated(true));
    designCheck.setRepeats(false);
    fileCheck = new Timer(FILE_CHECK_PERIOD_MS, e -> {
      if (project.getFrame() != null && !Projects.getOpenProjects().contains(project)) {
        ((Timer) e.getSource()).stop(); // the project was closed; let it be collected
        designCheck.stop();
        return;
      }
      checkOutdated(false);
    });
    fileCheck.start();
    project.addProjectListener(myListener);
    setFile(project.getLogisimFile());
  }

  public void addListener(Listener l) {
    listeners.add(l);
  }

  public void removeListener(Listener l) {
    listeners.remove(l);
  }

  private void fireChanged() {
    for (final var l : listeners) l.suiteChanged();
  }

  public Project getProject() {
    return project;
  }

  public TestBindings getBindings() {
    return bindings;
  }

  /** The circuits of the project, in the order of the file. */
  public List<Circuit> getCircuits() {
    return file == null ? List.of() : file.getCircuits();
  }

  /** The test model of a circuit, created with the circuit's bound vector file on first use. */
  public Model getModel(Circuit circuit) {
    var model = models.get(circuit);
    if (model == null) {
      model = new Model(project, circuit);
      model.addModelListener(myListener);
      models.put(circuit, model);
      model.setVectorFile(bindings.getVectorFile(circuit.getName()));
    }
    return model;
  }

  /** Binds a vector file to the circuit and remembers it next to the project file. */
  public void setVectorFile(Circuit circuit, File vectorFile) {
    bindings.setVectorFile(circuit.getName(), vectorFile);
    saveBindings();
    getModel(circuit).setVectorFile(bindings.getVectorFile(circuit.getName()));
    fireChanged();
  }

  private void saveBindings() {
    try {
      bindings.save();
    } catch (IOException e) {
      logger.error("Could not save test bindings to {}: {}", bindings.getStoreFile(), e.getMessage());
    }
  }

  /** Runs the given circuits' tests one after the other, after any that are already queued. */
  public void run(Collection<Circuit> circuits) {
    if (queueCurrent == null && queue.isEmpty()) {
      queueTotal = 0;
      queueDone = 0;
    }
    for (final var circuit : circuits) {
      final var model = getModel(circuit);
      if (model.getStatus() == Model.Status.NO_VECTOR || queue.contains(model) || model == queueCurrent) continue;
      queue.add(model);
      queueTotal++;
    }
    if (queueCurrent == null) startNext();
    fireChanged();
  }

  /** Stops the queue and every running test. */
  public void stopAll() {
    queue.clear();
    final var current = queueCurrent;
    queueCurrent = null;
    for (final var model : models.values()) model.stop();
    if (current != null) fireChanged();
  }

  public boolean isQueueRunning() {
    return queueCurrent != null;
  }

  public boolean isQueued(Model model) {
    return queue.contains(model);
  }

  /** Circuits finished in the current queue run, and how many it has in all. */
  public int[] getQueueProgress() {
    return new int[] {queueDone, queueTotal};
  }

  private void startNext() {
    while (!queue.isEmpty()) {
      final var model = queue.poll();
      queueCurrent = model;
      starting = true;
      try {
        model.start();
      } catch (TestException e) {
        logger.warn("Could not start the test of {}: {}", model.getCircuit().getName(), e.getMessage());
      } finally {
        starting = false;
      }
      if (model.isRunning()) return;
      queueDone++;
    }
    queueCurrent = null;
  }

  private void testingChanged() {
    if (!starting && queueCurrent != null && !queueCurrent.isRunning()) {
      queueDone++;
      startNext();
    }
    fireChanged();
  }

  /** Marks the results that the last edits or changed vector files made outdated. */
  void checkOutdated(boolean designMayHaveChanged) {
    final var digests = new HashMap<Circuit, String>();
    for (final var model : List.copyOf(models.values())) {
      if (designMayHaveChanged) {
        model.checkOutdated(() -> DesignDigest.of(file, model.getCircuit(), digests));
      } else {
        model.checkOutdated(null);
      }
    }
  }

  private void setFile(LogisimFile newFile) {
    if (file == newFile) return;
    stopAll();
    if (file != null) {
      file.removeLibraryListener(myListener);
      for (final var circuit : circuitNames.keySet()) circuit.removeCircuitListener(myListener);
    }
    models.clear();
    circuitNames.clear();
    file = newFile;
    final var projectFile = file == null ? null : file.getLoader().getMainFile();
    try {
      bindings = TestBindings.load(projectFile);
    } catch (IOException e) {
      logger.error("Could not read test bindings of {}: {}", projectFile, e.getMessage());
      bindings = new TestBindings(projectFile);
    }
    if (file != null) {
      file.addLibraryListener(myListener);
      for (final var circuit : file.getCircuits()) watch(circuit);
    }
    fireChanged();
  }

  private void watch(Circuit circuit) {
    if (circuitNames.containsKey(circuit)) return;
    circuitNames.put(circuit, circuit.getName());
    circuit.addCircuitListener(myListener);
  }

  /** The project file may have been saved under a new name: keep the bindings with it. */
  private void projectFileMayHaveMoved() {
    final var projectFile = file == null ? null : file.getLoader().getMainFile();
    if (Objects.equals(projectFile, bindings.getProjectFile())) return;
    bindings.setProjectFile(projectFile);
    saveBindings();
    refreshBindings();
  }

  /** Applies the bindings again, for circuits whose file depends on the project's location or name. */
  private void refreshBindings() {
    for (final var model : models.values()) {
      final var bound = bindings.getVectorFile(model.getCircuit().getName());
      if (!Objects.equals(bound, model.getVectorFile())) model.setVectorFile(bound);
    }
    fireChanged();
  }

  private void circuitRenamed(Circuit circuit) {
    final var oldName = circuitNames.get(circuit);
    final var newName = circuit.getName();
    circuitNames.put(circuit, newName);
    if (oldName == null || oldName.equals(newName)) return;
    if (bindings.isManual(oldName)) {
      bindings.rename(oldName, newName);
      saveBindings();
    }
    refreshBindings();
  }

  private void circuitsChanged() {
    final Set<Circuit> present = file == null ? Set.of() : new HashSet<>(file.getCircuits());
    for (final var circuit : new ArrayList<>(circuitNames.keySet())) {
      if (present.contains(circuit)) continue;
      circuit.removeCircuitListener(myListener);
      circuitNames.remove(circuit);
      final var model = models.remove(circuit);
      if (model != null) {
        queue.remove(model);
        model.stop();
      }
    }
    for (final var circuit : present) watch(circuit);
    fireChanged();
  }

  private class MyListener implements ModelListener, CircuitListener, LibraryListener, ProjectListener {
    @Override
    public void testingChanged() {
      TestSuite.this.testingChanged();
    }

    @Override
    public void testResultsChanged(int numPass, int numFail) {
      // the explorer refreshes on testingChanged and on its own timer while tests run
    }

    @Override
    public void vectorChanged() {
      fireChanged();
    }

    @Override
    public void circuitChanged(CircuitEvent event) {
      if (event.getAction() == CircuitEvent.ACTION_DISPLAY_CHANGE) return;
      if (event.getAction() == CircuitEvent.ACTION_SET_NAME) {
        circuitRenamed(event.getCircuit());
      }
      designCheck.restart();
    }

    @Override
    public void libraryChanged(LibraryEvent event) {
      switch (event.getAction()) {
        case LibraryEvent.ADD_TOOL, LibraryEvent.REMOVE_TOOL, LibraryEvent.MOVE_TOOL -> circuitsChanged();
        case LibraryEvent.SET_NAME, LibraryEvent.DIRTY_STATE -> projectFileMayHaveMoved();
        default -> {
          // nothing to update
        }
      }
      designCheck.restart();
    }

    @Override
    public void projectChanged(ProjectEvent event) {
      if (event.getAction() == ProjectEvent.ACTION_SET_FILE) setFile(project.getLogisimFile());
    }
  }
}
