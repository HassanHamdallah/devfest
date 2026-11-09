package dev.devfest.event.domain;

import java.math.BigDecimal;

/** The only two point deductions of the event; nothing is ever added to a team's time. */
public enum DeductionKind {
  HINT(new BigDecimal("2")),
  CHEATING(new BigDecimal("7.5"));

  /** A team may use this many hints in total, across all four rooms. */
  public static final int MAX_HINTS_PER_TEAM = 2;

  private final BigDecimal points;

  DeductionKind(BigDecimal points) {
    this.points = points;
  }

  public BigDecimal points() {
    return points;
  }
}
