package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.TerminalManifest.Parsed;
import dev.devfest.event.domain.TerminalManifest.Rule;
import java.util.List;
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
}
