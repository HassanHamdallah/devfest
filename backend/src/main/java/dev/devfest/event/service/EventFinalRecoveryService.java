package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.FinalStageResolver;
import dev.devfest.event.domain.ShipmentContent;
import dev.devfest.event.domain.recovery.ArduinoCheck;
import dev.devfest.event.domain.recovery.ArduinoCheck.TestResult;
import dev.devfest.event.domain.recovery.MlChecks;
import dev.devfest.event.domain.recovery.RecoveryLevel;
import dev.devfest.event.domain.recovery.RecoveryLevels;
import dev.devfest.event.domain.recovery.RecoveryLevels.Arduino;
import dev.devfest.event.domain.recovery.RecoveryLevels.Ml;
import dev.devfest.event.domain.recovery.RecoveryLevels.MlStageText;
import dev.devfest.event.domain.recovery.RecoveryLevels.SlotOption;
import dev.devfest.event.domain.recovery.RecoveryPath;
import dev.devfest.event.domain.recovery.RecoveryPath.Step;
import dev.devfest.event.domain.recovery.RecoveryThrottle;
import dev.devfest.event.domain.recovery.SlotShuffle;
import dev.devfest.event.domain.recovery.ThresholdMetrics;
import dev.devfest.event.domain.recovery.ThresholdMetrics.Metrics;
import dev.devfest.event.dto.ArduinoRunRequest;
import dev.devfest.event.dto.MlCheckRequest;
import dev.devfest.event.dto.RecoveryCheckResult;
import dev.devfest.event.dto.RecoveryProgressRow;
import dev.devfest.event.dto.TeamRecovery;
import dev.devfest.event.dto.TeamRecovery.ArduinoView;
import dev.devfest.event.dto.TeamRecovery.CleaningData;
import dev.devfest.event.dto.TeamRecovery.EvidenceData;
import dev.devfest.event.dto.TeamRecovery.LeakageData;
import dev.devfest.event.dto.TeamRecovery.LevelProgress;
import dev.devfest.event.dto.TeamRecovery.MlStageView;
import dev.devfest.event.dto.TeamRecovery.ModelData;
import dev.devfest.event.dto.TeamRecovery.PipelineData;
import dev.devfest.event.dto.TeamRecovery.SlotChoice;
import dev.devfest.event.dto.TeamRecovery.SlotView;
import dev.devfest.event.dto.TeamRecovery.StageFlag;
import dev.devfest.event.dto.TeamRecovery.TestName;
import dev.devfest.event.dto.TeamRecovery.ThresholdData;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.RecoveryRepository;
import dev.devfest.event.repository.RecoveryRepository.Check;
import dev.devfest.event.repository.RecoveryRepository.StageCount;
import dev.devfest.event.repository.RecoveryRepository.TeamProgress;
import dev.devfest.event.repository.RecoveryRepository.TeamRow;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.service.FinalWriteGuard.Guard;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The final's recovery levels (rules section 9). K = the room keys the team held when the final opened decides the path
 * ({@link RecoveryPath}): ML (six stages), then ARDUINO for 0 to 2 keys. Stages are checked in order on the server, each
 * passed once, with unlimited checks (one ML check every 2 s, one Arduino run every 3 s per team). Passing the last
 * required level grants the team's missing room keys (source RECOVERY) and records S, the active final second its case
 * clock starts at, in the same transaction. Every answer check is a pure domain function ({@link MlChecks},
 * {@link ArduinoCheck}); nothing returned to a member holds an answer, an accepted line, the correct option or the
 * passing threshold range. Writes take the event lock first (TeamWriteGate) and then the team's recovery row, so two
 * finishing checks resolve to one.
 */
@Service
public class EventFinalRecoveryService {

  static final String NO_RECOVERY = "Your team needs no recovery.";
  static final String ALREADY_FINISHED = "Your recovery is already complete.";
  static final String ALREADY_PASSED = "This stage is already passed.";
  static final String NOT_YOUR_LEVEL = "Your team does not play this level.";
  static final String EARLIER_FIRST = "Finish the earlier recovery stages first.";

