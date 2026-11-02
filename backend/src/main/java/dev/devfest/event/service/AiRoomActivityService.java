package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.QuizEvaluationCause;
import dev.devfest.event.domain.QuizScoring;
import dev.devfest.event.domain.QuizWindow;
import dev.devfest.event.domain.QuizWindow.Stage;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.dto.AiRoomActivityView;
import dev.devfest.event.dto.QuizAnswerRequest;
import dev.devfest.event.dto.QuizRepairOrderRequest;
import dev.devfest.event.dto.QuizSavedReceipt;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.QuizAnswerRepository;
import dev.devfest.event.repository.QuizAnswerRepository.OptionCheck;
import dev.devfest.event.repository.QuizContentRepository;
import dev.devfest.event.repository.QuizEvaluationRepository;
import dev.devfest.event.repository.QuizMcqAnswerRecord;
import dev.devfest.event.repository.QuizRepairAnswerRecord;
import dev.devfest.event.repository.QuizRepairRecord;
import dev.devfest.event.repository.QuizTeamProgressRecord;
import dev.devfest.event.repository.QuizTeamProgressRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AI room activity of a team (rules §19): the view, saving MCQ answers and repair orders (each locked on its
 * first save), and "Finish MCQs". Only the team assigned to the room this round sees it (404 otherwise, like an
 * unknown room). Answers do not bump the event revision: nothing on other screens changes with them. The team's
 * last repair evaluates it (AiRoomEvaluationService), which bumps the revision; after that nothing is accepted.
 */
@Service
public class AiRoomActivityService {

  static final String NOT_FOUND = "Activity not found.";
  static final String ALREADY_ANSWERED = "Already answered.";
  static final String ALREADY_EVALUATED = "Your team's result for this room is already recorded.";

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final TeamRoomExceptionRepository exceptions;
  private final QuizContentRepository content;
  private final QuizAnswerRepository answers;
  private final QuizTeamProgressRepository progress;
  private final QuizEvaluationRepository evaluations;
  private final AiRoomEvaluationService evaluator;
  private final AuditLog audit;
  private final EventClock clock;
  private final AiRoomShuffleSecret shuffleSecret;

