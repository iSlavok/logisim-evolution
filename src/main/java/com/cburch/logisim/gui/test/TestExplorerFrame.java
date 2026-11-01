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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Lists the tests of every circuit of a project, and runs them. */
public class TestExplorerFrame extends LFrame.SubWindow {

  private static final long serialVersionUID = 1L;
  static final Color PASS_COLOR = new Color(0x2e9e44);
  static final Color FAIL_COLOR = new Color(0xd03838);
  private static final int COL_CIRCUIT = 0;
  private static final int COL_STATUS = 1;
  private static final int COL_PASSED = 2;
  private static final int COL_FAILED = 3;
  private static final int COL_ROWS = 4;
  private static final int COL_TIME = 5;
  private static final int COL_FILE = 6;
  private static final String[] COLUMN_KEYS = {
    "testExplorerCircuitColumn", "testExplorerStatusColumn", "testExplorerPassedColumn",
    "testExplorerFailedColumn", "testExplorerRowsColumn", "testExplorerTimeColumn",
    "testExplorerFileColumn"
  };

  private final TestSuite suite;
  private final TableModel tableModel = new TableModel();
  private final JTable table = new JTable(tableModel);
  private final MyListener myListener = new MyListener();
  private final WindowMenuManager windowManager;
  private final JFileChooser chooser = JFileChoosers.create();
  private final JLabel summary = new JLabel();
  private final JLabel hint = new JLabel();
  private final JCheckBox showAll = new JCheckBox();
  private final JButton runAll = new JButton();
  private final JButton runSelected = new JButton();
  private final JButton runNotPassed = new JButton();
  private final JButton stop = new JButton();
  private final JButton choose = new JButton();
  private final JButton unbind = new JButton();
  private final JButton open = new JButton();
  private final JButton close = new JButton();
  /** Refreshes progress while tests run. */
  private final Timer refresh = new Timer(250, e -> refreshRows());
  private List<Circuit> rows = new ArrayList<>();

