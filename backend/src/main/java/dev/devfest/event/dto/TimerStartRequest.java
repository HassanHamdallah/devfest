package dev.devfest.event.dto;

/** {@code anyway}: start although the board lists blocking alerts (the admin pressed "Start anyway"). */
public record TimerStartRequest(Boolean anyway) {
  public boolean startAnyway() {
    return Boolean.TRUE.equals(anyway);
  }
}
