package dev.devfest.event.domain;

import java.util.List;

/** An uploaded terminal package broke one or more rules; carries every problem found (answered with 400). */
public class TerminalPackageRejectedException extends RuntimeException {

  private final List<String> problems;

  public TerminalPackageRejectedException(List<String> problems) {
    super(problems.size() == 1 ? problems.get(0) : "The package has " + problems.size() + " problems.");
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
