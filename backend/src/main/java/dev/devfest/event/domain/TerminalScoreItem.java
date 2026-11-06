package dev.devfest.event.domain;

/** The five scored items of a Linux terminal room's form (rules §18.8); each is worth the same points. */
public enum TerminalScoreItem {
  PATH("PATH"),
  USERNAME("USERNAME"),
  FILE_SIZE("FILE_SIZE"),
  PATTERN("PATTERN"),
  /** Worth its points when the final clue is correct (the room's clue check). */
  CLUE("Clue");

  private final String label;

  TerminalScoreItem(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
