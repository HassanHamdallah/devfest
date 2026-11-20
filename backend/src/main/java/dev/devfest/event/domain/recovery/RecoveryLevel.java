package dev.devfest.event.domain.recovery;

/** A recovery level of the final and how many stages it has (each stage is passed once, in order). */
public enum RecoveryLevel {
  ML(6),
  ARDUINO(1);

  private final int stages;

  RecoveryLevel(int stages) {
    this.stages = stages;
  }

  public int stages() {
    return stages;
  }
}
