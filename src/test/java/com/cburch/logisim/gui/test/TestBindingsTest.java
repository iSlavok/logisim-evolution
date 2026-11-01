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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestBindingsTest {

  @TempDir Path dir;

  private File project() {
    return dir.resolve("cpu.circ").toFile();
  }

  private File write(String path) throws Exception {
    final var file = dir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "a b\n0 0\n");
    return file.toFile();
  }

  @Test
  void findsVectorInTestsFolder() throws Exception {
    final var vector = write("tests/alu.txt");
    final var bindings = TestBindings.load(project());
    assertEquals(vector.getAbsoluteFile(), bindings.getVectorFile("alu").getAbsoluteFile());
    assertFalse(bindings.isManual("alu"));
    assertNull(bindings.getVectorFile("other"));
  }

  @Test
  void keepsManualBindingsNextToTheProject() throws Exception {
    final var vector = write("vectors/my alu=1.txt");
    final var bindings = TestBindings.load(project());
    bindings.setVectorFile("alu main", vector);
    bindings.save();

    final var store = dir.resolve("cpu.circ.tests");
    assertTrue(Files.readString(store).contains("vectors/my"), Files.readString(store));
    final var loaded = TestBindings.load(project());
    assertEquals(vector.getCanonicalFile(), loaded.getVectorFile("alu main").getCanonicalFile());
    assertTrue(loaded.isManual("alu main"));
  }

  @Test
  void bindingTheAutomaticFileStoresNothing() throws Exception {
    final var vector = write("tests/alu.txt");
    final var bindings = TestBindings.load(project());
    bindings.setVectorFile("alu", vector);
    bindings.save();
    assertFalse(Files.exists(dir.resolve("cpu.circ.tests")));
  }

  @Test
  void noTestOverridesTheAutomaticFile() throws Exception {
    write("tests/alu.txt");
    final var bindings = TestBindings.load(project());
    bindings.setVectorFile("alu", null);
    bindings.save();
    assertNull(TestBindings.load(project()).getVectorFile("alu"));

    final var again = TestBindings.load(project());
    again.setVectorFile("alu", dir.resolve("tests/alu.txt").toFile());
    again.save();
    assertFalse(Files.exists(dir.resolve("cpu.circ.tests")));
    assertEquals("alu.txt", TestBindings.load(project()).getVectorFile("alu").getName());
  }

  @Test
  void bindingFollowsRenamedCircuitAndMovedProject() throws Exception {
    final var vector = write("vectors/alu.txt");
    final var bindings = TestBindings.load(project());
    bindings.setVectorFile("alu", vector);
    bindings.rename("alu", "alu2");
    assertNull(bindings.getVectorFile("alu"));

    final var moved = dir.resolve("sub/copy.circ").toFile();
    Files.createDirectories(moved.toPath().getParent());
    bindings.setProjectFile(moved);
    bindings.save();
    assertTrue(Files.readString(dir.resolve("sub/copy.circ.tests")).contains("../vectors/alu.txt"));
    assertEquals(vector.getCanonicalFile(), TestBindings.load(moved).getVectorFile("alu2").getCanonicalFile());
  }

  @Test
  void unsavedProjectKeepsBindingsInMemory() throws Exception {
    final var vector = write("v.txt");
    final var bindings = TestBindings.load(null);
    bindings.setVectorFile("alu", vector);
    bindings.save();
    assertEquals(vector.getAbsoluteFile(), bindings.getVectorFile("alu"));
    assertNull(bindings.getStoreFile());
  }
}
