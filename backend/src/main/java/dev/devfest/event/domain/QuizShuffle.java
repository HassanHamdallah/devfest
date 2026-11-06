package dev.devfest.event.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-team orders of the AI room (rules §19.4): stable for one team (a reload shows the same order), different
 * between teams, and unpredictable without the server secret. Snippets never start in their correct order.
 */
public final class QuizShuffle {

  /** What is being ordered; part of the seed so two kinds of item with the same id get unrelated orders. */
  public enum Kind {
    SECTION,
    OPTIONS,
    SNIPPETS,
  }

  private QuizShuffle() {}

  /** HMAC-SHA256(secret, kind:team:item), first 8 bytes. */
  public static long seed(byte[] secret, Kind kind, long teamId, long itemId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] digest = mac.doFinal((kind + ":" + teamId + ":" + itemId).getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }

  /** A Fisher–Yates shuffle of a copy of {@code items}, fully determined by {@code seed}. */
  public static <T> List<T> shuffle(List<T> items, long seed) {
    List<T> copy = new ArrayList<>(items);
    Collections.shuffle(copy, new SplittableRandom(seed));
    return copy;
  }

  /**
   * Like {@link #shuffle}, but never the given order: {@code correctOrder} is the correct order, so a team that
   * submits what it was shown never scores full marks by doing nothing. A shuffle that comes out unchanged is
   * rotated by one place (lists of 0 or 1 items are returned as they are).
   */
  public static <T> List<T> shuffleAwayFrom(List<T> correctOrder, long seed) {
    List<T> shuffled = shuffle(correctOrder, seed);
    if (shuffled.size() > 1 && shuffled.equals(correctOrder)) Collections.rotate(shuffled, -1);
    return shuffled;
  }
}
