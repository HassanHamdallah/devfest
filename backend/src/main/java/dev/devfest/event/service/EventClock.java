package dev.devfest.event.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Server time for the event. Truncated to microseconds so a stored instant reads back identical (TIMESTAMPTZ). */
@Component
public class EventClock {

  private final Clock clock;

  public EventClock(Clock clock) {
    this.clock = clock;
  }

  public Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
