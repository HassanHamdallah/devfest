package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.dto.JudgementRequest;
import dev.devfest.event.dto.JudgementView;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.RoomJudgementRecord;
import dev.devfest.event.repository.RoomJudgementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PASS/FAIL decisions per team and room, optimistic on the judgement version. */
@Service
public class EventJudgingService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final RoomJudgementRepository judgements;
  private final AuditLog audit;
  private final EventClock clock;

  public EventJudgingService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    RoomJudgementRepository judgements,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.judgements = judgements;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * A request equal to the stored decision is a successful no-op whatever version it carries, so a retried
   * click never fails and never adds history. Otherwise the version must match and exactly one history row is written.
   */
  @Transactional
  public JudgementView judge(Principal principal, long roomId, long teamId, JudgementRequest request) {
    access.requireManagerOf(principal, roomId);
    requireMaxBytes(request.note(), 1000, "Note");
    states.lockForUpdate();
    teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    RoomJudgementRecord current = judgements.find(teamId, roomId);
    if (current.sameDecision(request.status(), request.note())) return EventViews.judgement(current);
    if (request.version() != current.version()) {
      throw fail(409, "Someone else changed this judgement. Reload and try again.");
    }
    judgements.saveWithHistory(current, request.status(), request.note(), principal.id(), clock.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_JUDGEMENT", "team:" + teamId + ":room:" + roomId + ":" + request.status());
    return EventViews.judgement(judgements.find(teamId, roomId));
  }
}
