package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.auth.RateLimit;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The shared opening of every idempotent team POST (contract §2, steps 1–3 plus the request hash).
 * Must be called inside the caller's write transaction so the locks are held until it commits.
 */
@Component
class TeamWriteGate {

  static final int TEAM_WRITES_PER_MINUTE = 20;

  /** The locked team, the locked event state and the hash that identifies this exact request. */
  record TeamWrite(EventTeamRecord team, EventStateRecord state, String requestHash) {}

  private final EventAccessPolicy access;
  private final RateLimit rateLimit;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final ObjectMapper json;

  TeamWriteGate(
    EventAccessPolicy access,
    RateLimit rateLimit,
    EventStateRepository states,
    EventTeamRepository teams,
    ObjectMapper json
  ) {
    this.access = access;
    this.rateLimit = rateLimit;
    this.states = states;
    this.teams = teams;
    this.json = json;
  }

  /** {@code discriminator} separates request kinds that could share a body shape (room id, "recovery", ...). */
  TeamWrite open(Principal principal, String discriminator, Object body) {
    EventTeamRecord team = access.requireTeamOf(principal);
    rateLimit.check("event-team:" + team.id(), TEAM_WRITES_PER_MINUTE, "Too many attempts. Wait a moment and try again.");
    EventStateRecord state = states.lockForUpdate();
    EventTeamRecord locked = teams
      .lockForUpdate(team.id())
      .filter(EventTeamRecord::active)
      .orElseThrow(() -> fail(409, "Your team is not active."));
    return new TeamWrite(locked, state, AuthService.hash(discriminator + ":" + json.writeValueAsString(body)));
  }

  /** Checked right after the replay lookup: a disqualified team keeps its receipts but cannot add anything. */
  static void requireNotDisqualified(TeamWrite write) {
    if (write.team().disqualified()) throw fail(409, "Your team has been disqualified. Contact the organizer.");
  }

  /** A stored record with this key is a replay only if it was created by the very same request. */
  static void requireSameRequest(String storedHash, TeamWrite write) {
    if (!storedHash.equals(write.requestHash())) {
      throw fail(409, "This Idempotency-Key was already used for a different request.");
    }
  }
}