  /** What the team was told about one check; stored with the check so a replay answers the same. */
  public record Outcome(String message, List<TestResult> tests, Metrics metrics, String token) {}

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final FinalWriteGuard guards;
  private final EventStateRepository states;
  private final TeamFinalRepository finals;
  private final RecoveryRepository recoveries;
  private final CollectedClueRepository clues;
  private final FinalContentReader contents;
  private final AuditLog audit;
  private final EventClock clock;
  private final ObjectMapper json;

  public EventFinalRecoveryService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    FinalWriteGuard guards,
    EventStateRepository states,
    TeamFinalRepository finals,
    RecoveryRepository recoveries,
    CollectedClueRepository clues,
    FinalContentReader contents,
    AuditLog audit,
    EventClock clock,
    ObjectMapper json
  ) {
    this.access = access;
    this.gate = gate;
    this.guards = guards;
    this.states = states;
    this.finals = finals;
    this.recoveries = recoveries;
    this.clues = clues;
    this.contents = contents;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  // ---- the team's view ----------------------------------------------------------------------------------------------

  /**
   * A constant number of SQL statements: team, event state, clue facts, the team's final row, its passed stages, the
   * content and its latest check of the current level (or of Arduino, for the last test results).
   */
  @Transactional(readOnly = true)
  public TeamRecovery view(Principal principal) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    Instant now = clock.now();
    TeamFinalRepository.ClueFacts facts = finals.clueFacts(team.id());
    boolean finalised = finals.find(team.id()).map(TeamFinalRepository.Row::finalised).orElse(false) || FinalClock.timeUp(state, now);
    FinalStage status = FinalStageResolver.resolve(state.finalEverOpened(), facts.keysAtOpen(), facts.recoveryFinished(), finalised);
    List<RecoveryLevel> levels = state.finalEverOpened() ? RecoveryPath.requiredLevels(facts.keysAtOpen()) : List.of();
    if (levels.isEmpty()) return new TeamRecovery(status, levels, false, null, null, List.of(), null, null, List.of(), 0);
    Set<Step> passed = recoveries.passedSteps(team.id());
    RecoveryLevels content = contents.content().map(ShipmentContent::recoveryLevels).orElse(null);
    if (content == null) return new TeamRecovery(status, levels, facts.recoveryFinished(), null, null, List.of(), null, null, List.of(), 0);
    Optional<Step> current = status == FinalStage.RECOVERY ? RecoveryPath.current(levels, passed) : Optional.empty();
    RecoveryLevel latestOf = current.map(Step::level).orElse(RecoveryLevel.ARDUINO);
    Optional<Check> latest = levels.contains(latestOf) ? recoveries.latest(team.id(), latestOf) : Optional.empty();
    return new TeamRecovery(
      status,
      levels,
      facts.recoveryFinished(),
      current.map(Step::level).orElse(null),
      current.map(Step::stage).orElse(null),
      levels.stream().map(level -> progress(level, content, passed)).toList(),
      current.filter(s -> s.level() == RecoveryLevel.ML).map(s -> mlStage(content.ml(), s.stage())).orElse(null),
      current.filter(s -> s.level() == RecoveryLevel.ARDUINO).map(s -> arduino(content.arduino(), team.id())).orElse(null),
      latestOf == RecoveryLevel.ARDUINO ? latest.map(c -> outcome(c).tests()).orElse(List.of()) : List.of(),
      current.flatMap(s -> latest.map(c -> RecoveryThrottle.waitSeconds(s.level(), c.receivedAt(), now))).orElse(0)
    );
  }

  private static LevelProgress progress(RecoveryLevel level, RecoveryLevels content, Set<Step> passed) {
    List<StageFlag> stages = new ArrayList<>();
    for (int stage = 1; stage <= level.stages(); stage++) {
      String name = level == RecoveryLevel.ML ? content.ml().stages().get(stage - 1).name() : content.arduino().title();
      stages.add(new StageFlag(stage, name, passed.contains(new Step(level, stage))));
    }
    boolean done = stages.stream().allMatch(StageFlag::passed);
    String title = level == RecoveryLevel.ML ? content.ml().title() : content.arduino().title();
    String tokenLabel = level == RecoveryLevel.ML ? content.ml().tokenLabel() : content.arduino().tokenLabel();
    String token = level == RecoveryLevel.ML ? content.ml().token() : content.arduino().token();
    return new LevelProgress(level, title, stages, done, done ? tokenLabel : null, done ? token : null);
  }

  /** The public part of one ML stage: never its answer. */
  private static MlStageView mlStage(Ml ml, int stage) {
    MlStageText text = ml.stages().get(stage - 1);
    return new MlStageView(
      stage,
      text.name(),
      text.title(),
      text.prompt(),
      text.note(),
      text.action(),
      stage == 1 ? new LeakageData(ml.leakage().features()) : null,
      stage == 2 ? new PipelineData(ml.pipeline().items(), ml.pipeline().startOrder(), ml.pipeline().relabel()) : null,
      stage == 3 ? new CleaningData(ml.cleaning().table(), ml.cleaning().missing(), ml.cleaning().outliers()) : null,
      stage == 4 ? new ModelData(ml.model().models()) : null,
      stage == 5
        ? new ThresholdData(
          ml.threshold().label(),
          ml.threshold().slider(),
          ThresholdMetrics.sliderValues(ml.threshold().slider()).stream().map(ThresholdMetrics::at).toList()
        )
        : null,
      stage == 6 ? new EvidenceData(ml.evidence().records(), ml.evidence().inputLabel(), ml.evidence().placeholder()) : null
    );
  }

  /** The public part of the Arduino level: never a bug, an accepted line or the correct option. */
  private static ArduinoView arduino(Arduino a, long teamId) {
    Map<String, String> codeById = a.slot().options().stream().collect(Collectors.toMap(SlotOption::id, SlotOption::code));
    List<String> order = SlotShuffle.order(optionIds(a), teamId);
    List<SlotChoice> choices = new ArrayList<>();
    for (int i = 0; i < order.size(); i++) choices.add(new SlotChoice(SlotShuffle.letter(i), codeById.get(order.get(i))));
    return new ArduinoView(
      a.title(),
      a.intro(),
      a.badge(),
      a.brief(),
      a.warning(),
      a.expectedBehaviour(),
      a.fileName(),
      a.action(),
      a.lines(),
      new SlotView(a.slot().afterLine(), a.slot().prompt(), choices),
      a.tests().stream().map(t -> new TestName(t.id(), t.name())).toList()
    );
  }

  // ---- checks -------------------------------------------------------------------------------------------------------

  /** One ML stage's answer (stages 1 to 6, in order); an Idempotency-Key retry returns the same verdict. */
  @Transactional(noRollbackFor = EventFinalService.FinalTimeUp.class)
  public RecoveryCheckResult checkMl(Principal principal, int stage, UUID idempotencyKey, MlCheckRequest request) {
    TeamWrite write = gate.openActivity(principal, "recovery-ml:" + stage, request);
    Optional<RecoveryCheckResult> replay = replay(write, idempotencyKey);
    if (replay.isPresent()) return replay.get();
    TeamWriteGate.requireNotDisqualified(write);
    if (stage < 1 || stage > RecoveryLevel.ML.stages()) throw fail(404, "Stage not found.");
    Guard guard = guards.check(write);
    RecoveryLevels content = requireContent();
    TeamRow row = recoveries.lockTeamRow(write.team().id());
    Step step = requireCurrent(row, new Step(RecoveryLevel.ML, stage));
    requireNoWait(write.team().id(), RecoveryLevel.ML, guard.now());
    Ml ml = content.ml();
    boolean passed = MlChecks.passes(ml, stage, answer(stage, request));
    MlStageText text = ml.stages().get(stage - 1);
    Metrics metrics = stage == 5 ? ThresholdMetrics.at(request.threshold()) : null;
    boolean levelPassed = passed && stage == RecoveryLevel.ML.stages();
    Outcome outcome = new Outcome(passed ? text.passMessage() : text.wrongMessage(), List.of(), metrics, levelPassed ? ml.token() : null);
    return record(principal, write, guard, row, step, request, passed, outcome, idempotencyKey);
  }

  /** One Arduino run: every program line and the chosen option's letter; an Idempotency-Key retry returns the same run. */
  @Transactional(noRollbackFor = EventFinalService.FinalTimeUp.class)
  public RecoveryCheckResult runArduino(Principal principal, UUID idempotencyKey, ArduinoRunRequest request) {
    TeamWrite write = gate.openActivity(principal, "recovery-arduino", request);
    Optional<RecoveryCheckResult> replay = replay(write, idempotencyKey);
    if (replay.isPresent()) return replay.get();
    TeamWriteGate.requireNotDisqualified(write);
    Guard guard = guards.check(write);
    RecoveryLevels content = requireContent();
    long teamId = write.team().id();
    TeamRow row = recoveries.lockTeamRow(teamId);
    Step step = requireCurrent(row, new Step(RecoveryLevel.ARDUINO, 1));
    requireNoWait(teamId, RecoveryLevel.ARDUINO, guard.now());
    Arduino program = content.arduino();
    String optionId = SlotShuffle.optionFor(optionIds(program), teamId, request.option()).orElse(null);
    ArduinoCheck.Run run = ArduinoCheck.run(program, request.lines(), optionId);
    String message = run.outsideChanged() ? program.outsideChangeMessage() : run.passed() ? program.passMessage() : program.wrongMessage();
    Outcome outcome = new Outcome(message, run.tests(), null, run.passed() ? program.token() : null);
    return record(principal, write, guard, row, step, request, run.passed(), outcome, idempotencyKey);
  }

  /** Stores the evaluated check; when it passes the last required stage, grants the missing keys and records S. */
  private RecoveryCheckResult record(
    Principal principal,
    TeamWrite write,
    Guard guard,
    TeamRow row,
    Step step,
    Object request,
    boolean passed,
    Outcome outcome,
    UUID idempotencyKey
  ) {
    long teamId = write.team().id();
    Check check = new Check(
      UUID.randomUUID(),
      teamId,
      step.level(),
      step.stage(),
      json.writeValueAsString(request),
      passed,
      json.writeValueAsString(outcome),
      guard.now(),
      write.requestHash()
    );
    recoveries.insertCheck(check, idempotencyKey);
    boolean finished = false;
    if (passed && RecoveryPath.isLast(RecoveryPath.requiredLevels(row.keysAtOpen()), step)) {
      int granted = clues.grantMissingFromRecovery(teamId, guard.now());
      finished = recoveries.finish(teamId, guard.now(), FinalClock.activeSeconds(guard.timer(), guard.now()));
      audit.record(principal.id(), "EVENT_RECOVERY_FINISHED", "team:" + teamId + " keys:" + granted);
    }
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_RECOVERY_CHECK", "recovery:" + check.id() + " " + step.level() + ":" + step.stage() + (passed ? " passed" : ""));
    FinalStage status = finished ? FinalStage.IN_FINAL : FinalStage.RECOVERY;
    return result(check, outcome, finished, status);
  }

  private Optional<RecoveryCheckResult> replay(TeamWrite write, UUID idempotencyKey) {
    long teamId = write.team().id();
    return recoveries
      .findByIdempotencyKey(teamId, idempotencyKey)
      .map(previous -> {
        TeamWriteGate.requireSameRequest(previous.requestHash(), write);
        TeamFinalRepository.ClueFacts facts = finals.clueFacts(teamId);
        boolean finalised = finals.find(teamId).map(TeamFinalRepository.Row::finalised).orElse(false);
        FinalStage status = FinalStageResolver.resolve(write.state().finalEverOpened(), facts.keysAtOpen(), facts.recoveryFinished(), finalised);
        return result(previous, outcome(previous), facts.recoveryFinished(), status);
      });
  }

  /** The stage the team may check now must be exactly {@code step}; otherwise the refusal says why (409). */
  private Step requireCurrent(TeamRow row, Step step) {
    List<RecoveryLevel> required = RecoveryPath.requiredLevels(row.keysAtOpen());
    if (required.isEmpty()) throw fail(409, NO_RECOVERY);
    if (row.finishedAt() != null) throw fail(409, ALREADY_FINISHED);
    if (!required.contains(step.level())) throw fail(409, NOT_YOUR_LEVEL);
    Set<Step> passed = recoveries.passedSteps(row.teamId());
    if (passed.contains(step)) throw fail(409, ALREADY_PASSED);
    if (!RecoveryPath.current(required, passed).map(step::equals).orElse(false)) throw fail(409, EARLIER_FIRST);
    return step;
  }

  private void requireNoWait(long teamId, RecoveryLevel level, Instant now) {
    Instant last = recoveries.latest(teamId, level).map(Check::receivedAt).orElse(null);
    int wait = RecoveryThrottle.waitSeconds(level, last, now);
    if (wait > 0) throw fail(429, "Wait " + wait + (wait == 1 ? " second" : " seconds") + " before the next check.");
  }

  /** The domain answer of ML stage {@code stage}, from the field that stage reads. */
  private static MlChecks.Answer answer(int stage, MlCheckRequest request) {
    return switch (stage) {
      case 1 -> new MlChecks.Features(request.features());
      case 2 -> new MlChecks.Order(request.order());
      case 3 -> new MlChecks.CleaningChoice(request.missing(), request.outliers());
      case 4 -> new MlChecks.ModelPick(request.model());
      case 5 -> new MlChecks.ThresholdValue(request.threshold());
      default -> new MlChecks.Word(request.word());
    };
  }

  private RecoveryLevels requireContent() {
    return contents.content().map(ShipmentContent::recoveryLevels).orElseThrow(() -> fail(409, "The final content is not ready."));
  }

  private Outcome outcome(Check check) {
    return json.readValue(check.outcome(), Outcome.class);
  }

  private static RecoveryCheckResult result(Check check, Outcome outcome, boolean finished, FinalStage status) {
    return new RecoveryCheckResult(
      check.id(),
      check.level(),
      check.stage(),
      check.passed(),
      outcome.message(),
      outcome.tests() == null ? List.of() : outcome.tests(),
      outcome.metrics(),
      outcome.token(),
      finished,
      status,
      check.receivedAt()
    );
  }

  private static List<String> optionIds(Arduino a) {
    return a.slot().options().stream().map(SlotOption::id).toList();
  }

  // ---- the admin's table --------------------------------------------------------------------------------------------

  /** Every active team's recovery progress; two SQL statements whatever the number of teams. */
  @Transactional(readOnly = true)
  public List<RecoveryProgressRow> adminProgress(Principal principal) {
    access.requireAdmin(principal);
    Map<Long, List<StageCount>> counts = recoveries.stageCounts().stream().collect(Collectors.groupingBy(StageCount::teamId));
    return recoveries.progressOfActiveTeams().stream().map(team -> adminRow(team, counts.getOrDefault(team.teamId(), List.of()))).toList();
  }

  private static RecoveryProgressRow adminRow(TeamProgress team, List<StageCount> counts) {
    List<RecoveryLevel> levels = RecoveryPath.requiredLevels(Math.min(team.keysAtOpen(), RecoveryPath.ROOM_KEYS));
    Set<Step> passed = counts.stream().filter(StageCount::passed).map(c -> new Step(c.level(), c.stage())).collect(Collectors.toSet());
    boolean finished = team.finishedAt() != null;
    Optional<Step> current = finished ? Optional.empty() : RecoveryPath.current(levels, passed);
    int stageChecks = current
      .map(step -> counts.stream().filter(c -> c.level() == step.level() && c.stage() == step.stage()).mapToInt(StageCount::checks).sum())
      .orElse(0);
    return new RecoveryProgressRow(
      team.teamId(),
      team.teamName(),
      team.keysAtOpen(),
      levels,
      current.map(Step::level).orElse(null),
      current.map(Step::stage).orElse(null),
      finished,
      counts.stream().mapToInt(StageCount::checks).sum(),
      stageChecks,
      counts.stream().map(StageCount::lastAt).max(Instant::compareTo).orElse(null),
      team.finishedAt(),
      team.finishedActiveSeconds()
    );
  }
}
