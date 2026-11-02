package dev.devfest.event.domain;

import java.util.Collection;

/** Penalty arithmetic shared by boards, the final and the hint cross-check. Voided penalties never count. */
public final class PenaltyRules {

  public static final String AUTOMATIC_KIND_MESSAGE = "This penalty kind is added automatically.";

  /** The view of a stored penalty these rules need; repository records implement it. */
  public interface CountedPenalty {
    PenaltyKind kind();

    int minutes();

    boolean voided();
  }

  private PenaltyRules() {}

  public static void requireManagerEntered(PenaltyKind kind) {
    if (!kind.managerEntered()) throw new EventRuleException(AUTOMATIC_KIND_MESSAGE);
  }

  public static int activeMinutes(Collection<? extends CountedPenalty> penalties) {
    return penalties.stream().filter(p -> !p.voided()).mapToInt(CountedPenalty::minutes).sum();
  }

  public static int activeCount(Collection<? extends CountedPenalty> penalties, PenaltyKind kind) {
    return (int) penalties.stream().filter(p -> !p.voided() && p.kind() == kind).count();
  }

  /** The manager's hint counter is informational; flag it when it disagrees with the active HINT penalties. */
  public static boolean hintPenaltyMismatch(int hints, Collection<? extends CountedPenalty> roomPenalties) {
    return hints != activeCount(roomPenalties, PenaltyKind.HINT);
  }
}
