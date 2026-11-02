package dev.devfest.event.dto;

/**
 * A setup problem on the admin board; {@code link} is the frontend route where it is fixed. {@code blocking}: the
 * round's timer starts only with "Start anyway" while it exists.
 */
public record AdminAlert(String code, String message, String link, boolean blocking) {
  public AdminAlert(String code, String message, String link) {
    this(code, message, link, false);
  }
}
