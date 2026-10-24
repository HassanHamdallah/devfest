package dev.devfest.event.domain;

/**
 * A request that breaks a fixed event rule regardless of stored state (answered with 400 and this message).
 * Keeps the domain free of the HTTP error types.
 */
public class EventRuleException extends RuntimeException {

  public EventRuleException(String message) {
    super(message);
  }
}
