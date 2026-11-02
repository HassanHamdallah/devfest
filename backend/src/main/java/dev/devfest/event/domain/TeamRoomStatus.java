package dev.devfest.event.domain;

/** What a team sees about its own progress in one room. */
public enum TeamRoomStatus {
  NOT_STARTED,
  SUBMITTED,
  PASSED,
  FAILED;

  /** A manager verdict wins over submission activity; PENDING falls back to whether anything was submitted. */
  public static TeamRoomStatus of(JudgeStatus judgement, boolean hasSubmission) {
    return switch (judgement) {
      case PASS -> PASSED;
      case FAIL -> FAILED;
      case PENDING -> hasSubmission ? SUBMITTED : NOT_STARTED;
    };
  }
}
