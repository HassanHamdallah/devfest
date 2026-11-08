package dev.devfest.event.service;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService;
import dev.devfest.event.domain.MissionEvaluationCause;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.sentinel.SentinelContent;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.SentinelEvaluationRecord;
import dev.devfest.event.repository.SentinelEvaluationRepository;
import dev.devfest.event.repository.SentinelWorkRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Evaluates each team once per AI room (rules §19.6): when it submits its last stage, or at the round's end for
 * teams that did not finish. A total of at least the room's pass score is recorded through the room-submission
 * path as CORRECT (collecting the room's clue), anything less as INCORRECT. No scheduler: both callers already
 * hold the event_state lock, so the two triggers run one after the other, and the second finds the evaluation of
 * the first (sentinel_evaluation's primary key backs this up). Callers bump the revision, which is broadcast after
 * commit.
 */
@Service
public class AiRoomEvaluationService {

  /** The stored answer text of an evaluation: never the score, which only organizers see. */
  static final String RESULT_TEXT = "AI mission result";
  private static final String REQUEST_HASH = AuthService.hash("ai-mission-evaluation");

  private final EventSubmissionService submissions;
  private final EventRoomRepository rooms;
  private final EventTeamRepository teams;
  private final TeamRoomExceptionRepository exceptions;
  private final SentinelWorkRepository work;
  private final SentinelEvaluationRepository evaluations;
  private final AuditLog audit;

  public AiRoomEvaluationService(
    EventSubmissionService submissions,
    EventRoomRepository rooms,
    EventTeamRepository teams,
    TeamRoomExceptionRepository exceptions,
    SentinelWorkRepository work,
    SentinelEvaluationRepository evaluations,
    AuditLog audit
  ) {
    this.submissions = submissions;
    this.rooms = rooms;
    this.teams = teams;
    this.exceptions = exceptions;
    this.work = work;
    this.evaluations = evaluations;
    this.audit = audit;
  }

  /**
   * Evaluates the writing team unless it already was. The caller holds the event_state, team and room locks.
   * Returns true when this call recorded the evaluation.
   */
  boolean evaluate(long actorId, TeamWrite write, EventRoomRecord room, MissionEvaluationCause cause, Instant now) {
    if (evaluations.find(write.team().id(), room.id()).isPresent()) return false;
    record(actorId, new TeamWrite(write.team(), write.state(), REQUEST_HASH), room, cause, now);
    return true;
  }

  /**
   * The round's end (Time's up by End round or removed time, or the next round): every active, not disqualified
   * team assigned to an AI mission room this round that is not evaluated yet. The caller holds the event_state lock.
   * Returns the number of teams evaluated.
   */
  int evaluateRoundEnd(long actorId, EventStateRecord state, Instant now) {
    int round = state.currentRound();
    if (!Rotation.isRunningRound(round)) return 0;
    List<EventRoomRecord> missionRooms = rooms.findAll().stream().filter(EventRoomRecord::hasMission).toList();
    if (missionRooms.isEmpty()) return 0;
    Map<Long, RoomCode> roundExceptions = exceptions.findForRound(round);
    List<EventTeamRecord> active = teams.findActive();
    int evaluated = 0;
    for (EventRoomRecord missionRoom : missionRooms) {
      Set<Long> done = evaluations.teamIds(missionRoom.id());
      List<Long> due = active
        .stream()
        .filter(team -> !team.disqualified() && !done.contains(team.id()))
        .filter(team -> RoomAssignment.isIn(missionRoom.code(), team.groupCode(), round, Optional.ofNullable(roundExceptions.get(team.id()))))
        .map(EventTeamRecord::id)
        .toList();
      if (due.isEmpty()) continue;
      // Lock order event_state → teams → room, as on every team write.
      List<EventTeamRecord> locked = teams.lockForUpdate(due);
      EventRoomRecord room = rooms.lockForUpdate(missionRoom.id()).orElseThrow();
      for (EventTeamRecord team : locked) {
        record(actorId, new TeamWrite(team, state, REQUEST_HASH), room, MissionEvaluationCause.TIME_UP, now);
        evaluated++;
      }
    }
    return evaluated;
  }

  private void record(long actorId, TeamWrite write, EventRoomRecord room, MissionEvaluationCause cause, Instant now) {
    long teamId = write.team().id();
    SentinelWorkRepository.Summary summary = work.summary(teamId, room.id());
    // An unsubmitted stage is worth 0, so the total is just the sum of what was saved; passing is total >= pass score.
    boolean passed = summary.total().compareTo(BigDecimal.valueOf(room.quizPassScore())) >= 0;
    String progress = "AI mission · stages %d/%d submitted".formatted(summary.submitted(), SentinelContent.STAGE_COUNT);
    // A random key: a fixed one could be taken first by one of the team's own submissions and make this insert fail.
    RoomSubmissionRecord submission = submissions.recordActivityResult(write, room, UUID.randomUUID(), progress, RESULT_TEXT, passed);
    evaluations.insert(
      new SentinelEvaluationRecord(
        teamId,
        room.id(),
        write.state().currentRound(),
        submission.id(),
        summary.total(),
        room.quizPassScore(),
        passed,
        cause,
        now,
        passed
      )
    );
    audit.record(actorId, "EVENT_MISSION_EVALUATED", "room:" + room.id() + " team:" + teamId + " cause:" + cause + " passed:" + passed);
  }
}
