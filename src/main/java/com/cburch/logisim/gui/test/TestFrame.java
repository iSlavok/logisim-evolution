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

import com.cburch.logisim.data.TestException;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.gui.generic.LFrame;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectEvent;
import com.cburch.logisim.proj.ProjectListener;
import com.cburch.logisim.util.JFileChoosers;
import com.cburch.logisim.util.LocaleListener;
import com.cburch.logisim.util.LocaleManager;
import com.cburch.logisim.util.WindowMenuItemManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

public class TestFrame extends LFrame.SubWindowWithSimulation {

  private static final long serialVersionUID = 1L;
  static final Color WARNING_COLOR = new Color(0xd08000);
  private final MyListener myListener = new MyListener();
  private final WindowMenuManager windowManager;
  private final JFileChooser chooser = JFileChoosers.create();
  private final TestPanel panel;
  private final JButton load = new JButton();
  private final JButton run = new JButton();
  private final JButton stop = new JButton();
  private final JButton reset = new JButton();
  private final JButton nextFailure = new JButton();
  private final JButton explorer = new JButton();
  private final JButton close = new JButton();
  private final JCheckBox failuresOnly = new JCheckBox();
  private final JLabel pass = new JLabel();
  private final JLabel fail = new JLabel();
  private final JLabel fileLabel = new JLabel();
  private final JTextArea notice = new JTextArea();
  private Model curModel;

  public TestFrame(Project project) {
    super(project);
    this.windowManager = new WindowMenuManager();
    project.addProjectListener(myListener);
    updateWithProject(project);

    chooser.addChoosableFileFilter(chooser.getAcceptAllFileFilter());
    chooser.addChoosableFileFilter(TestVector.FILE_FILTER);
    chooser.setFileFilter(TestVector.FILE_FILTER);

    panel = new TestPanel(this);

    final var counts = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
    counts.add(pass);
    counts.add(fail);
    counts.add(fileLabel);
    notice.setEditable(false);
    notice.setFocusable(false);
    notice.setOpaque(false);
    notice.setLineWrap(true);
    notice.setWrapStyleWord(true);
    notice.setForeground(WARNING_COLOR);
    notice.setFont(pass.getFont().deriveFont(Font.BOLD));
    notice.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
    final var statusPanel = new JPanel(new BorderLayout());
    statusPanel.add(counts, BorderLayout.NORTH);
    statusPanel.add(notice, BorderLayout.CENTER);

    final var runButtons = new JPanel();
    runButtons.add(load);
    runButtons.add(run);
    runButtons.add(stop);
    runButtons.add(reset);
    final var viewButtons = new JPanel();
    viewButtons.add(failuresOnly);
    viewButtons.add(nextFailure);
    viewButtons.add(explorer);
    viewButtons.add(close);
    final var buttonPanel = new JPanel();
    buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
    buttonPanel.add(runButtons);
    buttonPanel.add(viewButtons);
    for (final var button : new JButton[] {load, run, stop, reset, nextFailure, explorer, close}) {
      button.addActionListener(myListener);
    }
    failuresOnly.addActionListener(myListener);

    Container contents = getContentPane();
    panel.setPreferredSize(new Dimension(560, 400));
    contents.add(statusPanel, BorderLayout.NORTH);
    contents.add(panel, BorderLayout.CENTER);
    contents.add(buttonPanel, BorderLayout.SOUTH);

    LocaleManager.addLocaleListener(myListener);
    myListener.localeChanged();
    pack();
  }

  private static String computeTitle(Model data, Project proj) {
    var name = data == null ? "???" : data.getCircuit().getName();
    return S.get("testFrameTitle", name, proj.getLogisimFile().getDisplayName());
  }

  Model getModel() {
    return curModel;
  }

  private void updateWithProject(Project theProject) {
    final var simulator = theProject.getSimulator();
    if (simulator == null) return;
    final var circuitState = simulator.getCircuitState();
    final var circuit = circuitState.getCircuit();
    if (curModel != null && circuit == curModel.getCircuit()) return;

    menubar.setCircuitState(simulator, circuitState);

    if (curModel != null) curModel.removeModelListener(myListener);

    Model oldModel = curModel;
    curModel = theProject.getTestSuite().getModel(circuit);
    curModel.addModelListener(myListener);
    setTitle(computeTitle(curModel, project));
    if (panel != null) {
      panel.modelChanged(oldModel, curModel);
      myListener.testingChanged();
    }
    repaint();
  }

  /** Lets the user choose a vector file for the current circuit, then runs it. */
  private void loadVector() {
    final var model = getModel();
    if (model.getVectorFile() != null) chooser.setSelectedFile(model.getVectorFile());
    int result = chooser.showOpenDialog(TestFrame.this);
    if (result != JFileChooser.APPROVE_OPTION) return;
    File file = chooser.getSelectedFile();
    if (!file.exists() || !file.canRead() || file.isDirectory()) {
      OptionPane.showMessageDialog(
          TestFrame.this,
          S.get("fileCannotReadMessage", file.getName()),
          S.get("fileCannotReadTitle"),
          OptionPane.OK_OPTION);
      return;
    }
    project.getTestSuite().setVectorFile(model.getCircuit(), file);
    if (model.getError() != null) {
      OptionPane.showMessageDialog(
          TestFrame.this,
          S.get("fileWrongPinsMessage", file.getName(), model.getError()),
          S.get("fileWrongPinsTitle"),
          OptionPane.OK_OPTION);
      return;
    }
    runModel();
  }

