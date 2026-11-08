package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.MissionEvaluationCause;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.domain.sentinel.SentinelContent;
import dev.devfest.event.domain.sentinel.SentinelContent.Stage;
import dev.devfest.event.domain.sentinel.SentinelRuns;
import dev.devfest.event.domain.sentinel.SentinelScoring;
import dev.devfest.event.domain.sentinel.StageAnswer;
import dev.devfest.event.dto.AiRoomActivityView;
import dev.devfest.event.dto.MissionDocument;
import dev.devfest.event.dto.MissionRunRequest;
import dev.devfest.event.dto.MissionRunResult;
import dev.devfest.event.dto.MissionSavedReceipt;
import dev.devfest.event.dto.StageAnswers;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.SentinelContentRepository;
import dev.devfest.event.repository.SentinelEvaluationRepository;
import dev.devfest.event.repository.SentinelWorkRepository;
import dev.devfest.event.repository.SentinelWorkRepository.Run;
import dev.devfest.event.repository.SentinelWorkRepository.Submission;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The AI mission room of a team (rules §19): the view, experiments in the three stages that have them, and
 * submitting each of the ten stages once (it locks). Only the team assigned to the room this round sees it (404
 * otherwise, like an unknown room). Runs and submissions do not bump the event revision: nothing on other screens
 * changes with them. The team's last stage evaluates it (AiRoomEvaluationService), which bumps the revision; after
 * that nothing is accepted.
 */
@Service
public class AiRoomActivityService {

  static final String NOT_FOUND = "Activity not found.";
  static final String ALREADY_SAVED = "This stage is already saved.";
  static final String ALREADY_EVALUATED = "Your team's result for this room is already recorded.";
  static final String NOT_READY = "The mission is not ready yet.";

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final TeamRoomExceptionRepository exceptions;
  private final SentinelContentRepository contentStore;
  private final SentinelWorkRepository work;
  private final SentinelEvaluationRepository evaluations;
  private final AiRoomEvaluationService evaluator;
  private final AuditLog audit;
  private final EventClock clock;
  private final ObjectMapper json;

