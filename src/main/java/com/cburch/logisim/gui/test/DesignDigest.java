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
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.vhdl.base.VhdlEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A digest of everything a circuit's simulation depends on: the circuit, every circuit and VHDL
 * entity it uses, directly or not, and the simulation options of the file. Two equal digests mean
 * the design did not change in a way that could change a test result.
 *
 * <p>Every attribute of every component counts, including ones that only change the drawing, so
 * the digest errs on the side of reporting a change. Must be called on the thread that edits the
 * circuits (the event dispatch thread in the GUI).
 */
public final class DesignDigest {

  private DesignDigest() {}

  public static String of(LogisimFile file, Circuit circuit) {
    return of(file, circuit, new HashMap<>());
  }

  /**
   * Same as {@link #of(LogisimFile, Circuit)}, reusing and filling a cache of per-circuit digests,
   * valid as long as no circuit changes.
   */
  public static String of(LogisimFile file, Circuit circuit, Map<Circuit, String> cache) {
    final var text = new StringBuilder();
    if (file != null) {
      text.append("options\n");
      appendAttributes(text, file.getOptions().getAttributeSet());
    }
    for (final var used : circuitsUsedBy(circuit)) {
      text.append(cache.computeIfAbsent(used, DesignDigest::digestOfCircuit)).append('\n');
    }
    return sha256(text.toString());
  }

  private static String digestOfCircuit(Circuit circuit) {
    final var text = new StringBuilder();
    text.append("circuit ").append(circuit.getName()).append('\n');
    appendAttributes(text, circuit.getStaticAttributes());
    final var lines = new ArrayList<String>();
    for (final var wire : circuit.getWires()) {
      lines.add("wire " + wire.getEnd0() + " " + wire.getEnd1() + " " + circuit.getWireBusWidthPos(wire));
    }
    for (final var comp : circuit.getNonWires()) {
      lines.add(describe(comp));
    }
    Collections.sort(lines);
    for (final var line : lines) text.append(line).append('\n');
    return sha256(text.toString());
  }

  /** The circuit itself followed by every circuit it uses, directly or not. */
  public static Set<Circuit> circuitsUsedBy(Circuit circuit) {
    final var found = new LinkedHashSet<Circuit>();
    final var todo = new ArrayDeque<Circuit>();
    todo.add(circuit);
    while (!todo.isEmpty()) {
      final var next = todo.poll();
      if (!found.add(next)) continue;
      for (final var comp : next.getNonWires()) {
        if (comp.getFactory() instanceof SubcircuitFactory sub) todo.add(sub.getSubcircuit());
      }
    }
    return found;
  }

  private static String describe(Component comp) {
    final var factory = comp.getFactory();
    final var line = new StringBuilder("comp ")
        .append(factory.getClass().getName()).append(' ')
        .append(factory.getName()).append(' ')
        .append(comp.getLocation());
    if (factory instanceof VhdlEntity vhdl) {
      line.append(" vhdl ").append(vhdl.getContent().getContent());
    }
    line.append('\n');
    appendAttributes(line, comp.getAttributeSet());
    return line.toString();
  }

  private static void appendAttributes(StringBuilder text, AttributeSet attrs) {
    if (attrs == null) return;
    final var lines = new ArrayList<String>();
    for (final var attr : attrs.getAttributes()) {
      @SuppressWarnings("unchecked")
      final var typed = (Attribute<Object>) attr;
      final var value = attrs.getValue(typed);
      lines.add("  " + attr.getName() + "=" + (value == null ? "null" : typed.toStandardString(value)));
    }
    Collections.sort(lines);
    for (final var line : lines) text.append(line).append('\n');
  }

  private static String sha256(String text) {
    try {
      final var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
