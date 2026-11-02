package dev.devfest.event.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AiRoomShuffleSecretTest {

  private static final String SECRET = "a-quiz-shuffle-secret-of-forty-characters";

  @Test
  void aConfiguredSecretIsUsedInEveryMode() {
    byte[] production = new AiRoomShuffleSecret(SECRET, false).bytes();
    assertArrayEquals(production, new AiRoomShuffleSecret(SECRET, true).bytes());
    assertFalse(Arrays.equals(production, new AiRoomShuffleSecret("another-quiz-shuffle-secret-of-forty-chars", false).bytes()));
  }

  @Test
  void developmentFallsBackWithoutASecretAndProductionRefusesToStart() {
    byte[] fallback = new AiRoomShuffleSecret("", true).bytes();
    assertArrayEquals(fallback, new AiRoomShuffleSecret("too-short", true).bytes(), "a short value counts as missing");
    assertFalse(Arrays.equals(fallback, new AiRoomShuffleSecret(SECRET, true).bytes()));
    IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new AiRoomShuffleSecret("", false));
    assertEquals(AiRoomShuffleSecret.MISSING, refused.getMessage());
    assertThrows(IllegalStateException.class, () -> new AiRoomShuffleSecret("too-short", false));
  }
}
