package dev.devfest.event.domain.sentinel;

import dev.devfest.event.domain.sentinel.SentinelContent.CrossoverFigure;
import dev.devfest.event.domain.sentinel.SentinelContent.Counts;
import dev.devfest.event.domain.sentinel.SentinelContent.Figure;
import dev.devfest.event.domain.sentinel.SentinelContent.MinimaxFigure;
import dev.devfest.event.domain.sentinel.SentinelContent.NeuronFigure;
import dev.devfest.event.domain.sentinel.SentinelContent.NeuronInput;
import dev.devfest.event.domain.sentinel.SentinelContent.SplitFigure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a figure of a CHOICE or INPUT stage must satisfy (rules §19.2). A figure draws the question's own data, so it
 * has to agree with the brief: every number of it is found in the brief, in the form the brief writes it. Its shape is
 * checked too (equal weights and inputs, a cut inside the bits, children that add up to their parent). The figures
 * carry no answer field at all, so none can leak. Pure; every problem is listed.
 */
public final class FigureRules {

  private static final Pattern BITS = Pattern.compile("[01]{2,16}");

  private FigureRules() {}

  /** The problems of {@code figure} against the stage's {@code brief}; none for a stage without a figure. */
  public static List<String> problems(Figure figure, String brief) {
    List<String> problems = new ArrayList<>();
    if (figure == null) return problems;
    switch (figure) {
      case NeuronFigure f -> neuron(f, brief, problems);
      case CrossoverFigure f -> crossover(f, brief, problems);
      case SplitFigure f -> split(f, brief, problems);
      case MinimaxFigure f -> minimax(f, brief, problems);
    }
    return problems.stream().map(problem -> "figure: " + problem).toList();
  }

  // ---- neuron --------------------------------------------------------------------------------------

  private static void neuron(NeuronFigure f, String brief, List<String> problems) {
    if (f.inputs().size() != f.weights().size()) {
      problems.add("it needs one weight for every input (" + f.inputs().size() + " inputs, " + f.weights().size() + " weights).");
      return;
    }
    Set<String> labels = new HashSet<>();
    for (int i = 0; i < f.inputs().size(); i++) {
      NeuronInput input = f.inputs().get(i);
      if (!labels.add(input.label())) problems.add("two inputs share the label \"" + input.label() + "\".");
      if (!assigned(brief, input.label(), input.value())) problems.add("the brief does not say \"" + input.label() + " = " + number(input.value()) + "\".");
      String weight = "w" + (i + 1);
      if (!assigned(brief, weight, f.weights().get(i))) problems.add("the brief does not say \"" + weight + " = " + number(f.weights().get(i)) + "\".");
    }
    if (!assigned(brief, "b", f.bias())) problems.add("the brief does not say \"b = " + number(f.bias()) + "\".");
  }

  // ---- crossover -----------------------------------------------------------------------------------

  private static void crossover(CrossoverFigure f, String brief, List<String> problems) {
    boolean shapeOk = true;
    if (!BITS.matcher(f.parentA()).matches() || !BITS.matcher(f.parentB()).matches()) {
      problems.add("the parents must be 2 to 16 bits (0 and 1 only).");
      shapeOk = false;
    } else if (f.parentA().length() != f.parentB().length()) {
      problems.add("the parents must have the same number of bits.");
      shapeOk = false;
    }
    if (shapeOk) {
      int length = f.parentA().length();
      if (f.cut() < 1 || f.cut() >= length) problems.add("cut must be from 1 to " + (length - 1) + ".");
      if (f.mutate() < 1 || f.mutate() > length) problems.add("mutate must be from 1 to " + length + ".");
    }
    if (!bitsOf(brief, "Parent A").equals(f.parentA())) problems.add("Parent A in the brief is not " + f.parentA() + ".");
    if (!bitsOf(brief, "Parent B").equals(f.parentB())) problems.add("Parent B in the brief is not " + f.parentB() + ".");
    if (!brief.matches("(?s).*Crossover after position " + f.cut() + "(?!\\d).*")) problems.add("the brief does not say \"Crossover after position " + f.cut() + "\".");
    if (!brief.matches("(?s).*flip bit number " + f.mutate() + "(?!\\d).*")) problems.add("the brief does not say \"flip bit number " + f.mutate() + "\".");
  }

