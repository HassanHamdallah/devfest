package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.auth.RateLimit;
import dev.devfest.event.dto.StreamTicketView;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Single-use tickets for GET /event/stream, so no JWT ever appears in a URL. A ticket is 256 random bits, lives
 * 30 seconds, is bound to the caller's account and session, and is kept only in this server's memory.
 */
@Service
public class StreamTicketService {

  public static final Duration TICKET_LIFETIME = Duration.ofSeconds(30);
  /** Tickets per account per minute; a healthy tab needs one per 30-minute stream. */
  static final int TICKETS_PER_MINUTE = 30;

  /** Who a consumed ticket was issued to. */
  public record StreamOwner(long userId, UUID session) {}

  private record Ticket(StreamOwner owner, Instant expiresAt) {}

  private final EventAccessPolicy access;
  private final RateLimit rateLimit;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

  public StreamTicketService(EventAccessPolicy access, RateLimit rateLimit, Clock clock) {
    this.access = access;
    this.rateLimit = rateLimit;
    this.clock = clock;
  }

  /** 1 SQL statement (the rate limit). */
  public StreamTicketView issue(Principal principal) {
    access.requireEventRole(principal);
    rateLimit.check(
      "event-stream-ticket:" + principal.id(),
      TICKETS_PER_MINUTE,
      "Too many live update connections. Please wait a minute."
    );
    Instant now = clock.instant();
    tickets.values().removeIf(ticket -> !ticket.expiresAt().isAfter(now));
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tickets.put(id, new Ticket(new StreamOwner(principal.id(), principal.session()), now.plus(TICKET_LIFETIME)));
    return new StreamTicketView(id, TICKET_LIFETIME.toSeconds());
  }

  /** No SQL. A missing, unknown, used or expired ticket is a 401; the first use removes the ticket either way. */
  public StreamOwner consume(String ticket) {
    Ticket found = ticket == null ? null : tickets.remove(ticket);
    if (found == null || !found.expiresAt().isAfter(clock.instant())) throw fail(401, "Please sign in again.");
    return found.owner();
  }
}
