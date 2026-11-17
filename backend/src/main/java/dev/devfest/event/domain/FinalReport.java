package dev.devfest.event.domain;

/**
 * The team's Final Investigation Report: up to four pieces of evidence and an explanation, each optional, trimmed and at
 * most {@link #MAX_CHARS} characters. It is stored with the suspect, shown back to the team read-only and read by the
 * organizers; it never takes part in a score.
 */
public record FinalReport(String evidence1, String evidence2, String evidence3, String evidence4, String explanation) {
  public static final int MAX_CHARS = 600;

  public static final FinalReport EMPTY = new FinalReport(null, null, null, null, null);

  /** True when no evidence box has any text (the explanation is optional and does not count). */
  public boolean noEvidence() {
    return evidence1 == null && evidence2 == null && evidence3 == null && evidence4 == null;
  }
}
