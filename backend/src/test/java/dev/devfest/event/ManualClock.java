package dev.devfest.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock tests move by hand; {@link Config} makes it the clock of every bean in the test context. */
final class ManualClock extends Clock {

  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));

  void advance(Duration duration) {
    now.updateAndGet(instant -> instant.plus(duration));
  }

  void reset() {
    now.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  /** Import on a test class; classes importing it share one Spring context. */
  @TestConfiguration
  static class Config {

    @Bean
    @Primary
    ManualClock manualClock() {
      return new ManualClock();
    }
  }
}
