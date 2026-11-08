package dev.devfest.event.domain.sentinel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Forward chaining over if-all-then rules (stage 9): fire any rule whose conditions hold until nothing is new. Pure. */
public final class ForwardChaining {

  public record Rule(String id, List<String> ifAll, String then) {}

  private ForwardChaining() {}

  /** The facts known at the end: the starting facts, then each derived fact in the order the rules fired. */
  public static Set<String> closure(Set<String> start, List<Rule> rules) {
    Set<String> known = new LinkedHashSet<>(start);
    fire(known, rules, new ArrayList<>());
    return known;
  }

  /** The rules that added a new fact, in firing order. */
  public static List<String> firingOrder(Set<String> start, List<Rule> rules) {
    List<String> fired = new ArrayList<>();
    fire(new LinkedHashSet<>(start), rules, fired);
    return fired;
  }

  /** Whether a rule may be clicked now: all its conditions are known. */
  public static boolean applicable(Rule rule, Set<String> known) {
    return known.containsAll(rule.ifAll());
  }

  private static void fire(Set<String> known, List<Rule> rules, List<String> fired) {
    boolean changed = true;
    while (changed) {
      changed = false;
      for (Rule rule : rules) {
        if (!known.contains(rule.then()) && known.containsAll(rule.ifAll())) {
          known.add(rule.then());
          fired.add(rule.id());
          changed = true;
        }
      }
    }
  }
}
