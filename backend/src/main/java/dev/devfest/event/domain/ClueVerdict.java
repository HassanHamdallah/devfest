package dev.devfest.event.domain;

/** The clue result a team gets: a manager's override wins over the key-based (automatic) result. */
public final class ClueVerdict {

  private ClueVerdict() {}

  public static boolean effective(boolean autoCorrect, ClueOverride override) {
    return switch (override) {
      case ACCEPT -> true;
      case REJECT -> false;
      case AUTOMATIC -> autoCorrect;
    };
  }
}