  private void runModel() {
    try {
      getModel().start();
    } catch (TestException e) {
      final var file = getModel().getVectorFile();
      OptionPane.showMessageDialog(
          TestFrame.this,
          S.get("fileWrongPinsMessage", file == null ? "?" : file.getName(), e.getMessage()),
          S.get("fileWrongPinsTitle"),
          OptionPane.OK_OPTION);
    }
    if (getModel().getError() != null) myListener.testingChanged();
  }

  /** Text for the run time of a test. */
  static String formatDuration(long nanos) {
    return String.format(Locale.ROOT, "%.1f s", nanos / 1e9);
  }

  /** Path of a vector file as shown to the user: relative to the project's folder when inside it. */
  static String displayPath(Project project, File file) {
    if (file == null) return "";
    final var path = file.getAbsoluteFile().toPath().normalize();
    final var projectFile = project.getLogisimFile().getLoader().getMainFile();
    if (projectFile != null && projectFile.getAbsoluteFile().getParentFile() != null) {
      final var dir = projectFile.getAbsoluteFile().getParentFile().toPath().normalize();
      try {
        final var relative = dir.relativize(path).toString();
        if (relative.length() < path.toString().length()) return relative;
      } catch (IllegalArgumentException e) {
        // on another drive
      }
    }
    return path.toString();
  }

  @Override
  public void setVisible(boolean value) {
    if (value) windowManager.frameOpened(this);
    super.setVisible(value);
  }

  private class MyListener
      implements ActionListener,
          ProjectListener,
          LocaleListener,
          ModelListener {

    @Override
    public void actionPerformed(ActionEvent event) {
      Object src = event.getSource();
      if (src == close) {
        requestClose();
      } else if (src == load) {
        loadVector();
      } else if (src == run) {
        runModel();
      } else if (src == stop) {
        getModel().stop();
      } else if (src == reset) {
        getModel().clearResults();
      } else if (src == failuresOnly) {
        panel.setFailuresOnly(failuresOnly.isSelected());
      } else if (src == nextFailure) {
        panel.selectNextFailure();
      } else if (src == explorer) {
        project.getTestExplorerFrame().setVisible(true);
        project.getTestExplorerFrame().toFront();
      }
    }

    @Override
    public void localeChanged() {
      setTitle(computeTitle(curModel, project));
      panel.localeChanged();
      load.setText(S.get("loadButton"));
      run.setText(S.get("runButton"));
      stop.setText(S.get("stopButton"));
      reset.setText(S.get("resetButton"));
      close.setText(S.get("closeButton"));
      failuresOnly.setText(S.get("testFailuresOnly"));
      nextFailure.setText(S.get("testNextFailure"));
      explorer.setText(S.get("testExplorerButton"));
      testingChanged();
      windowManager.localeChanged();
    }

    @Override
    public void projectChanged(ProjectEvent event) {
      int action = event.getAction();
      if (action == ProjectEvent.ACTION_SET_STATE) {
        updateWithProject(event.getProject());
      } else if (action == ProjectEvent.ACTION_SET_FILE) {
        setTitle(computeTitle(curModel, project));
      }
    }

    @Override
    public void testingChanged() {
      final var model = getModel();
      if (model == null) return;
      final var hasVector = model.getVector() != null;
      final var running = model.isRunning();
      final var done = model.getPass() + model.getFail();
      run.setEnabled(hasVector && !running || model.getError() != null);
      stop.setEnabled(running);
      reset.setEnabled(hasVector && !running && done > 0);
      nextFailure.setEnabled(model.getFail() > 0);
      testResultsChanged(model.getPass(), model.getFail());
      final var file = model.getVectorFile();
      var fileText = file == null ? "" : S.get("testFileLabel", displayPath(project, file));
      if (model.getRunNanos() > 0) fileText += " · " + formatDuration(model.getRunNanos());
      fileLabel.setText(fileText);
      final var text = switch (model.getStatus()) {
        case NO_VECTOR -> S.get("testNoVectorMessage", model.getCircuit().getName());
        case ERROR -> S.get("testErrorMessage", file == null ? "" : file.getName(), model.getError());
        case OUTDATED -> S.get("testOutdatedMessage");
        case STOPPED -> S.get("testStoppedMessage", Integer.toString(done), Integer.toString(model.getRowCount()));
        default -> null;
      };
      notice.setText(text == null ? "" : text);
      notice.setVisible(text != null);
    }

    @Override
    public void testResultsChanged(int numPass, int numFail) {
      pass.setText(S.get("passMessage", Integer.toString(numPass)));
      fail.setText(S.get("failMessage", Integer.toString(numFail)));
      nextFailure.setEnabled(numFail > 0);
    }

    @Override
    public void vectorChanged() {
      testingChanged();
    }
  }

  private class WindowMenuManager extends WindowMenuItemManager
      implements LocaleListener, ProjectListener {

    WindowMenuManager() {
      super(S.get("logFrameMenuItem"), false);
      project.addProjectListener(this);
    }

    @Override
    public JFrame getJFrame(boolean create, java.awt.Component parent) {
      return TestFrame.this;
    }

    @Override
    public void localeChanged() {
      String title = project.getLogisimFile().getDisplayName();
      setText(S.get("testFrameMenuItem", title));
    }

    @Override
    public void projectChanged(ProjectEvent event) {
      if (event.getAction() == ProjectEvent.ACTION_SET_FILE) {
        localeChanged();
      }
    }
  }
}
