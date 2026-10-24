package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.PenaltyRules;
import dev.devfest.event.dto.PenaltyRequest;
import dev.devfest.event.dto.PenaltyView;
import dev.devfest.event.dto.VoidPenaltyRequest;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.PenaltyRecord;
import dev.devfest.event.repository.PenaltyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manually entered penalties and voids. Penalties are never deleted; a void happens at most once. */
@Service
public class EventPenaltyService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final PenaltyRepository penalties;
  private final AuditLog audit;
  private final EventClock clock;

  public EventPenaltyService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    PenaltyRepository penalties,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.penalties = penalties;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public PenaltyView add(Principal principal, long roomId, PenaltyRequest request) {
    access.requireManagerOf(principal, roomId);
    PenaltyRules.requireManagerEntered(request.kind());
    requireMaxBytes(request.note(), 500, "Note");
    states.lockForUpdate();
    teams.lockForUpdate(request.teamId()).orElseThrow(() -> fail(404, "Team not found."));
    rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    long penaltyId = penalties.insert(request.teamId(), roomId, request.kind(), request.note(), principal.id(), clock.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_PENALTY_ADDED", "penalty:" + penaltyId);
    return EventViews.penalty(penalties.find(penaltyId).orElseThrow());
  }

  @Transactional
  public PenaltyView voidPenalty(Principal principal, long penaltyId, VoidPenaltyRequest request) {
    PenaltyRecord penalty = access.requireVoidablePenalty(principal, penaltyId);
    requireMaxBytes(request.reason(), 500, "Void reason");
    states.lockForUpdate();
    teams.lockForUpdate(penalty.teamId());
    if (penalty.roomId() != null) rooms.lockForUpdate(penalty.roomId());
    if (!penalties.voidPenalty(penaltyId, principal.id(), clock.now(), request.reason())) {
      throw fail(409, "This penalty is already voided.");
    }
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_PENALTY_VOIDED", "penalty:" + penaltyId);
    return EventViews.penalty(penalties.find(penaltyId).orElseThrow());
  }
}
