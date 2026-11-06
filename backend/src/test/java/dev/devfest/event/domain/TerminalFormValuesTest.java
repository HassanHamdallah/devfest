package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TerminalFormValuesTest {

  private static TerminalFormValues filled() {
    return new TerminalFormValues("/srv/rami/uploads", "tech9", "1337c", "*.bak", "KHOOR", "WEST TOWER");
  }

  @Test
  void aFormHasContentWhenAnyFieldIsNotBlank() {
    assertFalse(TerminalFormValues.empty().hasContent());
    assertFalse(new TerminalFormValues(" ", "\t", "", "\n", "  ", "").hasContent());
    for (int field = 0; field < 6; field++) {
      String[] values = { "", "", "", "", "", "" };
      values[field] = "x";
      assertTrue(new TerminalFormValues(values[0], values[1], values[2], values[3], values[4], values[5]).hasContent(), "field " + field);
    }
  }

  @Test
  void blanksAndMaximumSizedValuesAreAccepted() {
    assertTrue(TerminalFormValues.empty().problem().isEmpty());
    String max = "é".repeat(250);
    assertEquals(500, max.getBytes(StandardCharsets.UTF_8).length);
    assertTrue(new TerminalFormValues(max, max, max, max, max, max).problem().isEmpty());
  }

  @Test
  void aTooLongOrNulValueNamesItsField() {
    String tooLong = "é".repeat(250) + "x";
    assertEquals("PATH is too long (max 500 bytes).", new TerminalFormValues(tooLong, "", "", "", "", "").problem().orElseThrow());
    assertEquals("Final clue is too long (max 500 bytes).", new TerminalFormValues("", "", "", "", "", tooLong).problem().orElseThrow());
    assertEquals("PATTERN must be text.", new TerminalFormValues("", "", "", "a\0b", "", "").problem().orElseThrow());
  }

  @Test
  void theSummaryLabelsEveryFieldAndSaysHowItCameAbout() {
    String submitted = filled().summary(TerminalFormCause.SUBMIT);
    assertEquals(
      "Linux terminal form (submitted by the team)\nPATH: /srv/rami/uploads\nUSERNAME: tech9\nFILE_SIZE: 1337c\nPATTERN: *.bak\nEncrypted message: KHOOR\nFinal clue: WEST TOWER",
      submitted
    );
    assertTrue(filled().summary(TerminalFormCause.TIME_UP).startsWith("Linux terminal form (saved by the team, submitted at Time's up)\n"));
  }

  @Test
  void theLargestSummaryFitsTheSubmissionOutputLimit() {
    String max = "é".repeat(250);
    String summary = new TerminalFormValues(max, max, max, max, max, max).summary(TerminalFormCause.TIME_UP);
    assertTrue(summary.getBytes(StandardCharsets.UTF_8).length <= 4000, "bytes: " + summary.getBytes(StandardCharsets.UTF_8).length);
  }
}
