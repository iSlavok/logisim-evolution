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
import com.cburch.logisim.circuit.SplitterFactory;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.std.io.DotMatrix;
import com.cburch.logisim.std.io.HexDigit;
import com.cburch.logisim.std.io.Led;
import com.cburch.logisim.std.io.LedBar;
import com.cburch.logisim.std.io.RgbLed;
import com.cburch.logisim.std.io.SevenSegment;
import com.cburch.logisim.std.memory.Ram;
import com.cburch.logisim.std.memory.Random;
import com.cburch.logisim.std.wiring.PowerOnReset;
import java.util.Set;

/**
 * Decides whether a circuit's test may run in parallel: its rows split over several states of the
 * circuit, and alongside other tests.
 *
 * <p>Rows can be split at every point where the evaluator resets the circuit only when a reset
 * makes the state independent of the rows before it. A reset clears the data of every component
 * except non-volatile RAM, so a circuit qualifies when all its components, in every circuit it
 * uses, come from the libraries known to keep their state in that data and to share nothing between
 * states: wiring (and splitters), gates, plexers, arithmetic, memory and base, plus a few passive
 * displays. Random (seeded from the time) and Power-On Reset (driven by time) are left out, as is
 * anything else: external simulators, sockets, sound, keyboard input, SoC components.
 */
final class ParallelSafety {

  private static final Set<String> SAFE_PACKAGES = Set.of(
      "com.cburch.logisim.std.wiring",
      "com.cburch.logisim.std.gates",
      "com.cburch.logisim.std.plexers",
      "com.cburch.logisim.std.arith",
      "com.cburch.logisim.std.memory",
      "com.cburch.logisim.std.base");

  private static final Set<Class<?>> SAFE_CLASSES = Set.of(
      // A splitter only joins wires; its connections are part of the circuit's wiring, which is
      // built once on the GUI thread and only read by the states.
      SplitterFactory.class,
      Led.class, RgbLed.class, SevenSegment.class, HexDigit.class, DotMatrix.class, LedBar.class);

  private ParallelSafety() {}

  /** Whether the circuit's test may be split over several states and run alongside others. */
  static boolean isParallelSafe(Circuit circuit) {
    for (final var used : DesignDigest.circuitsUsedBy(circuit)) {
      for (final var comp : used.getNonWires()) {
        if (!isSafe(comp)) return false;
      }
    }
    return true;
  }

  private static boolean isSafe(Component comp) {
    final var factory = comp.getFactory();
    if (factory instanceof SubcircuitFactory) return true;
    if (factory instanceof Ram ram) return !ram.keepsContentsOnReset(comp.getAttributeSet());
    if (factory instanceof Random || factory instanceof PowerOnReset) return false;
    return SAFE_PACKAGES.contains(factory.getClass().getPackageName())
        || SAFE_CLASSES.contains(factory.getClass());
  }
}
