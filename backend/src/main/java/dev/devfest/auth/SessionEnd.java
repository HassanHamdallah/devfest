package dev.devfest.auth;

/**
 * Why a session can no longer be used. Every value except EXPIRED is stored in auth_session.revoked_reason;
 * EXPIRED covers sessions that ran out, unknown sessions and invalid tokens. The message is what the
 * signed-out device shows; it never names another device or address.
 */
public enum SessionEnd {
  LOGOUT("You signed out. Please sign in again."),
  REPLACED("Your team signed in on another device. Only one device per team can be signed in."),
  ORGANIZER("An organizer signed your team out."),
  DEACTIVATED("This account was deactivated. Ask an organizer."),
  PASSWORD_RESET("The password was changed. Please sign in again."),
  REPLAY("This session was ended for your security. Please sign in again."),
  EXPIRED("Session expired. Please sign in again.");

  private final String message;

  SessionEnd(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }

  /** A stored revoked_reason; NULL (sessions revoked before V6, or on expiry) reads as EXPIRED. */
  static SessionEnd fromStored(String reason) {
    return reason == null ? EXPIRED : valueOf(reason);
  }
}