  public TestExplorerFrame(Project project) {
    super(project);
    this.suite = project.getTestSuite();
    this.windowManager = new WindowMenuManager();
    project.addProjectListener(myListener);
    suite.addListener(myListener);

    chooser.addChoosableFileFilter(chooser.getAcceptAllFileFilter());
    chooser.addChoosableFileFilter(TestVector.FILE_FILTER);
    chooser.setFileFilter(TestVector.FILE_FILTER);

    table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    table.setAutoCreateRowSorter(false);
    table.setFillsViewportHeight(true);
    table.getSelectionModel().addListSelectionListener(e -> updateButtons());
    table.setDefaultRenderer(Object.class, new Renderer());
    // The header renderer is shared and keeps whatever font it was last given; use the table's.
    final var headerRenderer = table.getTableHeader().getDefaultRenderer();
    table.getTableHeader().setDefaultRenderer((t, value, isSelected, hasFocus, row, column) -> {
      final var comp = headerRenderer.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
      comp.setFont(t.getFont());
      return comp;
    });
    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) openSelected();
      }
    });

    summary.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    hint.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));
    hint.setForeground(UIManager.getColor("Label.disabledForeground"));

    final var top = new JPanel(new BorderLayout());
    top.add(summary, BorderLayout.CENTER);
    top.add(showAll, BorderLayout.EAST);

    final var runButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    runButtons.add(runAll);
    runButtons.add(runNotPassed);
    runButtons.add(runSelected);
    runButtons.add(stop);
    final var fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    fileButtons.add(open);
    fileButtons.add(choose);
    fileButtons.add(unbind);
    fileButtons.add(close);
    final var bottom = new JPanel(new BorderLayout());
    bottom.add(runButtons, BorderLayout.NORTH);
    bottom.add(fileButtons, BorderLayout.CENTER);
    bottom.add(hint, BorderLayout.SOUTH);
    for (final var button : new JButton[] {runAll, runSelected, runNotPassed, stop, choose, unbind, open, close}) {
      button.addActionListener(myListener);
    }
    showAll.addActionListener(myListener);

    final var contents = getContentPane();
    contents.add(top, BorderLayout.NORTH);
    final var scroll = new JScrollPane(table);
    scroll.setPreferredSize(new Dimension(760, 380));
    contents.add(scroll, BorderLayout.CENTER);
    contents.add(bottom, BorderLayout.SOUTH);

    addWindowListener(new WindowAdapter() {
      @Override
      public void windowActivated(WindowEvent e) {
        // vector files may have been regenerated while the window was in the background
        suite.checkOutdated(false);
        refreshRows();
      }
    });

    LocaleManager.addLocaleListener(myListener);
    myListener.localeChanged();
    rebuildRows();
    pack();
  }

  /** Circuits listed: those with a test, or all of them when asked. */
  private List<Circuit> listedCircuits() {
    final var list = new ArrayList<Circuit>();
    for (final var circuit : suite.getCircuits()) {
      if (showAll.isSelected() || suite.getModel(circuit).getStatus() != Model.Status.NO_VECTOR) {
        list.add(circuit);
      }
    }
    return list;
  }

  private void rebuildRows() {
    final var selected = selectedCircuits();
    rows = listedCircuits();
    tableModel.fireTableDataChanged();
    for (var i = 0; i < rows.size(); i++) {
      if (selected.contains(rows.get(i))) table.addRowSelectionInterval(i, i);
    }
    refreshRows();
  }

  private void refreshRows() {
    if (!rows.isEmpty()) tableModel.fireTableRowsUpdated(0, rows.size() - 1);
    updateSummary();
    updateButtons();
    var anyRunning = suite.isQueueRunning();
    for (final var circuit : rows) anyRunning |= suite.getModel(circuit).isRunning();
    if (anyRunning && !refresh.isRunning()) refresh.start();
    if (!anyRunning && refresh.isRunning()) refresh.stop();
  }

  private void updateSummary() {
    var passed = 0;
    var failed = 0;
    var other = 0;
    var total = 0;
    for (final var circuit : suite.getCircuits()) {
      final var status = suite.getModel(circuit).getStatus();
      switch (status) {
        case NO_VECTOR -> {
          continue;
        }
        case PASSED -> passed++;
        case FAILED -> failed++;
        default -> other++;
      }
      total++;
    }
    var text = S.get("testExplorerSummary", Integer.toString(total), Integer.toString(passed),
        Integer.toString(failed), Integer.toString(other));
    if (suite.isQueueRunning()) {
      final var progress = suite.getQueueProgress();
      text += " · " + S.get("testExplorerProgress", Integer.toString(progress[0]), Integer.toString(progress[1]),
          Integer.toString(TestEngine.shared().getThreads()));
    }
    summary.setText(text);
    final var store = suite.getBindings().getStoreFile();
    hint.setText(S.get("testExplorerHint", TestBindings.TEST_DIRECTORY,
        store == null ? "" : store.getName()));
  }

  private void updateButtons() {
    final var selected = selectedCircuits();
    final var anyTests = rows.stream().anyMatch(c -> suite.getModel(c).getStatus() != Model.Status.NO_VECTOR);
    runAll.setEnabled(anyTests);
    runNotPassed.setEnabled(anyTests);
    runSelected.setEnabled(!selected.isEmpty());
    var anyRunning = suite.isQueueRunning();
    for (final var circuit : rows) anyRunning |= suite.getModel(circuit).isRunning();
    stop.setEnabled(anyRunning);
    choose.setEnabled(selected.size() == 1);
    unbind.setEnabled(selected.size() == 1
        && suite.getModel(selected.get(0)).getStatus() != Model.Status.NO_VECTOR);
    open.setEnabled(selected.size() == 1);
  }

  private List<Circuit> selectedCircuits() {
    final var list = new ArrayList<Circuit>();
    for (final var row : table.getSelectedRows()) {
      if (row < rows.size()) list.add(rows.get(row));
    }
    return list;
  }

  private void runNotPassed() {
    final var list = new ArrayList<Circuit>();
    for (final var circuit : rows) {
      final var model = suite.getModel(circuit);
      if (model.getStatus() != Model.Status.PASSED || model.isVectorFileChanged()) list.add(circuit);
    }
    suite.run(list);
  }

  private void chooseFile() {
    final var selected = selectedCircuits();
    if (selected.size() != 1) return;
    final var circuit = selected.get(0);
    final var model = suite.getModel(circuit);
    if (model.getVectorFile() != null) {
      chooser.setSelectedFile(model.getVectorFile());
    } else {
      final var auto = suite.getBindings().getAutoVectorFile(circuit.getName());
      if (auto != null) chooser.setCurrentDirectory(auto.getParentFile());
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    suite.setVectorFile(circuit, chooser.getSelectedFile());
    if (model.getError() != null) {
      OptionPane.showMessageDialog(this,
          S.get("fileWrongPinsMessage", chooser.getSelectedFile().getName(), model.getError()),
          S.get("fileWrongPinsTitle"), OptionPane.OK_OPTION);
    }
    rebuildRows();
  }

  private void unbindSelected() {
    final var selected = selectedCircuits();
    if (selected.size() != 1) return;
    suite.setVectorFile(selected.get(0), null);
    rebuildRows();
  }

  /** Shows the selected circuit in the main window and its results in the test vector window. */
  private void openSelected() {
    final var selected = selectedCircuits();
    if (selected.size() != 1) return;
    project.setCurrentCircuit(selected.get(0));
    final var frame = project.getTestFrame();
    frame.setVisible(true);
    frame.toFront();
  }

  private String statusText(Model model) {
    return switch (model.getStatus()) {
      case NO_VECTOR -> S.get("testStatusNoVector");
      case ERROR -> S.get("testStatusError", model.getError());
      case NOT_RUN -> suite.isQueued(model) ? S.get("testStatusQueued") : S.get("testStatusNotRun");
      case RUNNING -> {
        if (suite.isQueued(model)) yield S.get("testStatusQueued");
        final var rowsTotal = Math.max(1, model.getRowCount());
        final var done = model.getPass() + model.getFail();
        yield S.get("testStatusRunning", Integer.toString((int) (100L * done / rowsTotal)));
      }
      case STOPPED -> suite.isQueued(model) ? S.get("testStatusQueued") : S.get("testStatusStopped");
      case PASSED -> model.isVectorFileChanged() ? S.get("testStatusOutdated") : S.get("testStatusPassed");
      case FAILED -> model.isVectorFileChanged() ? S.get("testStatusOutdated") : S.get("testStatusFailed");
      case OUTDATED -> suite.isQueued(model) ? S.get("testStatusQueued") : S.get("testStatusOutdated");
    };
  }

  private Color statusColor(Model model) {
    return switch (model.getStatus()) {
      case PASSED -> PASS_COLOR;
      case FAILED, ERROR -> FAIL_COLOR;
      case OUTDATED, STOPPED -> TestFrame.WARNING_COLOR;
      case NO_VECTOR, NOT_RUN -> UIManager.getColor("Label.disabledForeground");
      case RUNNING -> null;
    };
  }

  @Override
  public void setVisible(boolean value) {
    if (value) {
      windowManager.frameOpened(this);
      rebuildRows();
    }
    super.setVisible(value);
  }

  private class TableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMN_KEYS.length;
    }

    @Override
    public String getColumnName(int column) {
      return S.get(COLUMN_KEYS[column]);
    }

    @Override
    public Object getValueAt(int row, int column) {
      if (row >= rows.size()) return "";
      final var circuit = rows.get(row);
      final var model = suite.getModel(circuit);
      final var hasResults = model.getPass() + model.getFail() > 0;
      return switch (column) {
        case COL_CIRCUIT -> circuit.getName();
        case COL_STATUS -> statusText(model);
        case COL_PASSED -> hasResults ? Integer.toString(model.getPass()) : "";
        case COL_FAILED -> hasResults ? Integer.toString(model.getFail()) : "";
        case COL_ROWS -> model.getRowCount() > 0 ? Integer.toString(model.getRowCount()) : "";
        case COL_TIME -> model.getRunNanos() > 0 ? TestFrame.formatDuration(model.getRunNanos()) : "";
        case COL_FILE -> TestFrame.displayPath(project, model.getVectorFile());
        default -> "";
      };
    }
  }

  private class Renderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;

    @Override
    public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
        boolean hasFocus, int row, int column) {
      super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
      setHorizontalAlignment(column >= COL_PASSED && column <= COL_TIME ? SwingConstants.RIGHT : SwingConstants.LEFT);
      setToolTipText(null);
      if (!isSelected) setForeground(t.getForeground());
      if (row < rows.size()) {
        final var model = suite.getModel(rows.get(row));
        if (column == COL_STATUS && !isSelected) {
          final var color = statusColor(model);
          if (color != null) setForeground(color);
        } else if (column == COL_FAILED && !isSelected && model.getFail() > 0) {
          setForeground(FAIL_COLOR);
        }
        if (column == COL_STATUS && model.getError() != null) setToolTipText(model.getError());
        if (column == COL_FILE && model.getVectorFile() != null) {
          setToolTipText(model.getVectorFile().getAbsolutePath());
        }
      }
      return this;
    }
  }

  private class MyListener implements ActionListener, LocaleListener, ProjectListener, TestSuite.Listener {
    @Override
    public void actionPerformed(ActionEvent event) {
      final var src = event.getSource();
      if (src == runAll) {
        suite.run(rows);
      } else if (src == runNotPassed) {
        runNotPassed();
      } else if (src == runSelected) {
        suite.run(selectedCircuits());
      } else if (src == stop) {
        suite.stopAll();
      } else if (src == choose) {
        chooseFile();
      } else if (src == unbind) {
        unbindSelected();
      } else if (src == open) {
        openSelected();
      } else if (src == showAll) {
        rebuildRows();
      } else if (src == close) {
        requestClose();
      }
      refreshRows();
    }

    @Override
    public void localeChanged() {
      setTitle(S.get("testExplorerTitle", project.getLogisimFile().getDisplayName()));
      showAll.setText(S.get("testExplorerShowAll"));
      runAll.setText(S.get("testRunAll"));
      runSelected.setText(S.get("testRunSelected"));
      runNotPassed.setText(S.get("testRunNotPassed"));
      stop.setText(S.get("stopButton"));
      choose.setText(S.get("testChooseFile"));
      unbind.setText(S.get("testUnbindFile"));
      open.setText(S.get("testOpen"));
      close.setText(S.get("closeButton"));
      tableModel.fireTableStructureChanged();
      final var widths = new int[] {140, 130, 70, 70, 70, 70, 220};
      for (var i = 0; i < widths.length && i < table.getColumnCount(); i++) {
        table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
      }
      windowManager.localeChanged();
      updateSummary();
    }

    @Override
    public void projectChanged(ProjectEvent event) {
      if (event.getAction() == ProjectEvent.ACTION_SET_FILE) {
        localeChanged();
        rebuildRows();
      }
    }

    @Override
    public void suiteChanged() {
      if (!isVisible()) return;
      if (listedCircuits().equals(rows)) refreshRows();
      else rebuildRows();
    }
  }

  private class WindowMenuManager extends WindowMenuItemManager implements LocaleListener {

    WindowMenuManager() {
      super(S.get("testExplorerMenuItem", project.getLogisimFile().getDisplayName()), false);
    }

    @Override
    public JFrame getJFrame(boolean create, java.awt.Component parent) {
      return TestExplorerFrame.this;
    }

    @Override
    public void localeChanged() {
      setText(S.get("testExplorerMenuItem", project.getLogisimFile().getDisplayName()));
    }
  }
}
