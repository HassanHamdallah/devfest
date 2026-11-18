package dev.devfest.event.domain;

import java.util.List;
import java.util.Optional;

/**
 * The points of a points-and-phone room (rules §21.1), a pure function of a team's ledger entries. A team's total is
 * the sum of its entries and always stays between 0 and 100. "Undo" never deletes: it appends a reversing entry for the
 * newest entry that is not an undo and has not been undone yet, so repeating it steps back through the history.
 */
public final class PointsLedger {

  public static final int MIN_TOTAL = 0;
  public static final int MAX_TOTAL = 100;
  public static final int MAX_DELTA = 100;

  private PointsLedger() {}

  /** One ledger row as far as the rules need it; {@code reversesId} is set for an undo row only. */
  public record Entry(long id, int delta, Long reversesId) {
    public boolean isUndo() {
      return reversesId != null;
    }
  }

  /** Why {@code delta} cannot be applied, or empty when it can. {@code total} is the current total. */
  public static Optional<String> problem(int total, int delta) {
    if (delta == 0) return Optional.of("The change cannot be 0.");
    if (delta < -MAX_DELTA || delta > MAX_DELTA) return Optional.of("The change must be from -100 to 100.");
    int next = total + delta;
    if (next < MIN_TOTAL || next > MAX_TOTAL) {
      return Optional.of("The total must stay between 0 and 100 (now " + total + ").");
    }
    return Optional.empty();
  }

  /** The sum of the entries, oldest or newest first. */
  public static int total(List<Entry> entries) {
    return entries.stream().mapToInt(Entry::delta).sum();
  }

  /** The entry "Undo last" would reverse: the newest one that is not an undo and was not undone, if any. */
  public static Optional<Entry> undoTarget(List<Entry> oldestFirst) {
    for (int i = oldestFirst.size() - 1; i >= 0; i--) {
      Entry candidate = oldestFirst.get(i);
      if (candidate.isUndo()) continue;
      boolean undone = oldestFirst.stream().anyMatch(entry -> entry.reversesId() != null && entry.reversesId() == candidate.id());
      if (!undone) return Optional.of(candidate);
    }
    return Optional.empty();
  }
}
