package dev.devfest.event.dto;

/** The answer to a package download: {@code archive} is null when the caller's ETag still matches (304). */
public record TerminalBundleDelivery(String sha256, byte[] archive) {
  public boolean notModified() {
    return archive == null;
  }
}
