package dev.devfest.event.domain;

import java.util.List;

/** An uploaded content file broke one or more rules; carries every problem found (answered with 400). */
public class ContentRejectedException extends RuntimeException {

  private final List<String> problems;

  public ContentRejectedException(List<String> problems) {
    super("The file has " + problems.size() + (problems.size() == 1 ? " problem." : " problems."));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
