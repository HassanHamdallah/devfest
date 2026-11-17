package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalAnswerKeysTest {

  private static TerminalAnswerKeys keys(String path, String username, String fileSize, String pattern) {
    return new TerminalAnswerKeys(path, username, fileSize, pattern);
  }

  @Test
  void everyValueIsTrimmed() {
    TerminalAnswerKeys keys = keys("/srv/rami/uploads", "tech9", "1337c", "*.bak");
    assertTrue(keys.pathCorrect("  /srv/rami/uploads \t"));
    assertTrue(keys.usernameCorrect(" tech9 "));
    assertTrue(keys.fileSizeCorrect("\n1337c\n"));
    assertTrue(keys.patternCorrect("  *.bak  "));
    assertTrue(keys(" /srv/x \n", " u ", " 1c ", " *.a ").pathCorrect("/srv/x"), "the key is trimmed too");
  }

  @Test
  void pathIgnoresOneTrailingSlashOnEitherSide() {
    TerminalAnswerKeys keys = keys("/srv/rami/uploads\n/var/backups/", "", "", "");
    assertTrue(keys.pathCorrect("/srv/rami/uploads/"));
    assertTrue(keys.pathCorrect("/srv/rami/uploads"));
    assertTrue(keys.pathCorrect("/var/backups"));
    assertTrue(keys.pathCorrect("/var/backups/"));
    assertFalse(keys.pathCorrect("/srv/rami/uploads//"), "only one slash is ignored");
    assertFalse(keys.pathCorrect("/srv/rami"));
    assertFalse(keys.pathCorrect("srv/rami/uploads"), "a relative path is another path");
    assertTrue(keys("/", "", "", "").pathCorrect("/"), "the root stays the root");
    assertFalse(keys("/", "", "", "").pathCorrect(""));
  }

  @Test
  void usernameIsCaseSensitive() {
    TerminalAnswerKeys keys = keys("", "tech9", "", "");
    assertTrue(keys.usernameCorrect("tech9"));
    assertFalse(keys.usernameCorrect("Tech9"));
    assertFalse(keys.usernameCorrect("tech 9"));
  }

  @Test
  void fileSizeIgnoresCase() {
    TerminalAnswerKeys keys = keys("", "", "1337c\n2K", "");
    assertTrue(keys.fileSizeCorrect("1337C"));
    assertTrue(keys.fileSizeCorrect("2k"));
    assertFalse(keys.fileSizeCorrect("1337"));
    assertFalse(keys.fileSizeCorrect("13 37c"));
  }

  @Test
  void patternIgnoresOnePairOfSurroundingQuotesButNothingElse() {
    TerminalAnswerKeys keys = keys("", "", "", "*.bak\nhandover-*");
    assertTrue(keys.patternCorrect("\"*.bak\""));
    assertTrue(keys.patternCorrect("'*.bak'"));
    assertTrue(keys.patternCorrect("  \"*.bak\"  "));
    assertTrue(keys.patternCorrect("handover-*"));
    assertFalse(keys.patternCorrect("\"*.bak'"), "mismatched quotes are not quotes");
    assertFalse(keys.patternCorrect("\"\"*.bak\"\""), "only one pair is ignored");
    assertFalse(keys.patternCorrect("*.BAK"), "case matters");
    assertTrue(keys("", "", "", "\"*.bak\"").patternCorrect("*.bak"), "a quoted key matches the bare pattern");
  }

  @Test
  void severalValuesOnSeparateLinesAreAllAccepted() {
    TerminalAnswerKeys keys = keys("/a\r\n\r\n/b\n  \n/c", "", "", "");
    for (String value : List.of("/a", "/b", "/c")) assertTrue(keys.pathCorrect(value), value);
    assertFalse(keys.pathCorrect("/d"));
    assertEquals(List.of(), keys.emptyKeys().stream().filter(name -> name.equals("PATH")).toList());
  }

  @Test
  void anEmptyKeyAndABlankAnswerNeverMatch() {
    TerminalAnswerKeys none = TerminalAnswerKeys.empty();
    assertFalse(none.pathCorrect(""));
    assertFalse(none.pathCorrect("/anything"));
    assertFalse(none.usernameCorrect("root"));
    assertFalse(none.fileSizeCorrect("1c"));
    assertFalse(none.patternCorrect("*"));
    assertFalse(keys("  \n \n", "", "", "").pathCorrect("  "));
    assertFalse(keys("x", "x", "x", "x").usernameCorrect(null));
    assertEquals(List.of("PATH", "USERNAME", "FILE_SIZE", "FILE_NAME"), none.emptyKeys());
    assertEquals(List.of("USERNAME", "FILE_NAME"), keys("/p", "", "1c", " ").emptyKeys());
  }

  @Test
  void theKeysAreLimitedInCountAndSize() {
    assertTrue(TerminalAnswerKeys.empty().problem().isEmpty());
    assertTrue(keys("a\n".repeat(20), "", "", "").problem().isEmpty());
    assertEquals("PATH accepts at most 20 values.", keys("a\n".repeat(21), "", "", "").problem().orElseThrow());
    assertEquals("USERNAME has a value that is too long (max 500 bytes).", keys("", "é".repeat(251), "", "").problem().orElseThrow());
    assertEquals("FILE_SIZE must be text.", keys("", "", "1\u0000", "").problem().orElseThrow());
  }
}
