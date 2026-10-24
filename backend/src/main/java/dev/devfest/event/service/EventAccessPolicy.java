package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.PenaltyRecord;
import dev.devfest.event.repository.PenaltyRepository;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Every event authorization decision. Wrong role → 403; a room or team that is not the caller's → 404,
 * so foreign ids are indistinguishable from unknown ones. Reads only; never takes row locks.
 */
@Service
public class EventAccessPolicy {

  public static final String ADMIN = "ADMIN";
  public static final String MANAGER = "MANAGER";
  public static final String MEMBER = "MEMBER";

  private static final Set<String> EVENT_ROLES = Set.of(ADMIN, MANAGER, MEMBER);

  private final EventRoomRepository rooms;
  private final EventTeamRepository teams;
  private final PenaltyRepository penalties;

  public EventAccessPolicy(EventRoomRepository rooms, EventTeamRepository teams, PenaltyRepository penalties) {
    this.rooms = rooms;
    this.teams = teams;
    this.penalties = penalties;
  }

  public void requireAdmin(Principal principal) {
    if (!ADMIN.equals(principal.role())) throw fail(403, "Access denied.");
  }

  public void requireEventRole(Principal principal) {
    if (!EVENT_ROLES.contains(principal.role())) throw fail(403, "Access denied.");
  }

  /** ADMIN may open any room; a MANAGER only the room assigned to them. */
  public EventRoomRecord requireManagerOf(Principal principal, long roomId) {
    boolean admin = ADMIN.equals(principal.role());
    if (!admin && !MANAGER.equals(principal.role())) throw fail(403, "Access denied.");
    EventRoomRecord room = rooms.find(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (!admin && !Long.valueOf(principal.id()).equals(room.managerId())) throw fail(404, "Room not found.");
    return room;
  }

  /** Results are for ADMIN and MEMBER; manager accounts never see the ranking. */
  public void requireResultsViewer(Principal principal) {
    if (!ADMIN.equals(principal.role()) && !MEMBER.equals(principal.role())) throw fail(403, "Access denied.");
  }

  /**
   * ADMIN may void any penalty; a MANAGER only one recorded in the room they manage. Penalties without a
   * room (wrong accusations) and other rooms' penalties look unknown to a manager.
   */
  public PenaltyRecord requireVoidablePenalty(Principal principal, long penaltyId) {
    boolean admin = ADMIN.equals(principal.role());
    if (!admin && !MANAGER.equals(principal.role())) throw fail(403, "Access denied.");
    PenaltyRecord penalty = penalties.find(penaltyId).orElseThrow(() -> fail(404, "Penalty not found."));
    if (admin) return penalty;
    boolean ownRoom = penalty.roomId() != null && rooms
      .findByManager(principal.id())
      .filter(room -> room.id() == penalty.roomId())
      .isPresent();
    if (!ownRoom) throw fail(404, "Penalty not found.");
    return penalty;
  }

  /** The caller's own active team; team ids are never taken from the client. */
  public EventTeamRecord requireTeamOf(Principal principal) {
    if (!MEMBER.equals(principal.role())) throw fail(403, "A team account is required.");
    EventTeamRecord team = teams
      .findByAccount(principal.id())
      .orElseThrow(() -> fail(409, "Your account is not linked to a team yet. Ask an organizer."));
    if (!team.active()) throw fail(409, "Your team is not active.");
    return team;
  }
}
