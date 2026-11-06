package dev.devfest.event.service;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The server secret behind each team's question, option and snippet order (rules §19.4), from
 * DEVFEST_QUIZ_SHUFFLE_SECRET in backend/.env. Without it a development server uses a fixed fallback and warns
 * (orders are then predictable from the public source); any other server refuses to start (StartupSafety checks
 * the same before the database is touched).
 */
@Component
public class AiRoomShuffleSecret {

  public static final int MIN_LENGTH = 32;
  public static final String MISSING = "Set DEVFEST_QUIZ_SHUFFLE_SECRET (at least 32 characters) in backend/.env";
  static final String DEVELOPMENT_FALLBACK = "devfest-development-only-quiz-order-secret";
  private static final Logger log = LoggerFactory.getLogger(AiRoomShuffleSecret.class);

  private final byte[] key;

  public AiRoomShuffleSecret(
    @Value("${app.quiz-shuffle-secret:}") String secret,
    @Value("${app.development:false}") boolean development
  ) {
    String value = secret;
    if (!isSet(secret)) {
      if (!development) throw new IllegalStateException(MISSING);
      log.warn("{} before the event. Using the development fallback: question and snippet orders are predictable.", MISSING);
      value = DEVELOPMENT_FALLBACK;
    }
    // A label keeps these seeds unrelated to any other use of the same value.
    this.key = ("devfest-quiz-order:" + value).getBytes(StandardCharsets.UTF_8);
  }

  public static boolean isSet(String secret) {
    return secret != null && secret.strip().length() >= MIN_LENGTH;
  }

  byte[] bytes() {
    return key.clone();
  }
}
