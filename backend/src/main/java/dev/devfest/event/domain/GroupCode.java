package dev.devfest.event.domain;

/** Team groups; the index (A=0 … D=3) drives the room rotation. */
public enum GroupCode {
  A,
  B,
  C,
  D;

  public int index() {
    return ordinal();
  }

  public static GroupCode fromIndex(int index) {
    if (index < 0 || index >= values().length) {
      throw new IllegalArgumentException("Group index must be 0..3, was " + index);
    }
    return values()[index];
  }
}
