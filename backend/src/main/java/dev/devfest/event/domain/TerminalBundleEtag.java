package dev.devfest.event.domain;

/** The HTTP validator of a room's server package: its SHA-256 as a strong ETag. */
public final class TerminalBundleEtag {

  private TerminalBundleEtag() {}

  /** The header value for a digest: {@code "<sha256>"} with the quotes. */
  public static String of(String sha256) {
    return "\"" + sha256 + "\"";
  }

  /**
   * True when an If-None-Match header lists the digest (weak validators and a lone {@code *} included), so the
   * caller's copy is current and the archive does not need to be sent.
   */
  public static boolean matches(String ifNoneMatch, String sha256) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
    for (String candidate : ifNoneMatch.split(",")) {
      String tag = candidate.strip();
      if (tag.equals("*")) return true;
      if (tag.startsWith("W/")) tag = tag.substring(2);
      if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) tag = tag.substring(1, tag.length() - 1);
      if (tag.equals(sha256)) return true;
    }
    return false;
  }
}
