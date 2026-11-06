package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.QuizShuffle.Kind;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class QuizShuffleTest {

  private static final byte[] SECRET = "test-secret".getBytes(StandardCharsets.UTF_8);
  private static final List<Long> TWENTY = LongStream.rangeClosed(1, 20).boxed().toList();

  @Test
  void theSameTeamAndItemAlwaysGetTheSameOrder() {
    long seed = QuizShuffle.seed(SECRET, Kind.SECTION, 7, 0);
    assertEquals(seed, QuizShuffle.seed(SECRET, Kind.SECTION, 7, 0));
    assertEquals(QuizShuffle.shuffle(TWENTY, seed), QuizShuffle.shuffle(TWENTY, seed));
    assertEquals(TWENTY.size(), QuizShuffle.shuffle(TWENTY, seed).size());
    assertEquals(new HashSet<>(TWENTY), new HashSet<>(QuizShuffle.shuffle(TWENTY, seed)), "a permutation");
  }

  @Test
  void differentTeamsItemsKindsAndSecretsGetDifferentOrders() {
    Set<List<Long>> orders = new HashSet<>();
    for (long team = 1; team <= 16; team++) orders.add(QuizShuffle.shuffle(TWENTY, QuizShuffle.seed(SECRET, Kind.SECTION, team, 0)));
    assertEquals(16, orders.size(), "16 teams, 16 different orders of 20 questions");

    long base = QuizShuffle.seed(SECRET, Kind.OPTIONS, 3, 99);
    assertNotEquals(base, QuizShuffle.seed(SECRET, Kind.OPTIONS, 3, 100));
    assertNotEquals(base, QuizShuffle.seed(SECRET, Kind.SNIPPETS, 3, 99));
    assertNotEquals(base, QuizShuffle.seed("other".getBytes(StandardCharsets.UTF_8), Kind.OPTIONS, 3, 99));
  }

  @Test
  void snippetsNeverStartInTheirCorrectOrder() {
    for (List<Long> correct : List.of(List.of(1L, 2L), List.of(1L, 2L, 3L, 4L), List.of(5L, 6L, 7L, 8L, 9L), List.of(1L, 2L, 3L, 4L, 5L, 6L))) {
      Set<List<Long>> seen = new HashSet<>();
      for (long team = 1; team <= 2000; team++) {
        List<Long> shown = QuizShuffle.shuffleAwayFrom(correct, QuizShuffle.seed(SECRET, Kind.SNIPPETS, team, 42));
        assertNotEquals(correct, shown, "team " + team);
        assertEquals(new HashSet<>(correct), new HashSet<>(shown));
        seen.add(shown);
      }
      assertTrue(seen.size() > 1 || correct.size() == 2, "orders vary for " + correct.size() + " snippets");
    }
  }

  @Test
  void tinyListsAreReturnedAsTheyAre() {
    assertEquals(List.of(), QuizShuffle.shuffleAwayFrom(List.<Long>of(), 1));
    assertEquals(List.of(4L), QuizShuffle.shuffleAwayFrom(List.of(4L), 1));
  }
}
