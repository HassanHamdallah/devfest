package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.TerminalManifest.Parsed;
import dev.devfest.event.domain.TerminalManifest.Rule;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class TerminalManifestTest {

  @Test
  void readsRulesWithAHeaderCommentsBlankLinesQuotesAndCrLf() {
    Parsed parsed = TerminalManifest.parse(
      "path,owner,group,mode\r\n# who owns what\r\n\r\n/srv/rami/**,rami,ops,0640\r\n\"srv/odd,name.txt\",tech9,tech9,600\r\n"
    );
    assertEquals(List.of(), parsed.problems());
    assertEquals(2, parsed.rules().size());
    Rule folder = parsed.rules().get(0);
    assertEquals("srv/rami", folder.base());
    assertTrue(folder.recursive());
    assertEquals(0640, folder.mode());
    assertEquals(4, folder.line());
    assertEquals("srv/odd,name.txt", parsed.rules().get(1).base());
    assertEquals(0600, parsed.rules().get(1).mode());
  }

  @Test
  void anExactPathBeatsAGlobAndADeeperFolderBeatsAShallowerOne() {
    Parsed parsed = TerminalManifest.parse(
      "srv/**,a,a,0644\nsrv/rami/**,b,b,0644\nsrv/rami/secret.txt,c,c,0600\n/**,d,d,0644\n"
    );
    assertEquals(List.of(), parsed.problems());
    List<Rule> rules = parsed.rules();
    assertEquals("c", TerminalManifest.match(rules, "srv/rami/secret.txt").orElseThrow().owner());
    assertEquals("b", TerminalManifest.match(rules, "srv/rami/other.txt").orElseThrow().owner());
    assertEquals("b", TerminalManifest.match(rules, "srv/rami").orElseThrow().owner(), "a folder rule covers the folder itself");
    assertEquals("a", TerminalManifest.match(rules, "srv/logs/x").orElseThrow().owner());
    assertEquals("d", TerminalManifest.match(rules, "home/x").orElseThrow().owner());
    assertFalse(TerminalManifest.match(rules.subList(0, 1), "srvx/file").isPresent(), "srv/** must not cover srvx");
  }

  @Test
  void everyKindOfBadRowIsReportedWithItsLine() {
    Parsed parsed = TerminalManifest.parse(
      String.join(
        "\n",
        "srv/a.txt,Rami,rami,0644",
        "srv/b.txt,rami,ro oot,0644",
        "srv/c.txt,rami,rami,0999",
        "srv/d.txt,rami,rami,64",
        "srv/e.txt,rami,rami",
        "../x,rami,rami,0644",
        "/etc/passwd,rami,rami,0644",
        "srv/*.txt,rami,rami,0644",
        "srv/f.txt,rami,rami,0644",
        "srv/f.txt,tech9,tech9,0644",
        "\"unclosed,rami,rami,0644"
      )
    );
    String all = String.join("\n", parsed.problems());
    assertEquals(1, parsed.rules().size(), all);
    assertTrue(all.contains("line 1: \"Rami\" is not a valid owner name"), all);
    assertTrue(all.contains("line 2: \"ro oot\" is not a valid group name"), all);
    assertTrue(all.contains("line 3: \"0999\" is not a valid mode"), all);
    assertTrue(all.contains("line 4: \"64\" is not a valid mode"), all);
    assertTrue(all.contains("line 5: expected path,owner,group,mode"), all);
    assertTrue(all.contains("line 6: the path ../x contains \"..\""), all);
    assertTrue(all.contains("line 7: the path /etc/passwd is under /etc/passwd"), all);
    assertTrue(all.contains("line 8: only a trailing /** is supported"), all);
    assertTrue(all.contains("line 10: the path srv/f.txt repeats line 9"), all);
    assertTrue(all.contains("line 11: expected path,owner,group,mode"), all);
  }

  @Test
  void rootAndSymbolicOwnersAreValidNames() {
    Parsed parsed = TerminalManifest.parse("a,root,root,0644\nb,_svc-1,www_data,4755\n");
    assertEquals(List.of(), parsed.problems());
    assertEquals(04755, parsed.rules().get(1).mode());
  }

  // ---- the optional mtime column ----------------------------------------------------------------------

  private static final long MARCH_14 = java.time.Instant.parse("2026-03-14T09:26:00Z").getEpochSecond();

  @Test
  void theFifthColumnIsAUtcTimeAndAFourColumnManifestStaysValid() {
    Parsed parsed = TerminalManifest.parse(
      "path,owner,group,mode,mtime\n"
        + "srv/a.txt,rami,rami,0644,2026-03-14T09:26:00Z\n"
        + "srv/b.txt,rami,rami,0644,\n"
        + "srv/c.txt,rami,rami,0644\n"
        + "srv/d.txt,rami,rami,0644, 1970-01-01T00:00:00Z \n"
        + "srv/e.txt,rami,rami,0644,2099-12-31T23:59:59Z\n"
    );
    assertEquals(List.of(), parsed.problems());
    assertEquals(MARCH_14, parsed.rules().get(0).mtime());
    assertNull(parsed.rules().get(1).mtime(), "blank keeps the fallback");
    assertNull(parsed.rules().get(2).mtime(), "four columns: no date");
    assertEquals(0L, parsed.rules().get(3).mtime());
    assertEquals(4_102_444_799L, parsed.rules().get(4).mtime());
    assertEquals(List.of(), TerminalManifest.parse("path,owner,group,mode\nsrv/a,rami,rami,0644\n").problems());
  }

  @Test
  void invalidDatesAndDatesOutside1970To2099AreProblemsListedWithTheOthers() {
    Parsed parsed = TerminalManifest.parse(
      String.join(
        "\n",
        "srv/a,rami,rami,0644,2026-03-14",
        "srv/b,rami,rami,0644,2026-03-14T09:26:00",
        "srv/c,rami,rami,0644,2026-03-14 09:26:00Z",
        "srv/d,rami,rami,0644,2026-13-14T09:26:00Z",
        "srv/e,rami,rami,0644,2026-02-30T09:26:00Z",
        "srv/f,rami,rami,0644,1969-12-31T23:59:59Z",
        "srv/g,rami,rami,0644,2100-01-01T00:00:00Z",
        "srv/h,rami,rami,0644,2026-03-14T09:26:00+01:00",
        "srv/i,Rami,rami,0644,2026-03-14T09:26:00Z",
        "srv/j,rami,rami,0644,2026-03-14T09:26:00Z,extra"
      )
    );
    String all = String.join("\n", parsed.problems());
    assertEquals(0, parsed.rules().size(), all);
    for (int line = 1; line <= 8; line++) assertTrue(all.contains("line " + line + ": \""), "line " + line + ": " + all);
    assertTrue(all.contains("is not a valid date (UTC like 2026-03-14T09:26:00Z, years 1970 to 2099)"), all);
    assertTrue(all.contains("line 9: \"Rami\" is not a valid owner name"), "other problems are listed too: " + all);
    assertTrue(all.contains("line 10: expected path,owner,group,mode[,mtime]"), all);
  }

  @Test
  void theMostSpecificRuleWinsWholesaleAndABlankDateIsNotInherited() {
    Parsed parsed = TerminalManifest.parse(
      "srv/**,rami,rami,0644,2026-03-14T09:26:00Z\n"
        + "srv/rami/**,rami,rami,0644,2026-04-01T00:00:00Z\n"
        + "srv/rami/blank.txt,rami,rami,0644,\n"
        + "srv/rami/exact.txt,rami,rami,0644,2026-05-01T12:00:00Z\n"
    );
    assertEquals(List.of(), parsed.problems());
    assertEquals(MARCH_14, TerminalManifest.match(parsed.rules(), "srv/other.txt").orElseThrow().mtime());
    assertEquals(java.time.Instant.parse("2026-04-01T00:00:00Z").getEpochSecond(), TerminalManifest.match(parsed.rules(), "srv/rami/a.txt").orElseThrow().mtime());
    assertNull(TerminalManifest.match(parsed.rules(), "srv/rami/blank.txt").orElseThrow().mtime(), "the exact rule decides, even with a blank date");
    assertEquals(java.time.Instant.parse("2026-05-01T12:00:00Z").getEpochSecond(), TerminalManifest.match(parsed.rules(), "srv/rami/exact.txt").orElseThrow().mtime());
  }

  @Test
  void parseTimeAcceptsOnlyTheUtcFormat() {
    assertEquals(MARCH_14, TerminalManifest.parseTime("2026-03-14T09:26:00Z"));
    assertNull(TerminalManifest.parseTime("2026-03-14T09:26Z"));
    assertNull(TerminalManifest.parseTime("2026-03-14T09:26:00.5Z"));
    assertNull(TerminalManifest.parseTime(""));
    assertNull(TerminalManifest.parseTime("yesterday"));
  }
}
