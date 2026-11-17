package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.TerminalFormScoring.Item;
import dev.devfest.event.domain.TerminalFormScoring.Score;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalFormScoringTest {

  private static final String CLUE_KEY = "WEST TOWER";
  private static final TerminalAnswerKeys KEYS = new TerminalAnswerKeys("/srv/rami/uploads", "tech9", "1337c", "*.bak");
  private static final TerminalFormValues PERFECT = new TerminalFormValues("/srv/rami/uploads/", "tech9", "1337C", "\"*.bak\"", " west tower ");
  private static final Map<TerminalScoreItem, Boolean> NO_OVERRIDES = new EnumMap<>(TerminalScoreItem.class);

  private static Item item(Score score, TerminalScoreItem which) {
    return score.items().stream().filter(candidate -> candidate.item() == which).findFirst().orElseThrow();
  }

  private static Map<TerminalScoreItem, Boolean> overrides(Object... pairs) {
    Map<TerminalScoreItem, Boolean> map = new EnumMap<>(TerminalScoreItem.class);
    for (int i = 0; i < pairs.length; i += 2) map.put((TerminalScoreItem) pairs[i], (Boolean) pairs[i + 1]);
    return map;
  }

  @Test
  void theMaximumIs15AndEveryItemIsWorth3() {
    assertEquals(3, TerminalFormScoring.POINTS_PER_ITEM);
    assertEquals(15, TerminalFormScoring.MAX_POINTS);
    Score score = TerminalFormScoring.score(KEYS, PERFECT, CLUE_KEY, NO_OVERRIDES);
    assertEquals(15, score.total());
    assertEquals(5, score.items().size());
    assertTrue(score.items().stream().allMatch(item -> item.points() == 3 && item.awarded() && item.override() == null));
    assertTrue(score.clueCorrect());
  }

  @Test
  void eachParameterIsWorth3OnItsOwn() {
    TerminalFormValues onlyPath = new TerminalFormValues("/srv/rami/uploads", "x", "x", "x", "x");
    Score score = TerminalFormScoring.score(KEYS, onlyPath, CLUE_KEY, NO_OVERRIDES);
    assertEquals(3, score.total());
    assertTrue(item(score, TerminalScoreItem.PATH).awarded());
    assertFalse(item(score, TerminalScoreItem.USERNAME).awarded());
    assertEquals(9, TerminalFormScoring.score(KEYS, new TerminalFormValues("/srv/rami/uploads", "tech9", "1337c", "x", ""), CLUE_KEY, NO_OVERRIDES).total());
  }

  @Test
  void aStoredPatternKeyStillScoresTheFileNameItem() {
    // The stored key and the JSON field keep the name "pattern"; only the label people see is FILE_NAME.
    TerminalFormValues onlyName = new TerminalFormValues("", "", "", "\"*.bak\"", "");
    Score score = TerminalFormScoring.score(KEYS, onlyName, CLUE_KEY, NO_OVERRIDES);
    assertEquals(3, score.total());
    assertTrue(item(score, TerminalScoreItem.PATTERN).awarded());
    assertEquals("FILE_NAME", item(score, TerminalScoreItem.PATTERN).item().label());
    assertEquals("pattern", TerminalScoreItem.PATTERN.name().toLowerCase());
    assertEquals(java.util.List.of("FILE_NAME"), new TerminalAnswerKeys("/p", "u", "1c", "").emptyKeys());
  }

  @Test
  void aCorrectFinalClueIsWorth3OnItsOwn() {
    TerminalFormValues wrong = new TerminalFormValues("", "", "", "", "wrong");
    TerminalFormValues right = new TerminalFormValues("", "", "", "", "West   Tower");
    Score miss = TerminalFormScoring.score(KEYS, wrong, CLUE_KEY, NO_OVERRIDES);
    assertEquals(0, miss.total());
    assertFalse(miss.clueCorrect());
    assertFalse(item(miss, TerminalScoreItem.CLUE).awarded());
    Score hit = TerminalFormScoring.score(KEYS, right, CLUE_KEY, NO_OVERRIDES);
    assertEquals(3, hit.total(), "the usual clue check: case and spaces are ignored");
    assertTrue(hit.clueCorrect());
    assertTrue(item(hit, TerminalScoreItem.CLUE).awarded());
    assertEquals("RETRIEVED KEY", item(hit, TerminalScoreItem.CLUE).item().label());
    assertEquals(
      java.util.List.of("PATH", "USERNAME", "FILE_SIZE", "PATTERN", "CLUE"),
      hit.items().stream().map(item -> item.item().name()).toList()
    );
  }

  @Test
  void anEmptyKeyCannotBeAwardedAutomaticallyAndNeitherCanABlankClueKey() {
    Score none = TerminalFormScoring.score(TerminalAnswerKeys.empty(), PERFECT, CLUE_KEY, NO_OVERRIDES);
    assertEquals(3, none.total(), "no keys: only the Clue scores, because it has no key here (the room's clue key decides)");
    assertFalse(item(none, TerminalScoreItem.PATH).awarded());
    Score noClueKey = TerminalFormScoring.score(KEYS, PERFECT, "", NO_OVERRIDES);
    assertEquals(12, noClueKey.total(), "an empty clue key never matches");
  }

  @Test
  void anOverrideAwardsOrDeniesOneItemWhateverTheKeysSay() {
    Score awarded = TerminalFormScoring.score(KEYS, TerminalFormValues.empty(), CLUE_KEY, overrides(TerminalScoreItem.PATH, true));
    assertEquals(3, awarded.total());
    Item path = item(awarded, TerminalScoreItem.PATH);
    assertTrue(path.awarded());
    assertFalse(path.auto(), "the automatic result is kept next to the override");
    assertEquals(Boolean.TRUE, path.override());

    Score denied = TerminalFormScoring.score(KEYS, PERFECT, CLUE_KEY, overrides(TerminalScoreItem.USERNAME, false, TerminalScoreItem.CLUE, false));
    assertEquals(9, denied.total());
    assertTrue(item(denied, TerminalScoreItem.USERNAME).auto());
    assertFalse(item(denied, TerminalScoreItem.USERNAME).awarded());
    assertFalse(item(denied, TerminalScoreItem.CLUE).awarded());
    assertTrue(denied.clueCorrect(), "denying the item does not change what was right");
  }

  @Test
  void clearingAnOverrideGoesBackToAutomaticAndTheTotalNeverExceeds15() {
    Score all = TerminalFormScoring.score(
      KEYS,
      TerminalFormValues.empty(),
      CLUE_KEY,
      overrides(TerminalScoreItem.PATH, true, TerminalScoreItem.USERNAME, true, TerminalScoreItem.FILE_SIZE, true, TerminalScoreItem.PATTERN, true, TerminalScoreItem.CLUE, true)
    );
    assertEquals(15, all.total());
    Score cleared = TerminalFormScoring.score(KEYS, TerminalFormValues.empty(), CLUE_KEY, NO_OVERRIDES);
    assertEquals(0, cleared.total());
    assertNull(item(cleared, TerminalScoreItem.PATH).override());
  }

  @Test
  void aTeamThatNeverStartedScoresOnlyWhatAnOrganizerAwards() {
    assertEquals(0, TerminalFormScoring.notStarted(NO_OVERRIDES).total());
    assertEquals(6, TerminalFormScoring.notStarted(overrides(TerminalScoreItem.PATTERN, true, TerminalScoreItem.PATH, true)).total());
  }

  @Test
  void theCluePartFollowsTheCurrentKey() {
    TerminalFormValues values = new TerminalFormValues("", "", "", "", "NEW KEY");
    assertEquals(0, TerminalFormScoring.score(KEYS, values, CLUE_KEY, NO_OVERRIDES).total());
    assertEquals(3, TerminalFormScoring.score(KEYS, values, "new  key", NO_OVERRIDES).total(), "the same answer scores when the key changes");
  }
}
