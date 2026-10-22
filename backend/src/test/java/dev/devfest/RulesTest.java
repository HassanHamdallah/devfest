package dev.devfest;

import static org.junit.jupiter.api.Assertions.*;

import dev.devfest.contest.ContestService;
import dev.devfest.judge.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RulesTest {

  @Test
  void exactContestBoundaries() {
    var start = Instant.parse("2026-10-20T10:00:00Z");
    var end = start.plusSeconds(3600);
    assertFalse(ContestService.eligible(start.minusNanos(1), start, end));
    assertTrue(ContestService.eligible(start, start, end));
    assertTrue(ContestService.eligible(end.minusNanos(1), start, end));
    assertFalse(ContestService.eligible(end, start, end));
  }

  @Test
  void deterministicWhitespace() {
    assertEquals("5", Verdicts.normalize("5 \t\r\n\r\n"));
    assertNotEquals(Verdicts.normalize(" 5\n"), Verdicts.normalize("5\n"));
    assertNotEquals(Verdicts.normalize("5 6"), Verdicts.normalize("5  6"));
  }

  @Test
  void compilationIsNotAcceptance() {
    assertEquals(
      "COMPILATION_ERROR",
      Verdicts.verdict(
        new JudgeClient.Result(6, "5", "", "error", "", null),
        "5",
        false,
        65536
      )
    );
  }

  @Test
  void outputMustMatch() {
    var r = new JudgeClient.Result(3, "4\n", "", "", "", 100L);
    assertEquals("WRONG_ANSWER", Verdicts.verdict(r, "5\n", false, 65536));
    assertEquals("ACCEPTED", Verdicts.verdict(r, "", true, 65536));
  }

  @Test
  void failureStatuses() {
    assertEquals(
      "TIME_LIMIT_EXCEEDED",
      Verdicts.verdict(
        new JudgeClient.Result(5, "", "", "", "", null),
        "",
        false,
        65536
      )
    );
    assertEquals(
      "RUNTIME_ERROR",
      Verdicts.verdict(
        new JudgeClient.Result(11, "", "", "", "", null),
        "",
        false,
        65536
      )
    );
    assertEquals(
      "MEMORY_LIMIT_EXCEEDED",
      Verdicts.verdict(
        new JudgeClient.Result(11, "", "", "", "", 65536L),
        "",
        false,
        65536
      )
    );
    assertEquals(
      "SYSTEM_ERROR",
      Verdicts.verdict(
        new JudgeClient.Result(13, "", "", "", "", null),
        "",
        false,
        65536
      )
    );
  }
}