  public AiRoomActivityService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    EventStateRepository states,
    EventRoomRepository rooms,
    TeamRoomExceptionRepository exceptions,
    SentinelContentRepository contentStore,
    SentinelWorkRepository work,
    SentinelEvaluationRepository evaluations,
    AiRoomEvaluationService evaluator,
    AuditLog audit,
    EventClock clock,
    ObjectMapper json
  ) {
    this.access = access;
    this.gate = gate;
    this.states = states;
    this.rooms = rooms;
    this.exceptions = exceptions;
    this.contentStore = contentStore;
    this.work = work;
    this.evaluations = evaluations;
    this.evaluator = evaluator;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  /** 8 SQL statements whatever the number of teams or runs: team, state, room, exception, content, runs, submissions, evaluation. */
  public AiRoomActivityView view(Principal principal, long roomId) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    EventRoomRecord room = rooms.find(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    RoundTimer timer = requireAssigned(team, state, room, exceptionThisRound(state, team));
    return AiRoomViews.member(
      new AiRoomViews.MemberData(
        room,
        timer,
        clock.now(),
        content(room.id()),
        work.runs(team.id(), room.id()),
        work.submissions(team.id(), room.id()),
        evaluations.find(team.id(), room.id())
      ),
      json
    );
  }

  /**
   * One experiment. Order: replay of the same Idempotency-Key (same body → the same result, other body → 409),
   * disqualification, the team's room this round (404), room open, timer running, not evaluated, stage exists and
   * has experiments, not saved yet, configuration exists; then a configuration already run is free, a new one needs
   * budget (409).
   */
  @Transactional
  public MissionRunResult run(Principal principal, long roomId, int stageNumber, UUID idempotencyKey, MissionRunRequest request) {
    TeamWrite write = gate.openActivity(principal, "mission-run:" + roomId + ":" + stageNumber, request);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    long teamId = write.team().id();
    Optional<Run> previous = work.findRunByKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      int stagePrev = previous.get().stage();
      return runResult(previous.get(), runsLeft(stageOf(room, stagePrev), used(work.runs(teamId, roomId), stagePrev)));
    }
    TeamWriteGate.requireNotDisqualified(write);
    Instant now = clock.now();
    requireWritable(write, room, now);
    Stage stage = stageOf(room, stageNumber);
    if (!SentinelRuns.hasRuns(stage)) throw fail(400, "This stage has no experiments.");
    if (submitted(teamId, roomId, stageNumber)) throw fail(409, ALREADY_SAVED);
    String config = request.config().strip();
    var outcome = SentinelRuns.run(stage, config).orElseThrow(() -> fail(400, "Choose one of the stage's configurations."));
    List<Run> runs = work.runs(teamId, roomId);
    Optional<Run> repeat = runs.stream().filter(run -> run.stage() == stageNumber && run.config().equals(config)).findFirst();
    int used = used(runs, stageNumber);
    if (repeat.isPresent()) return runResult(repeat.get(), runsLeft(stage, used));
    if (used >= SentinelRuns.budget(stage)) throw fail(409, "You have used all the experiments of this stage.");
    Run run = new Run(teamId, roomId, stageNumber, config, json.writeValueAsString(outcome), now, idempotencyKey, write.requestHash());
    work.insertRun(run);
    audit.record(principal.id(), "EVENT_MISSION_RUN", "room:" + roomId + " stage:" + stageNumber);
    return runResult(run, runsLeft(stage, used + 1));
  }

  /**
   * Submits and locks one stage (same order of checks as {@link #run}). The score is computed here from the stage's
   * data and stored; the team only gets "Saved". The last missing stage evaluates the team in the same transaction.
   */
  @Transactional
  public MissionSavedReceipt submit(Principal principal, long roomId, int stageNumber, UUID idempotencyKey, JsonNode body) {
    TeamWrite write = gate.openActivity(principal, "mission-submit:" + roomId + ":" + stageNumber, body == null ? "null" : body);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    long teamId = write.team().id();
    Optional<Submission> previous = work.findSubmissionByKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return saved(previous.get().stage(), previous.get().submittedAt());
    }
    TeamWriteGate.requireNotDisqualified(write);
    Instant now = clock.now();
    requireWritable(write, room, now);
    Stage stage = stageOf(room, stageNumber);
    if (submitted(teamId, roomId, stageNumber)) throw fail(409, ALREADY_SAVED);
    BigDecimal points;
    try {
      StageAnswer answer = StageAnswers.read(stage, body);
      points = SentinelScoring.points(stage, answer);
    } catch (IllegalArgumentException e) {
      throw fail(400, e.getMessage());
    }
    work.insertSubmission(
      new Submission(teamId, roomId, stageNumber, write.state().currentRound(), json.writeValueAsString(body), points, now, idempotencyKey, write.requestHash())
    );
    audit.record(principal.id(), "EVENT_MISSION_STAGE_SAVED", "room:" + roomId + " stage:" + stageNumber);
    if (work.summary(teamId, roomId).submitted() == SentinelContent.STAGE_COUNT) {
      if (evaluator.evaluate(principal.id(), write, room, MissionEvaluationCause.LAST_STAGE, now)) states.bumpRevision();
    }
    return saved(stageNumber, now);
  }

  /** The team's activity room this round, open, timer running, team not evaluated. */
  private void requireWritable(TeamWrite write, EventRoomRecord room, Instant now) {
    RoundTimer timer = requireAssigned(write.team(), write.state(), room, exceptionThisRound(write.state(), write.team()));
    if (!room.open()) throw fail(409, "This room is closed.");
    switch (timer.effectivePhase(now)) {
      case ASSIGNED -> throw fail(409, "The timer has not started yet.");
      case PAUSED -> throw fail(409, "The round is paused.");
      case TIME_UP -> throw fail(409, "Time is up for this round.");
      case RUNNING -> {}
    }
    if (evaluations.find(write.team().id(), room.id()).isPresent()) throw fail(409, ALREADY_EVALUATED);
  }

  /** 404 unless the room runs the AI mission and is the team's room in the current round (exception or rotation). */
  private static RoundTimer requireAssigned(
    EventTeamRecord team,
    EventStateRecord state,
    EventRoomRecord room,
    Optional<RoomCode> exception
  ) {
    if (!room.hasMission() || !RoomAssignment.isIn(room.code(), team.groupCode(), state.currentRound(), exception)) {
      throw fail(404, NOT_FOUND);
    }
    return state.timer().orElseThrow(() -> fail(404, NOT_FOUND));
  }

  private Optional<RoomCode> exceptionThisRound(EventStateRecord state, EventTeamRecord team) {
    int round = state.currentRound();
    return Rotation.isRunningRound(round) ? exceptions.find(round, team.id()) : Optional.empty();
  }

  /** The room's mission; 409 while it has none, 404 for a stage number outside 1..10. */
  private Stage stageOf(EventRoomRecord room, int number) {
    SentinelContent content = content(room.id()).orElseThrow(() -> fail(409, NOT_READY));
    Stage stage = content.stage(number);
    if (stage == null) throw fail(404, "Stage not found.");
    return stage;
  }

  private Optional<SentinelContent> content(long roomId) {
    return contentStore.document(roomId).map(text -> MissionDocument.load(json.readTree(text)));
  }

  private boolean submitted(long teamId, long roomId, int stageNumber) {
    return work.submissions(teamId, roomId).stream().anyMatch(s -> s.stage() == stageNumber);
  }

  private MissionRunResult runResult(Run run, Integer runsLeft) {
    return new MissionRunResult(run.stage(), run.config(), json.readTree(run.resultJson()), runsLeft);
  }

  private static int used(List<Run> runs, int stageNumber) {
    return (int) runs.stream().filter(run -> run.stage() == stageNumber).count();
  }

  /** Different experiments still allowed; null when the stage has no limit. */
  private static Integer runsLeft(Stage stage, int used) {
    return SentinelRuns.budget(stage) == SentinelRuns.UNLIMITED ? null : SentinelRuns.budget(stage) - used;
  }

  private static MissionSavedReceipt saved(int stage, Instant at) {
    return new MissionSavedReceipt(stage, MissionSavedReceipt.SAVED, at);
  }
}
