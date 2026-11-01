/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.test;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Which test vector file belongs to which circuit of a project.
 *
 * <p>A circuit named {@code foo} uses {@code tests/foo.txt} next to the project file when that
 * file exists. Bindings chosen by hand are kept in {@code <project>.circ.tests} next to the
 * project file, as {@code circuit name = path} lines with paths relative to the project's folder.
 * An empty path marks a circuit as having no test. The project file itself is never changed, so it
 * still opens in any Logisim-evolution.
 */
public class TestBindings {

  public static final String FILE_SUFFIX = ".tests";
  public static final String TEST_DIRECTORY = "tests";
  public static final String VECTOR_SUFFIX = ".txt";

  /** Bindings chosen by hand: circuit name to file, or to null for "no test". */
  private final Map<String, File> manual = new TreeMap<>();
  private File projectFile;

  public TestBindings(File projectFile) {
    this.projectFile = projectFile;
  }

  /** Reads the bindings stored next to the project file, if any. */
  public static TestBindings load(File projectFile) throws IOException {
    final var bindings = new TestBindings(projectFile);
    final var store = bindings.getStoreFile();
    if (store != null && store.isFile()) {
      try (Reader in = Files.newBufferedReader(store.toPath(), StandardCharsets.UTF_8)) {
        final var props = new Properties();
        props.load(in);
        for (final var name : props.stringPropertyNames()) {
          final var path = props.getProperty(name).trim();
          bindings.manual.put(name, path.isEmpty() ? null : bindings.resolve(path));
        }
      }
    }
    return bindings;
  }

  public File getProjectFile() {
    return projectFile;
  }

  /** Where hand-made bindings are stored, or null for a project that was never saved. */
  public File getStoreFile() {
    return projectFile == null ? null : new File(projectFile.getPath() + FILE_SUFFIX);
  }

  /** The vector file of the circuit, or null when it has none. */
  public File getVectorFile(String circuitName) {
    if (manual.containsKey(circuitName)) return manual.get(circuitName);
    final var auto = getAutoVectorFile(circuitName);
    return auto != null && auto.isFile() ? auto : null;
  }

  /** Whether the circuit's file was chosen by hand rather than found in the tests folder. */
  public boolean isManual(String circuitName) {
    return manual.containsKey(circuitName);
  }

  /** {@code tests/<circuit>.txt} next to the project file, whether it exists or not. */
  public File getAutoVectorFile(String circuitName) {
    final var dir = getProjectDirectory();
    if (dir == null) return null;
    return new File(new File(dir, TEST_DIRECTORY), circuitName + VECTOR_SUFFIX);
  }

  /** Binds a file to the circuit, or marks it as having none when file is null. */
  public void setVectorFile(String circuitName, File file) {
    final var auto = getAutoVectorFile(circuitName);
    if (file != null && auto != null && sameFile(file, auto)) {
      manual.remove(circuitName);
    } else if (file == null && (auto == null || !auto.isFile())) {
      manual.remove(circuitName);
    } else {
      manual.put(circuitName, file == null ? null : file.getAbsoluteFile().toPath().normalize().toFile());
    }
  }

  /** Moves a binding along when a circuit is renamed. */
  public void rename(String oldName, String newName) {
    if (!manual.containsKey(oldName)) return;
    manual.put(newName, manual.remove(oldName));
  }

  /** Keeps the bindings when the project is saved under another name. */
  public void setProjectFile(File projectFile) {
    this.projectFile = projectFile;
  }

  /** Writes the hand-made bindings next to the project file; removes the file when there are none. */
  public void save() throws IOException {
    final var store = getStoreFile();
    if (store == null) return;
    if (manual.isEmpty()) {
      Files.deleteIfExists(store.toPath());
      return;
    }
    Files.writeString(store.toPath(), format(), StandardCharsets.UTF_8);
  }

  String format() {
    final var out = new StringBuilder();
    out.append("# Test vector files of ").append(projectFile.getName())
        .append(", written by Logisim-evolution.\n");
    out.append("# circuit name = file, relative to this folder; empty = no test.\n");
    for (final var entry : manual.entrySet()) {
      final var props = new Properties();
      props.setProperty(entry.getKey(), entry.getValue() == null ? "" : relativize(entry.getValue()));
      final var line = new StringWriter();
      try {
        props.store(line, null);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      // Properties.store writes a date comment first; keep only the entry.
      for (final var text : line.toString().split("\\R")) {
        if (!text.startsWith("#") && !text.isEmpty()) out.append(text).append('\n');
      }
    }
    return out.toString();
  }

  private File getProjectDirectory() {
    if (projectFile == null) return null;
    return projectFile.getAbsoluteFile().getParentFile();
  }

  private File resolve(String path) {
    final var file = new File(path);
    if (file.isAbsolute() || getProjectDirectory() == null) return file;
    return new File(getProjectDirectory(), path).toPath().normalize().toFile();
  }

  private String relativize(File file) {
    final var dir = getProjectDirectory();
    if (dir == null) return file.getPath();
    try {
      return dir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    } catch (IllegalArgumentException e) {
      return file.getPath(); // on another drive
    }
  }

  private static boolean sameFile(File a, File b) {
    return a.getAbsoluteFile().toPath().normalize().equals(b.getAbsoluteFile().toPath().normalize());
  }
}
