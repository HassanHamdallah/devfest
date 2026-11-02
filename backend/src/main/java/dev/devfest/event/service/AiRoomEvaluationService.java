package dev.devfest.event.service;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService;
import dev.devfest.event.domain.QuizEvaluationCause;
import dev.devfest.event.domain.QuizScoring;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.QuizAnswerRepository;
import dev.devfest.event.repository.QuizEvaluationRecord;
import dev.devfest.event.repository.QuizEvaluationRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Evaluates each team once per AI room (rules §19.6): when it submits its last repair, or at the round's end for
 * teams that did not finish. A total of at least the room's pass score is recorded through the room-submission
 * path as CORRECT (collecting the room's clue), anything less as INCORRECT. No scheduler: both callers already
 * hold the event_state lock, so the two triggers run one after the other, and the second finds the evaluation of
 * the first (quiz_evaluation's primary key backs this up). Callers bump the revision, which is broadcast after
 * commit.
 */
@Service
public class AiRoomEvaluationService {

  /** The stored answer text of an evaluation: never the score, which only organizers see. */
  static final String RESULT_TEXT = "AI quiz result";
  private static final String REQUEST_HASH = AuthService.hash("ai-quiz-evaluation");

  private final EventSubmissionService submissions;
  private final EventRoomRepository rooms;
  private final EventTeamRepository teams;
  private final TeamRoomExceptionRepository exceptions;
  private final QuizAnswerRepository answers;
  private final QuizEvaluationRepository evaluations;
  private final AuditLog audit;

  public AiRoomEvaluationService(
    EventSubmissionService submissions,
    EventRoomRepository rooms,
    EventTeamRepository teams,
    TeamRoomExceptionRepository exceptions,
    QuizAnswerRepository answers,
    QuizEvaluationRepository evaluations,
    AuditLog audit
  ) {
    this.submissions = submissions;
    this.rooms = rooms;
    this.teams = teams;
    this.exceptions = exceptions;
    this.answers = answers;
    this.evaluations = evaluations;
    this.audit = audit;
  }

  /**
   * Evaluates the writing team unless it already was. The caller holds the event_state, team and room locks.
   * Returns true when this call recorded the evaluation.
   */
  boolean evaluate(long actorId, TeamWrite write, EventRoomRecord room, QuizEvaluationCause cause, Instant now) {
    if (evaluations.find(write.team().id(), room.id()).isPresent()) return false;
    record(actorId, new TeamWrite(write.team(), write.state(), REQUEST_HASH), room, cause, now);
    return true;
  }

  /**
   * The round's end (Time's up by End round or removed time, or the next round): every active, not disqualified
   * team assigned to an AI quiz room this round that is not evaluated yet. The caller holds the event_state lock.
   * Returns the number of teams evaluated.
   */
  int evaluateRoundEnd(long actorId, EventStateRecord state, Instant now) {
    int round = state.currentRound();
    if (!Rotation.isRunningRound(round)) return 0;
    List<EventRoomRecord> quizRooms = rooms.findAll().stream().filter(EventRoomRecord::hasQuiz).toList();
    if (quizRooms.isEmpty()) return 0;
    Map<Long, RoomCode> roundExceptions = exceptions.findForRound(round);
    List<EventTeamRecord> active = teams.findActive();
    int evaluated = 0;
    for (EventRoomRecord quizRoom : quizRooms) {
      Set<Long> done = evaluations.teamIds(quizRoom.id());
      List<Long> due = active
        .stream()
        .filter(team -> !team.disqualified() && !done.contains(team.id()))
        .filter(team -> RoomAssignment.isIn(quizRoom.code(), team.groupCode(), round, Optional.ofNullable(roundExceptions.get(team.id()))))
        .map(EventTeamRecord::id)
        .toList();
      if (due.isEmpty()) continue;
      // Lock order event_state → teams → room, as on every team write.
      List<EventTeamRecord> locked = teams.lockForUpdate(due);
      EventRoomRecord room = rooms.lockForUpdate(quizRoom.id()).orElseThrow();
      for (EventTeamRecord team : locked) {
        record(actorId, new TeamWrite(team, state, REQUEST_HASH), room, QuizEvaluationCause.TIME_UP, now);
        evaluated++;
      }
    }
    return evaluated;
  }

  private void record(long actorId, TeamWrite write, EventRoomRecord room, QuizEvaluationCause cause, Instant now) {
    long teamId = write.team().id();
    QuizAnswerRepository.Summary summary = answers.summary(teamId, room.id());
    boolean passed = QuizScoring.passes(summary.total(), room.quizPassScore());
    String progress = "AI quiz · MCQ %d/%d answered · repairs %d/%d submitted".formatted(
      summary.answered(),
      summary.questions(),
      summary.submitted(),
      summary.repairs()
    );
    // A random key: a fixed one could be taken first by one of the team's own submissions and make this insert fail.
    RoomSubmissionRecord submission = submissions.recordActivityResult(write, room, UUID.randomUUID(), progress, RESULT_TEXT, passed);
    evaluations.insert(
      new QuizEvaluationRecord(
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
    audit.record(actorId, "EVENT_QUIZ_EVALUATED", "room:" + room.id() + " team:" + teamId + " cause:" + cause + " passed:" + passed);
  }
}