  public AiRoomActivityService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    EventStateRepository states,
    EventRoomRepository rooms,
    TeamRoomExceptionRepository exceptions,
    QuizContentRepository content,
    QuizAnswerRepository answers,
    QuizTeamProgressRepository progress,
    QuizEvaluationRepository evaluations,
    AiRoomEvaluationService evaluator,
    AuditLog audit,
    EventClock clock,
    AiRoomShuffleSecret shuffleSecret
  ) {
    this.access = access;
    this.gate = gate;
    this.states = states;
    this.rooms = rooms;
    this.exceptions = exceptions;
    this.content = content;
    this.answers = answers;
    this.progress = progress;
    this.evaluations = evaluations;
    this.evaluator = evaluator;
    this.audit = audit;
    this.clock = clock;
    this.shuffleSecret = shuffleSecret;
  }

  /**
   * 10 SQL statements whatever the number of teams or answers: team, state, room, the team's exception this round,
   * questions with options, repairs with snippets, the team's MCQ answers, its repair answers, its progress and its
   * evaluation.
   */
  public AiRoomActivityView view(Principal principal, long roomId) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    EventRoomRecord room = rooms.find(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    RoundTimer timer = requireAssigned(team, state, room, exceptionThisRound(state, team));
    return memberView(team, room, timer, clock.now());
  }

  /**
   * Saves the team's answer to one question. Order: replay of the same Idempotency-Key (same body → the same
   * receipt, other body → 409), disqualification, the team's room this round (404), room open, timer running, not
   * evaluated yet, MCQ part open, question and option of this room, not answered yet (409 "Already answered.").
   */
  @Transactional
  public QuizSavedReceipt answer(Principal principal, long roomId, UUID idempotencyKey, QuizAnswerRequest request) {
    TeamWrite write = gate.openActivity(principal, "quiz-answer:" + roomId, request);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    long teamId = write.team().id();
    Optional<QuizMcqAnswerRecord> previous = answers.findMcqByKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return saved(previous.get().questionId(), previous.get().answeredAt());
    }
    TeamWriteGate.requireNotDisqualified(write);
    Instant now = clock.now();
    Writable writable = requireWritable(write, room, now);
    if (writable.stage() != Stage.MCQ) throw fail(409, "The multiple-choice part is over.");
    OptionCheck check = answers
      .checkOption(roomId, request.questionId(), request.optionId())
      .orElseThrow(() -> fail(404, "Question not found."));
    if (check.correct() == null) throw fail(400, "Choose one of the question's options.");
    if (answers.mcqAnswered(teamId, request.questionId())) throw fail(409, ALREADY_ANSWERED);
    ensureProgress(teamId, roomId, writable.progress(), now);
    answers.insertMcq(
      new QuizMcqAnswerRecord(
        teamId,
        request.questionId(),
        request.optionId(),
        roomId,
        write.state().currentRound(),
        QuizScoring.mcqPoints(check.points(), check.correct()),
        now,
        idempotencyKey,
        write.requestHash()
      )
    );
    audit.record(principal.id(), "EVENT_QUIZ_ANSWER", "room:" + roomId + " question:" + request.questionId());
    return saved(request.questionId(), now);
  }

  /**
   * Saves the team's order for one repair (same order of checks as {@link #answer}; the repairs must be open). The
   * last missing repair evaluates the team in the same transaction.
   */
  @Transactional
  public QuizSavedReceipt submitRepair(
    Principal principal,
    long roomId,
    long repairId,
    UUID idempotencyKey,
    QuizRepairOrderRequest request
  ) {
    TeamWrite write = gate.openActivity(principal, "quiz-repair:" + roomId + ":" + repairId, request);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    long teamId = write.team().id();
    Optional<QuizRepairAnswerRecord> previous = answers.findRepairByKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return saved(previous.get().repairId(), previous.get().submittedAt());
    }
    TeamWriteGate.requireNotDisqualified(write);
    Instant now = clock.now();
    Writable writable = requireWritable(write, room, now);
    if (writable.stage() == Stage.MCQ) throw fail(409, "The repairs open after the multiple-choice part.");
    List<QuizRepairRecord> repairs = content.repairs(roomId);
    QuizRepairRecord repair = repairs
      .stream()
      .filter(candidate -> candidate.id() == repairId)
      .findFirst()
      .orElseThrow(() -> fail(404, "Repair not found."));
    List<Long> correctOrder = repair.correctOrder();
    if (!isPermutation(request.snippetIds(), correctOrder)) {
      throw fail(400, "Submit every snippet of this repair exactly once.");
    }
    if (answers.repairSubmitted(teamId, repairId)) throw fail(409, ALREADY_ANSWERED);
    ensureProgress(teamId, roomId, writable.progress(), now);
    int correct = QuizScoring.correctPositions(request.snippetIds(), correctOrder);
    answers.insertRepair(
      new QuizRepairAnswerRecord(
        teamId,
        repairId,
        roomId,
        write.state().currentRound(),
        List.copyOf(request.snippetIds()),
        correct,
        correctOrder.size(),
        QuizScoring.repairPoints(repair.points(), correct, correctOrder.size()),
        now,
        idempotencyKey,
        write.requestHash()
      )
    );
    audit.record(principal.id(), "EVENT_QUIZ_REPAIR", "room:" + roomId + " repair:" + repairId);
    if (answers.teamRepairAnswers(teamId, roomId).size() == repairs.size()) {
      if (evaluator.evaluate(principal.id(), write, room, QuizEvaluationCause.LAST_REPAIR, now)) states.bumpRevision();
    }
    return saved(repairId, now);
  }

  /** Ends the team's MCQ part for good (the page asks first); a repeat, or a call after the cutoff, changes nothing. */
  @Transactional
  public AiRoomActivityView finishMcq(Principal principal, long roomId) {
    TeamWrite write = gate.openActivity(principal, "quiz-finish:" + roomId, Map.of());
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    TeamWriteGate.requireNotDisqualified(write);
    Instant now = clock.now();
    Writable writable = requireWritable(write, room, now);
    long teamId = write.team().id();
    if (writable.stage() == Stage.MCQ) {
      ensureProgress(teamId, roomId, writable.progress(), now);
      progress.finishMcq(teamId, roomId, now);
      audit.record(principal.id(), "EVENT_QUIZ_MCQ_FINISHED", "room:" + roomId);
    }
    return memberView(write.team(), room, writable.timer(), now);
  }

  private record Writable(RoundTimer timer, Stage stage, Optional<QuizTeamProgressRecord> progress) {}

  /** The team's activity room this round, open, timer running, team not evaluated; returns the stage at {@code now}. */
  private Writable requireWritable(TeamWrite write, EventRoomRecord room, Instant now) {
    RoundTimer timer = requireAssigned(write.team(), write.state(), room, exceptionThisRound(write.state(), write.team()));
    if (!room.open()) throw fail(409, "This room is closed.");
    switch (timer.effectivePhase(now)) {
      case ASSIGNED -> throw fail(409, "The timer has not started yet.");
      case PAUSED -> throw fail(409, "The round is paused.");
      case TIME_UP -> throw fail(409, "Time is up for this round.");
      case RUNNING -> {}
    }
    if (evaluations.find(write.team().id(), room.id()).isPresent()) throw fail(409, ALREADY_EVALUATED);
    Optional<QuizTeamProgressRecord> current = progress.find(write.team().id(), room.id());
    boolean finished = current.map(QuizTeamProgressRecord::mcqFinished).orElse(false);
    return new Writable(timer, QuizWindow.stage(timer, now, room.quizMcqMinutes() * 60, finished), current);
  }

  /** 404 unless the room runs the AI quiz and is the team's room in the current round (exception or rotation). */
  private static RoundTimer requireAssigned(
    EventTeamRecord team,
    EventStateRecord state,
    EventRoomRecord room,
    Optional<RoomCode> exception
  ) {
    if (!room.hasQuiz() || !RoomAssignment.isIn(room.code(), team.groupCode(), state.currentRound(), exception)) {
      throw fail(404, NOT_FOUND);
    }
    return state.timer().orElseThrow(() -> fail(404, NOT_FOUND));
  }

  private Optional<RoomCode> exceptionThisRound(EventStateRecord state, EventTeamRecord team) {
    int round = state.currentRound();
    return Rotation.isRunningRound(round) ? exceptions.find(round, team.id()) : Optional.empty();
  }

  /** The team's first action in the room (always after its round's timer started). */
  private void ensureProgress(long teamId, long roomId, Optional<QuizTeamProgressRecord> current, Instant now) {
    if (current.isEmpty()) progress.createIfAbsent(teamId, roomId, now);
  }

  private AiRoomActivityView memberView(EventTeamRecord team, EventRoomRecord room, RoundTimer timer, Instant now) {
    return AiRoomViews.member(
      new AiRoomViews.MemberData(
        team.id(),
        room,
        timer,
        now,
        content.questions(room.id()),
        content.repairs(room.id()),
        answers.teamMcqAnswers(team.id(), room.id()),
        answers.teamRepairAnswers(team.id(), room.id()),
        progress.find(team.id(), room.id()),
        evaluations.find(team.id(), room.id())
      ),
      shuffleSecret.bytes()
    );
  }

  private static boolean isPermutation(List<Long> submitted, List<Long> expected) {
    return submitted.size() == expected.size() && !submitted.contains(null) && new HashSet<>(submitted).equals(new HashSet<>(expected));
  }

  private static QuizSavedReceipt saved(long itemId, Instant at) {
    return new QuizSavedReceipt(itemId, QuizSavedReceipt.SAVED, at);
  }
}
