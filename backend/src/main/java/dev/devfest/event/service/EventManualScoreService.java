package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoomScoring;
import dev.devfest.event.dto.ManualScoreRequest;
import dev.devfest.event.dto.ManualScoreView;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.ManualScoreRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A temporary manual score (0 to 15) for a room that has no automatic score (Hardware today). Only the room's manager
 * or the admin enter it; every change is audited with the value. A room that runs an automatic activity (AI mission,
 * Linux terminal, contest) refuses it: its score comes from the activity.
 */
@Service
public class EventManualScoreService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final ManualScoreRepository scores;
  private final AuditLog audit;
  private final EventClock clock;

  public EventManualScoreService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    ManualScoreRepository scores,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.scores = scores;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public ManualScoreView save(Principal principal, long roomId, long teamId, ManualScoreRequest request) {
    access.requireManagerOf(principal, roomId);
    BigDecimal score = request.score();
    if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(RoomScoring.ROOM_MAX) > 0) {
      throw fail(400, "The score must be from 0 to 15.");
    }
    if (score.stripTrailingZeros().scale() > 2) throw fail(400, "The score can have at most 2 decimals.");
    states.lockForUpdate();
    teams.lockForUpdate(teamId).filter(team -> team.active()).orElseThrow(() -> fail(404, "Team not found."));
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (room.activity() != null) throw fail(409, "This room's score comes from its activity; it cannot be typed in.");
    BigDecimal stored = RoomScoring.round(score);
    scores.save(teamId, roomId, stored, principal.id(), clock.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_MANUAL_SCORE", "room:" + roomId + " team:" + teamId + " score:" + stored.toPlainString());
    return new ManualScoreView(teamId, roomId, stored);
  }
}
