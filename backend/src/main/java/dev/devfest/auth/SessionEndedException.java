package dev.devfest.auth;

import dev.devfest.api.Errors.ApiException;

/** The 401 for a request or refresh that used an ended session; the response names the reason. */
public class SessionEndedException extends ApiException {

  /** Request attribute the security filter sets so the 401 entry point can name the reason. */
  public static final String ATTRIBUTE = "devfest.sessionEnd";

  public final SessionEnd reason;

  public SessionEndedException(SessionEnd reason) {
    super(401, reason.message());
    this.reason = reason;
  }
}