  /** The bits of the brief's line "Parent A: 1 0 1 | 1 0 0", without spaces and the bar; empty when there is none. */
  private static String bitsOf(String brief, String parent) {
    Matcher line = Pattern.compile(Pattern.quote(parent) + ":\\s*([01 |]+)").matcher(brief);
    return line.find() ? line.group(1).replaceAll("[ |]", "") : "";
  }

  // ---- split ---------------------------------------------------------------------------------------

  private static void split(SplitFigure f, String brief, List<String> problems) {
    for (Counts counts : List.of(f.parent(), f.yes(), f.no())) {
      if (counts.normal() < 0 || counts.suspicious() < 0) problems.add("the counts cannot be negative.");
    }
    if (f.parent().normal() + f.parent().suspicious() < 1) problems.add("the parent node must hold at least one item.");
    if (f.parent().normal() != f.yes().normal() + f.no().normal() || f.parent().suspicious() != f.yes().suspicious() + f.no().suspicious()) {
      problems.add("the two branches must add up to the parent node.");
    }
    if (f.question().isBlank()) problems.add("the question cannot be empty.");
    else if (!brief.contains("\"" + f.question() + "\"")) problems.add("the brief does not quote the question \"" + f.question() + "\".");
    int total = f.parent().normal() + f.parent().suspicious();
    if (!brief.matches("(?s).*holds " + total + " card swipes: " + f.parent().normal() + " normal and " + f.parent().suspicious() + " suspicious.*")) {
      problems.add("the brief does not say the parent holds " + total + " card swipes: " + f.parent().normal() + " normal and " + f.parent().suspicious() + " suspicious.");
    }
    for (String branch : List.of("Yes", "No")) {
      Counts counts = branch.equals("Yes") ? f.yes() : f.no();
      if (!brief.matches("(?s).*" + branch + ":\\s*" + counts.normal() + " normal, " + counts.suspicious() + " suspicious.*")) {
        problems.add("the brief does not say \"" + branch + ": " + counts.normal() + " normal, " + counts.suspicious() + " suspicious\".");
      }
    }
  }

  // ---- minimax -------------------------------------------------------------------------------------

  private static void minimax(MinimaxFigure f, String brief, List<String> problems) {
    if (!List.of("MAX", "MIN").contains(f.root())) {
      problems.add("root must be MAX or MIN.");
      return;
    }
    if (f.children().size() < 2 || f.children().size() > 6) problems.add("it needs 2 to 6 child nodes.");
    if (f.children().stream().anyMatch(leaves -> leaves.isEmpty() || leaves.size() > 6)) problems.add("every child node needs 1 to 6 leaf values.");
    String child = f.root().equals("MAX") ? "MIN" : "MAX";
    List<List<String>> written = new ArrayList<>();
    Matcher lines = Pattern.compile(child + ":\\s*([-\\d., ]+)").matcher(brief);
    while (lines.find()) {
      List<String> leaves = new ArrayList<>();
      for (String leaf : lines.group(1).split(",")) if (!leaf.isBlank()) leaves.add(leaf.strip());
      written.add(leaves);
    }
    List<List<String>> drawn = f.children().stream().map(leaves -> leaves.stream().map(FigureRules::number).toList()).toList();
    if (!written.equals(drawn)) problems.add("the leaf values must be the ones the brief lists after \"" + child + ":\" (" + drawn + ").");
  }

  // ---- helpers -------------------------------------------------------------------------------------

  /** True when the brief contains "name = value" as written (3 matches "x1 = 3" but not "x1 = 30" or "x1 = 3.5"; a full stop after it is fine). */
  private static boolean assigned(String brief, String name, double value) {
    return Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\s*=\\s*" + Pattern.quote(number(value)) + "(?!\\d|\\.\\d)").matcher(brief).find();
  }

  /** A number as a brief writes it: no ".0" on a whole number, no trailing zeros. */
  static String number(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }
}
