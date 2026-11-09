package dev.devfest.event.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The event ranking: total DESC, then final score DESC, then the earlier final finish (a team that never submitted a
 * suspect finishes last on this tie-break); teams equal on all three share a rank (1, 1, 3). Teams that did not
 * qualify rank by their room scores like everyone else. Disqualified teams stay outside the ranking: listed last,
 * with no rank. Pure.
 */
public final class EventRanking {

  /** One active team's totals; {@code finishedAt} is the suspect submission time or null. */
  public record Entry(long teamId, String teamName, BigDecimal total, BigDecimal finalScore, Instant finishedAt, boolean disqualified) {}

  /** {@code rank} is null for a disqualified team. */
  public record Ranked(Integer rank, Entry entry) {}

  private static final Comparator<Entry> ORDER = Comparator
    .comparing(Entry::total, Comparator.reverseOrder())
    .thenComparing(Entry::finalScore, Comparator.reverseOrder())
    .thenComparing(Entry::finishedAt, Comparator.nullsLast(Comparator.naturalOrder()))
    .thenComparing(Entry::teamName);

  private EventRanking() {}

  public static List<Ranked> rank(List<Entry> entries) {
    List<Entry> eligible = entries.stream().filter(e -> !e.disqualified()).sorted(ORDER).toList();
    List<Ranked> ranked = new ArrayList<>();
    for (int i = 0; i < eligible.size(); i++) {
      Entry entry = eligible.get(i);
      boolean tied = i > 0 && sameStanding(eligible.get(i - 1), entry);
      int rank = tied ? ranked.get(i - 1).rank() : i + 1;
      ranked.add(new Ranked(rank, entry));
    }
    entries.stream().filter(Entry::disqualified).sorted(Comparator.comparing(Entry::teamName)).forEach(e -> ranked.add(new Ranked(null, e)));
    return ranked;
  }

  private static boolean sameStanding(Entry a, Entry b) {
    return a.total().compareTo(b.total()) == 0
      && a.finalScore().compareTo(b.finalScore()) == 0
      && java.util.Objects.equals(a.finishedAt(), b.finishedAt());
  }
}
