package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.devfest.event.domain.EventRanking.Entry;
import dev.devfest.event.domain.EventRanking.Ranked;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Total DESC, then final score DESC, then earlier final finish; ties share a rank; disqualified teams stay outside. */
class EventRankingRulesTest {

  private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

  private static Entry entry(long id, String total, String finalScore, Instant finishedAt, boolean disqualified) {
    return new Entry(id, "Team " + id, new BigDecimal(total), new BigDecimal(finalScore), finishedAt, disqualified);
  }

  private static List<String> places(List<Ranked> ranked) {
    return ranked.stream().map(r -> (r.rank() == null ? "-" : r.rank()) + ":" + r.entry().teamId()).toList();
  }

  @Test
  void totalDecidesFirst() {
    var ranked = EventRanking.rank(List.of(entry(1, "50", "10", T, false), entry(2, "70", "5", T, false), entry(3, "60", "40", T, false)));
    assertEquals(List.of("1:2", "2:3", "3:1"), places(ranked));
  }

  @Test
  void equalTotalsAreSeparatedByTheFinalScore() {
    var ranked = EventRanking.rank(List.of(entry(1, "70", "20", T, false), entry(2, "70", "30", T, false)));
    assertEquals(List.of("1:2", "2:1"), places(ranked));
  }

  @Test
  void equalTotalAndFinalScoreAreSeparatedByTheEarlierFinish() {
    var ranked = EventRanking.rank(List.of(entry(1, "70", "30", T.plusSeconds(60), false), entry(2, "70", "30", T, false)));
    assertEquals(List.of("1:2", "2:1"), places(ranked));
  }

  @Test
  void aTeamThatNeverFinishedLosesTheFinishTieBreak() {
    var ranked = EventRanking.rank(List.of(entry(1, "30", "0", null, false), entry(2, "30", "0", T, false)));
    assertEquals(List.of("1:2", "2:1"), places(ranked));
  }

  @Test
  void teamsEqualOnEverythingShareARankAndTheNextSkips() {
    var ranked = EventRanking.rank(
      List.of(
        entry(1, "80", "30", T, false),
        entry(2, "80", "30", T, false),
        entry(3, "60", "10", T, false),
        entry(4, "10", "0", null, false),
        entry(5, "10", "0", null, false)
      )
    );
    assertEquals(List.of("1:1", "1:2", "3:3", "4:4", "4:5"), places(ranked));
  }

  @Test
  void unqualifiedTeamsRankByTheirRoomScores() {
    var ranked = EventRanking.rank(List.of(entry(1, "35.5", "0", null, false), entry(2, "30", "0", null, false), entry(3, "20", "25", T, false)));
    assertEquals(List.of("1:1", "2:2", "3:3"), places(ranked));
  }

  @Test
  void disqualifiedTeamsStayOutsideTheRankingAndTheRankNumbers() {
    var ranked = EventRanking.rank(List.of(entry(1, "90", "40", T, true), entry(2, "50", "10", T, false), entry(3, "40", "10", T, false)));
    assertEquals(List.of("1:2", "2:3", "-:1"), places(ranked));
    assertNull(ranked.get(2).rank());
  }
}
