package dev.devfest.event.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * The contest link of a room (rules §20.2): one absolute https URL with a host, at most 500 bytes, no spaces or
 * control characters and no user name or password in it. Anything else (http, javascript:, data:, relative links) is
 * refused, because teams open the link from their page.
 */
public final class ContestUrl {

  public static final int MAX_BYTES = 500;

  private ContestUrl() {}

  /** The reason the text cannot be a contest link, or empty when it can. */
  public static Optional<String> problem(String text) {
    if (text == null || text.isBlank()) return Optional.of("Enter the contest link.");
    if (!text.equals(text.strip())) return Optional.of("The contest link cannot start or end with a space.");
    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
      return Optional.of("The contest link can have at most " + MAX_BYTES + " bytes.");
    }
    if (text.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
      return Optional.of("The contest link cannot contain spaces or control characters.");
    }
    URI uri;
    try {
      uri = new URI(text);
    } catch (URISyntaxException e) {
      return Optional.of("The contest link is not a valid web address.");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) return Optional.of("The contest link must start with https://.");
    if (uri.getHost() == null || uri.getHost().isBlank()) return Optional.of("The contest link needs a host name.");
    if (uri.getUserInfo() != null) return Optional.of("The contest link cannot contain a user name or password.");
    return Optional.empty();
  }
}
