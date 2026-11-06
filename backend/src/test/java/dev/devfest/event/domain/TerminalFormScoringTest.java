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
  private static final TerminalAnswerKeys KEYS = new TerminalAnswerKeys("/srv/rami/uploads", "tech9", "1337c", "*.bak", "KHOOR ZRUOG");
  private static final TerminalFormValues PERFECT = new TerminalFormValues("/srv/rami/uploads/", "tech9", "1337C", "\"*.bak\"", "KHOOR  ZRUOG", " west tower ");
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
    assertTrue(score.messageCorrect() && score.clueCorrect());
  }

  @Test
  void eachParameterIsWorth3OnItsOwn() {
    TerminalFormValues onlyPath = new TerminalFormValues("/srv/rami/uploads", "x", "x", "x", "x", "x");
    Score score = TerminalFormScoring.score(KEYS, onlyPath, CLUE_KEY, NO_OVERRIDES);
    assertEquals(3, score.total());
    assertTrue(item(score, TerminalScoreItem.PATH).awarded());
    assertFalse(item(score, TerminalScoreItem.USERNAME).awarded());
    assertEquals(9, TerminalFormScoring.score(KEYS, new TerminalFormValues("/srv/rami/uploads", "tech9", "1337c", "x", "", ""), CLUE_KEY, NO_OVERRIDES).total());
  }

  @Test
  void theMessageAndTheClueAreWorth3OnlyTogether() {
    TerminalFormValues messageOnly = new TerminalFormValues("", "", "", "", "KHOOR ZRUOG", "wrong");
    TerminalFormValues clueOnly = new TerminalFormValues("", "", "", "", "wrong", "West Tower");
    TerminalFormValues both = new TerminalFormValues("", "", "", "", "KHOOR ZRUOG", "WEST   TOWER");
    assertEquals(0, TerminalFormScoring.score(KEYS, messageOnly, CLUE_KEY, NO_OVERRIDES).total());
    assertEquals(0, TerminalFormScoring.score(KEYS, clueOnly, CLUE_KEY, NO_OVERRIDES).total());
    Score score = TerminalFormScoring.score(KEYS, both, CLUE_KEY, NO_OVERRIDES);
    assertEquals(3, score.total());
    assertTrue(item(score, TerminalScoreItem.MESSAGE_CLUE).awarded());
    assertTrue(TerminalFormScoring.score(KEYS, messageOnly, CLUE_KEY, NO_OVERRIDES).messageCorrect());
    assertTrue(TerminalFormScoring.score(KEYS, clueOnly, CLUE_KEY, NO_OVERRIDES).clueCorrect());
  }

  @Test
  void anEmptyKeyCannotBeAwardedAutomaticallyAndNeitherCanABlankClueKey() {
    Score none = TerminalFormScoring.score(TerminalAnswerKeys.empty(), PERFECT, CLUE_KEY, NO_OVERRIDES);
    assertEquals(0, none.total(), "no keys, no automatic points");
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

    Score denied = TerminalFormScoring.score(KEYS, PERFECT, CLUE_KEY, overrides(TerminalScoreItem.USERNAME, false, TerminalScoreItem.MESSAGE_CLUE, false));
    assertEquals(9, denied.total());
    assertTrue(item(denied, TerminalScoreItem.USERNAME).auto());
    assertFalse(item(denied, TerminalScoreItem.USERNAME).awarded());
    assertFalse(item(denied, TerminalScoreItem.MESSAGE_CLUE).awarded());
    assertTrue(denied.messageCorrect() && denied.clueCorrect(), "denying the item does not change what was right");
  }

  @Test
  void clearingAnOverrideGoesBackToAutomaticAndTheTotalNeverExceeds15() {
    Score all = TerminalFormScoring.score(
      KEYS,
      TerminalFormValues.empty(),
      CLUE_KEY,
      overrides(TerminalScoreItem.PATH, true, TerminalScoreItem.USERNAME, true, TerminalScoreItem.FILE_SIZE, true, TerminalScoreItem.PATTERN, true, TerminalScoreItem.MESSAGE_CLUE, true)
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
    TerminalFormValues values = new TerminalFormValues("", "", "", "", "KHOOR ZRUOG", "NEW KEY");
    assertEquals(0, TerminalFormScoring.score(KEYS, values, CLUE_KEY, NO_OVERRIDES).total());
    assertEquals(3, TerminalFormScoring.score(KEYS, values, "new  key", NO_OVERRIDES).total(), "the same answer scores when the key changes");
  }
}
