package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContestUrlTest {

  @Test
  void anHttpsLinkWithAHostIsAccepted() {
    assertEquals(Optional.empty(), ContestUrl.problem("https://codeforces.com/contest/2200"));
    assertEquals(Optional.empty(), ContestUrl.problem("https://codeforces.com/gym/104500?locale=en#standings"));
    assertEquals(Optional.empty(), ContestUrl.problem("HTTPS://Codeforces.com/contest/1"));
  }

  @Test
  void everythingThatIsNotAnHttpsWebAddressIsRefused() {
    for (String bad : new String[] {
      "http://codeforces.com/contest/1",
      "javascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "ftp://codeforces.com/x",
      "//codeforces.com/contest/1",
      "codeforces.com/contest/1",
      "/contest/1",
      "https://",
      "https:///contest/1",
      "https://user:secret@codeforces.com/contest/1",
      "https://codeforces.com/a b",
      "https://codeforces.com/\u0000",
      " https://codeforces.com/contest/1",
      "https://codeforces.com/contest/1 ",
      "",
      "   ",
    }) {
      assertTrue(ContestUrl.problem(bad).isPresent(), "should be refused: [" + bad + "]");
    }
    assertTrue(ContestUrl.problem(null).isPresent());
  }

  @Test
  void aLinkOver500BytesIsRefused() {
    String long500 = "https://codeforces.com/" + "a".repeat(ContestUrl.MAX_BYTES - "https://codeforces.com/".length());
    assertEquals(ContestUrl.MAX_BYTES, long500.length());
    assertEquals(Optional.empty(), ContestUrl.problem(long500));
    assertTrue(ContestUrl.problem(long500 + "a").isPresent());
  }

  @Test
  void theReasonIsReadable() {
    assertEquals("The contest link must start with https://.", ContestUrl.problem("http://codeforces.com").orElseThrow());
    assertEquals("Enter the contest link.", ContestUrl.problem("").orElseThrow());
  }
}
