package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;
import static dev.devfest.event.service.EventText.requireReason;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.DeductionKind;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.dto.CancelDeductionRequest;
import dev.devfest.event.dto.CheatingRequest;
import dev.devfest.event.dto.DeductionView;
import dev.devfest.event.dto.HintRequest;
import dev.devfest.event.repository.DeductionRecord;
import dev.devfest.event.repository.DeductionRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hints (-2) and cheating violations (-7.5): point deductions only, nothing is ever added to a team's time and they
 * never change clues or qualification. The room's manager records and cancels them for a team that is in the room now;
 * the admin may do both for any team and room. The log is append-only: a cancelled row stays with who cancelled it,
 * when and why. A team has 2 hints in total across all four rooms; a room holds at most one active cheating violation
 * per team. Lock order: event_state, team, room (the standard event order).
 */
@Service
public class EventDeductionService {

  static final String HINTS_USED = "This team has already used its 2 hints.";
  static final String CHEATING_EXISTS = "This team already has an active cheating violation in this room.";
  static final String NOT_IN_ROOM = "Team not found.";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final TeamRoomExceptionRepository exceptions;
  private final DeductionRepository deductions;
  private final AuditLog audit;
  private final EventClock clock;

  public EventDeductionService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    TeamRoomExceptionRepository exceptions,
    DeductionRepository deductions,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.exceptions = exceptions;
    this.deductions = deductions;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public DeductionView recordHint(Principal principal, long roomId, HintRequest request) {
    access.requireManagerOf(principal, roomId);
    String note = request.note() == null ? "" : request.note().trim();
    requireMaxBytes(note, 500, "Note");
    Locked locked = lock(principal, roomId, request.teamId());
    if (deductions.activeHints(locked.team().id()) >= DeductionKind.MAX_HINTS_PER_TEAM) throw fail(409, HINTS_USED);
    long id = deductions.insert(locked.team().id(), roomId, DeductionKind.HINT, note, principal.id(), clock.now());
    return changed(principal, "EVENT_HINT_GIVEN", id);
  }

  @Transactional
  public DeductionView recordCheating(Principal principal, long roomId, CheatingRequest request) {
    access.requireManagerOf(principal, roomId);
    if (!Boolean.TRUE.equals(request.confirmed())) throw fail(400, "Confirm the cheating violation first.");
    String reason = requireReason(request.reason());
    Locked locked = lock(principal, roomId, request.teamId());
    if (deductions.activeCheating(locked.team().id(), roomId)) throw fail(409, CHEATING_EXISTS);
    long id = deductions.insert(locked.team().id(), roomId, DeductionKind.CHEATING, reason, principal.id(), clock.now());
    return changed(principal, "EVENT_CHEATING_RECORDED", id);
  }

  /** Cancelling keeps the row in the log and restores the hint or the points. */
  @Transactional
  public DeductionView cancel(Principal principal, long deductionId, CancelDeductionRequest request) {
    boolean admin = EventAccessPolicy.ADMIN.equals(principal.role());
    if (!admin && !EventAccessPolicy.MANAGER.equals(principal.role())) throw fail(403, "Access denied.");
    String reason = requireReason(request.reason());
    DeductionRecord found = deductions.find(deductionId).orElseThrow(() -> fail(404, "Deduction not found."));
    if (!admin) {
      boolean ownRoom = rooms.findByManager(principal.id()).filter(room -> room.id() == found.roomId()).isPresent();
      if (!ownRoom) throw fail(404, "Deduction not found.");
    }
    states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(found.teamId()).orElseThrow(() -> fail(404, "Deduction not found."));
    EventRoomRecord room = rooms.lockForUpdate(found.roomId()).orElseThrow(() -> fail(404, "Deduction not found."));
    if (!admin) requireTeamInRoom(states.state(), team, room, "Deduction not found.");
    if (!deductions.cancel(deductionId, principal.id(), clock.now(), reason)) {
      throw fail(409, "This deduction is already cancelled.");
    }
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_DEDUCTION_CANCELLED", "deduction:" + deductionId, reason);
    return EventViews.deduction(deductions.find(deductionId).orElseThrow());
  }

  /** The room's log, newest first (the room's manager or the admin). */
  public List<DeductionView> roomLog(Principal principal, long roomId) {
    access.requireManagerOf(principal, roomId);
    return deductions.findForRoom(roomId).stream().map(EventViews::deduction).toList();
  }

  /** The whole log, newest first (admin only). */
  public List<DeductionView> fullLog(Principal principal) {
    access.requireAdmin(principal);
    return deductions.findAll().stream().map(EventViews::deduction).toList();
  }

  // ---- shared -----------------------------------------------------------------------------------

  private record Locked(EventTeamRecord team, EventRoomRecord room) {}

  /** Locks state, team, room; a manager may act only for a team that is in their room now. */
  private Locked lock(Principal principal, long roomId, long teamId) {
    EventStateRecord state = states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).filter(EventTeamRecord::active).orElseThrow(() -> fail(404, NOT_IN_ROOM));
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (!EventAccessPolicy.ADMIN.equals(principal.role())) requireTeamInRoom(state, team, room, NOT_IN_ROOM);
    return new Locked(team, room);
  }

  private void requireTeamInRoom(EventStateRecord state, EventTeamRecord team, EventRoomRecord room, String message) {
    Optional<dev.devfest.event.domain.RoomCode> exception = exceptions.find(state.currentRound(), team.id());
    if (!RoomAssignment.isIn(room.code(), team.groupCode(), state.currentRound(), exception)) throw fail(404, message);
  }

  private DeductionView changed(Principal principal, String action, long deductionId) {
    states.bumpRevision();
    audit.record(principal.id(), action, "deduction:" + deductionId);
    return EventViews.deduction(deductions.find(deductionId).orElseThrow());
  }
}
