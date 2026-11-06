package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalAccountsTest {

  @Test
  void idsStartAt2000InNameOrderWhateverTheInputOrder() {
    TerminalAccounts first = TerminalAccounts.of(List.of("tech9", "rami", "root", "rami"), List.of("ops", "root", "rami"));
    TerminalAccounts second = TerminalAccounts.of(List.of("rami", "root", "tech9"), List.of("rami", "ops"));
    assertEquals(first.passwdText(), second.passwdText());
    assertEquals(first.groupText(), second.groupText());
    assertEquals(2000, first.uid("rami"));
    assertEquals(2001, first.uid("tech9"));
    assertEquals(0, first.uid("root"));
    assertEquals(0, first.gid("root"));
    assertEquals(2000, first.gid("ops"));
    assertEquals(2001, first.gid("rami"));
    assertEquals(2002, first.gid("tech9"));
  }

  @Test
  void theTextsAreReadyToAppendToPasswdAndGroup() {
    TerminalAccounts accounts = TerminalAccounts.of(List.of("rami"), List.of("ops"));
    assertEquals("rami:x:2000:2001:rami:/:/bin/sh\n", accounts.passwdText());
    assertEquals("ops:x:2000:\nrami:x:2001:\n", accounts.groupText());
    assertEquals("rami", accounts.ownersText());
  }

  @Test
  void aPackageOwnedByRootAddsNothing() {
    TerminalAccounts accounts = TerminalAccounts.of(List.of("root"), List.of("root"));
    assertEquals("", accounts.passwdText());
    assertEquals("", accounts.groupText());
    assertEquals("", accounts.ownersText());
  }
}
