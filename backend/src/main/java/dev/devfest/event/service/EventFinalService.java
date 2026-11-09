package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.api.Errors;
import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.AnswerNormalizer;
import dev.devfest.event.domain.AttemptThrottle;
import dev.devfest.event.domain.CryptoContent;
import dev.devfest.event.domain.FinalScreen;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.FinalStageResolver;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.dto.FinalActionResult;
import dev.devfest.event.dto.LayerAnswerRequest;
import dev.devfest.event.dto.RecoveryRequest;
import dev.devfest.event.dto.RecoveryResult;
import dev.devfest.event.dto.SuspectChoiceRequest;
import dev.devfest.event.dto.TeamFinal;
import dev.devfest.event.dto.TeamFinal.FinalSuspect;
import dev.devfest.event.dto.TeamFinal.LayerPrompt;
import dev.devfest.event.dto.TeamFinal.Mission;
import dev.devfest.event.dto.TeamFinal.NotebookEntry;
import dev.devfest.event.dto.TeamFinal.RecoveryChallenge;
import dev.devfest.event.dto.TeamFinal.Signature;
import dev.devfest.event.dto.TeamFinal.Submitted;
import dev.devfest.event.dto.TeamFinal.Unlock;
import dev.devfest.event.dto.UnlockRequest;
import dev.devfest.event.dto.VerdictRequest;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.RecoveryAttemptRecord;
import dev.devfest.event.repository.RecoveryAttemptRepository;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamFinalRepository.ClueFacts;
import dev.devfest.event.repository.TeamFinalRepository.Row;
import dev.devfest.event.repository.TeamFinalRepository.Streak;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The team's final: qualification, the one Recovery Challenge and the crypto case (unlock, mission, four layers, two
 * one-time verdicts, one suspect). Every rule is enforced here on the server and the screen is always derived from
 * stored facts, so a refresh resumes exactly where the team was. Nothing returned to a member holds a plaintext, a
 * private exponent, p, q, a verdict's correctness, the correct suspect or a score.
 *
 * <p>All writes take the event_state lock first (TeamWriteGate), so the final's single timer, a team's submission and
 * the time-up finalisation are serialized: whichever comes first wins and a team's final is finalised exactly once.
 * There is no scheduler; a write that finds the timer at zero finalises the team in its own transaction (kept through
 * {@link FinalTimeUp}) and the admin's close finalises everyone still open.
 */
@Service
public class EventFinalService {

  static final String MISSION_STORY =
    "Rami left four encrypted evidence layers. Solve RSA on paper, examine the clues, and verify the signed reports.";
  static final String EQUIPMENT = "Printed dossiers and four shuffled key cards. A plain calculator is allowed.";
  static final String UNLOCK_WRONG = "These room outputs do not open the case.";
  static final String LAYER_WRONG = "That number does not open this layer.";
  static final String VERDICT_RECORDED = "Your verdict is recorded.";
  static final String SUBMITTED_MESSAGE = "Your final answer is recorded. The result stays confidential until judging.";
  static final String TIME_UP_MESSAGE = "Time is up. The final is over for your team.";
  static final String NOT_QUALIFIED = "You did not qualify for the final";
  private static final Pattern INTEGER = Pattern.compile("[0-9]{1,12}");

  /** The final's time ran out; thrown after the team was finalised, so the finalisation commits with the refusal. */
  public static final class FinalTimeUp extends Errors.ApiException {

    FinalTimeUp() {
      super(409, "Time is up for the final.");
    }
  }

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final EventStateRepository states;
  private final TeamFinalRepository finals;
  private final EventRoomRepository rooms;
  private final CollectedClueRepository clues;
  private final RecoveryAttemptRepository recoveries;
  private final FinalContentReader contents;
  private final EventBoardRepository boards;
  private final TeamRoomExceptionRepository exceptions;
  private final AuditLog audit;
  private final EventClock clock;
  private final ObjectMapper json;

  public EventFinalService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    EventStateRepository states,
    TeamFinalRepository finals,
    EventRoomRepository rooms,
    CollectedClueRepository clues,
    RecoveryAttemptRepository recoveries,
    FinalContentReader contents,
    EventBoardRepository boards,
    TeamRoomExceptionRepository exceptions,
    AuditLog audit,
    EventClock clock,
    ObjectMapper json
  ) {
    this.access = access;
    this.gate = gate;
    this.states = states;
    this.finals = finals;
    this.rooms = rooms;
    this.clues = clues;
    this.recoveries = recoveries;
    this.contents = contents;
    this.boards = boards;
    this.exceptions = exceptions;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  // ---- the screen -------------------------------------------------------------------------------

  /**
   * A constant number of SQL statements whatever the number of teams: team, event state, clue facts, the team's
   * final row, the content, solved layers, verdicts, the rooms, the team's evidence (rooms + exceptions) and, on an
   * answer screen, that screen's wrong-answer streak.
   */
  public TeamFinal view(Principal principal) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    Instant now = clock.now();
    ClueFacts facts = finals.clueFacts(team.id());
    Optional<Row> row = finals.find(team.id());
    boolean timeUp = FinalClock.timeUp(state, now);
    boolean finalised = row.map(Row::finalised).orElse(false) || (timeUp && facts.clues() >= 3);
    FinalStage status = FinalStageResolver.resolve(state.finalEverOpened(), facts.clues(), finalised);
    Optional<RoundTimer> timer = state.finalTimer();
    List<EventRoomRecord> roomList = rooms.findAll();
    var evidence = EventViews.evidence(team, state, boards.teamRooms(team.id()), exceptions.findForTeam(team.id()), now);
    FinalScreen screen = screenOf(status, row.orElse(null), Set.of());
    Set<Integer> solved = Set.of();
    List<TeamFinalRepository.Verdict> verdicts = List.of();
    Optional<CryptoContent> content = Optional.empty();
    if (status == FinalStage.IN_FINAL || status == FinalStage.RECOVERY || status == FinalStage.COMPLETED) {
      content = contents.content();
    }
    if (status == FinalStage.IN_FINAL || status == FinalStage.COMPLETED) {
      solved = finals.solvedLayers(team.id());
      verdicts = finals.verdicts(team.id());
      screen = screenOf(status, row.orElse(null), solved);
    }
    int wait = 0;
    if (screen == FinalScreen.UNLOCK) {
      Streak streak = finals.unlockStreak(team.id());
      wait = AttemptThrottle.waitSeconds(streak.wrongInARow(), streak.lastAttemptAt(), now);
    } else if (screen == FinalScreen.LAYER) {
      Streak streak = finals.layerStreak(team.id(), solved.size() + 1);
      wait = AttemptThrottle.waitSeconds(streak.wrongInARow(), streak.lastAttemptAt(), now);
    }
    return new TeamFinal(
      status,
      screen,
      now,
      state.finalStartedAt(),
      timer.map(t -> t.effectivePhase(now)).orElse(null),
      timer.map(t -> t.remainingSeconds(now)).orElse(null),
      facts.clues(),
      wait,
      screen == FinalScreen.UNLOCK ? new Unlock(roomList.stream().map(EventRoomRecord::name).toList()) : null,
      screen == FinalScreen.RECOVERY && content.isPresent()
        ? new RecoveryChallenge(content.get().recovery().question(), facts.recoveryAttempts())
        : null,
      screen == FinalScreen.MISSION ? new Mission(MISSION_STORY, EQUIPMENT) : null,
      screen == FinalScreen.LAYER && content.isPresent() ? layerPrompt(content.get(), solved) : null,
      content.isPresent() && status != FinalStage.RECOVERY ? notebook(content.get(), solved, verdicts, finalised) : List.of(),
      screen == FinalScreen.SUSPECTS && content.isPresent() ? suspects(content.get()) : List.of(),
      screen == FinalScreen.SUBMITTED ? submitted(team, status, row.orElse(null), now) : null,
      state.resultsPublished(),
      evidence
    );
  }

  private static FinalScreen screenOf(FinalStage status, Row row, Set<Integer> solved) {
    return switch (status) {
      case LOCKED -> FinalScreen.LOCKED;
      case NOT_QUALIFIED -> FinalScreen.NOT_QUALIFIED;
      case RECOVERY -> FinalScreen.RECOVERY;
      case COMPLETED -> FinalScreen.SUBMITTED;
      case IN_FINAL -> {
        if (row == null || row.unlockedAt() == null) yield FinalScreen.UNLOCK;
        if (row.caseStartedAt() == null) yield FinalScreen.MISSION;
        yield solved.size() >= CryptoContent.LAYER_COUNT ? FinalScreen.SUSPECTS : FinalScreen.LAYER;
      }
    };
  }

  private static LayerPrompt layerPrompt(CryptoContent content, Set<Integer> solved) {
    int layer = solved.size() + 1;
    CryptoContent.Layer l = content.layers().get(layer - 1);
    return new LayerPrompt(layer, l.c(), l.n(), solved.size());
  }

  /** The evidence revealed so far: E0k opens when layer k is solved. */
  private static List<NotebookEntry> notebook(
    CryptoContent content,
    Set<Integer> solved,
    List<TeamFinalRepository.Verdict> verdicts,
    boolean finalised
  ) {
    Map<Integer, String> given = verdicts.stream().collect(Collectors.toMap(TeamFinalRepository.Verdict::layer, TeamFinalRepository.Verdict::verdict));
    List<NotebookEntry> entries = new ArrayList<>();
    for (int layer = 1; layer <= CryptoContent.LAYER_COUNT; layer++) {
      if (!solved.contains(layer)) continue;
      CryptoContent.Evidence evidence = content.evidenceOf(layer);
      boolean signed = CryptoContent.SIGNED_EVIDENCE.contains(evidence.id());
      Signature signature = signed
        ? new Signature(content.report(evidence.id()).orElseThrow().s(), content.signatures().e(), content.signatures().n(), content.signatures().h())
        : null;
      String yours = given.get(layer);
      entries.add(new NotebookEntry(evidence.id(), evidence.text(), evidence.claimedSigner(), signature, signed && yours == null && !finalised, yours));
    }
    return entries;
  }

  private static List<FinalSuspect> suspects(CryptoContent content) {
    List<FinalSuspect> list = new ArrayList<>();
    for (int i = 0; i < content.suspects().size(); i++) list.add(new FinalSuspect(i + 1, content.suspects().get(i)));
    return list;
  }

  private static Submitted submitted(EventTeamRecord team, FinalStage status, Row row, Instant now) {
    boolean timeUp = row == null || !"SUBMITTED".equals(row.finalisedCause());
    Instant at = row != null && row.finalisedAt() != null ? row.finalisedAt() : now;
    return new Submitted(team.name(), status, at, timeUp ? TIME_UP_MESSAGE : SUBMITTED_MESSAGE);
  }

  // ---- unlock -----------------------------------------------------------------------------------

  @Transactional(noRollbackFor = FinalTimeUp.class)
  public FinalActionResult unlock(Principal principal, UUID idempotencyKey, UnlockRequest request) {
    TeamWrite write = gate.open(principal, "final-unlock", request);
    long teamId = write.team().id();
    Optional<TeamFinalRepository.StoredAttempt> previous = finals.findUnlockAttemptByKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return replay(write, previous.get().correct(), UNLOCK_WRONG, FinalScreen.UNLOCK);
    }
    TeamWriteGate.requireNotDisqualified(write);
    for (String code : request.codes()) requireMaxBytes(code == null ? "" : code, 500, "Room output");
    Guard guard = guard(write);
    if (guard.facts().clues() < FinalStageResolver.REQUIRED_CLUES) {
      throw fail(409, "Solve the Recovery Challenge first.");
    }
    if (guard.row() != null && guard.row().unlockedAt() != null) throw fail(409, "The case is already unlocked.");
    Streak streak = finals.unlockStreak(teamId);
    requireNoWait(AttemptThrottle.waitSeconds(streak.wrongInARow(), streak.lastAttemptAt(), guard.now()));
    boolean correct = codesOpenTheCase(teamId, request.codes());
    finals.insertUnlockAttempt(UUID.randomUUID(), teamId, joinCodes(request.codes()), correct, guard.now(), idempotencyKey, write.requestHash());
    if (correct) {
      finals.markUnlocked(teamId, guard.now());
      states.bumpRevision();
      audit.record(principal.id(), "EVENT_FINAL_UNLOCKED", "team:" + teamId);
      return result(true, "The case is open.", 0, write, FinalScreen.MISSION);
    }
    int wait = AttemptThrottle.waitSeconds(streak.wrongInARow() + 1, guard.now(), guard.now());
    return result(false, UNLOCK_WRONG, wait, write, FinalScreen.UNLOCK);
  }

  /** Every input must match its own room's clue key AND the team must hold that room's clue. */
  private boolean codesOpenTheCase(long teamId, List<String> codes) {
    List<EventRoomRecord> roomList = rooms.findAll();
    Set<Long> collected = Set.copyOf(clues.roomIdsForTeam(teamId));
    boolean all = roomList.size() == codes.size();
    for (int i = 0; all && i < roomList.size(); i++) {
      EventRoomRecord room = roomList.get(i);
      // No short-circuit between rooms: the work done never depends on which field was wrong.
      all = collected.contains(room.id()) & AnswerNormalizer.matches(codes.get(i), room.clueKey());
    }
    return all;
  }

  private String joinCodes(List<String> codes) {
    return json.writeValueAsString(codes);
  }

  // ---- start case -------------------------------------------------------------------------------

  @Transactional(noRollbackFor = FinalTimeUp.class)
  public FinalActionResult startCase(Principal principal) {
    TeamWrite write = gate.open(principal, "final-start", "start");
    TeamWriteGate.requireNotDisqualified(write);
    Guard guard = guard(write);
    if (guard.row() == null || guard.row().unlockedAt() == null) throw fail(409, "Open the case first.");
    if (guard.row().caseStartedAt() != null) return result(true, "The case is started.", 0, write, FinalScreen.LAYER);
    finals.markCaseStarted(write.team().id(), guard.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_FINAL_CASE_STARTED", "team:" + write.team().id());
    return result(true, "The case is started.", 0, write, FinalScreen.LAYER);
  }

  // ---- layers -----------------------------------------------------------------------------------

  @Transactional(noRollbackFor = FinalTimeUp.class)
  public FinalActionResult answerLayer(Principal principal, int layer, UUID idempotencyKey, LayerAnswerRequest request) {
    TeamWrite write = gate.open(principal, "final-layer:" + layer, request);
    long teamId = write.team().id();
    Optional<TeamFinalRepository.StoredAttempt> previous = finals.findLayerAttemptByKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return replay(write, previous.get().correct(), LAYER_WRONG, FinalScreen.LAYER);
    }
    TeamWriteGate.requireNotDisqualified(write);
    if (layer < 1 || layer > CryptoContent.LAYER_COUNT) throw fail(404, "Layer not found.");
    String value = request.value().strip();
    if (!INTEGER.matcher(value).matches()) throw fail(400, "Enter a whole number.");
    Guard guard = guard(write);
    requireCaseStarted(guard);
    Set<Integer> solved = finals.solvedLayers(teamId);
    if (solved.contains(layer)) throw fail(409, "This layer is already solved.");
    if (solved.size() != layer - 1) throw fail(409, "Solve the earlier layers first.");
    Streak streak = finals.layerStreak(teamId, layer);
    requireNoWait(AttemptThrottle.waitSeconds(streak.wrongInARow(), streak.lastAttemptAt(), guard.now()));
    CryptoContent content = requireContent();
    boolean correct = Long.parseLong(value) == content.plaintext(layer);
    finals.insertLayerAttempt(UUID.randomUUID(), teamId, layer, value, correct, guard.now(), idempotencyKey, write.requestHash());
    if (correct) {
      states.bumpRevision();
      audit.record(principal.id(), "EVENT_FINAL_LAYER_SOLVED", "team:" + teamId + " layer:" + layer);
      return result(true, "The layer opens.", 0, write, layer == CryptoContent.LAYER_COUNT ? FinalScreen.SUSPECTS : FinalScreen.LAYER);
    }
    int wait = AttemptThrottle.waitSeconds(streak.wrongInARow() + 1, guard.now(), guard.now());
    return result(false, LAYER_WRONG, wait, write, FinalScreen.LAYER);
  }

  // ---- verdicts ---------------------------------------------------------------------------------

  /** One time only; the answer is identical whether or not the verdict was right, and a wrong one blocks nothing. */
  @Transactional(noRollbackFor = FinalTimeUp.class)
  public FinalActionResult giveVerdict(Principal principal, int layer, VerdictRequest request) {
    TeamWrite write = gate.open(principal, "final-verdict:" + layer, request);
    TeamWriteGate.requireNotDisqualified(write);
    if (layer < 3 || layer > 4) throw fail(404, "Verdict not found.");
    long teamId = write.team().id();
    Optional<TeamFinalRepository.Verdict> given = finals.verdicts(teamId).stream().filter(v -> v.layer() == layer).findFirst();
    if (given.isPresent()) {
      if (!given.get().verdict().equals(request.verdict())) throw fail(409, "Your verdict for this report is already recorded.");
      return result(true, VERDICT_RECORDED, 0, write, null);
    }
    Guard guard = guard(write);
    requireCaseStarted(guard);
    if (!finals.solvedLayers(teamId).contains(layer)) throw fail(409, "Solve the layer first.");
    boolean correct = "YES".equals(request.verdict()) == requireContent().verdictYes(layer);
    finals.insertVerdict(teamId, layer, request.verdict(), correct, guard.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_FINAL_VERDICT", "team:" + teamId + " layer:" + layer);
    return result(true, VERDICT_RECORDED, 0, write, null);
  }

  // ---- the suspect ------------------------------------------------------------------------------

  /**
   * One time only. Records the choice, the active seconds of the final's timer at this moment (pauses excluded) and
   * finalises the team in the same guarded statement; it never reveals whether the suspect is right.
   */
  @Transactional(noRollbackFor = FinalTimeUp.class)
  public FinalActionResult submitSuspect(Principal principal, SuspectChoiceRequest request) {
    TeamWrite write = gate.open(principal, "final-suspect", request);
    TeamWriteGate.requireNotDisqualified(write);
    long teamId = write.team().id();
    int choice = request.suspectId();
    if (choice < 1 || choice > CryptoContent.SUSPECT_COUNT) throw fail(400, "Choose one of the listed suspects.");
    Optional<Row> existing = finals.find(teamId);
    if (existing.isPresent() && existing.get().suspectId() != null) {
      if (existing.get().suspectId() != choice) throw fail(409, "Your final answer is already recorded.");
      return result(true, SUBMITTED_MESSAGE, 0, write, FinalScreen.SUBMITTED);
    }
    Guard guard = guard(write);
    requireCaseStarted(guard);
    if (finals.solvedLayers(teamId).size() < CryptoContent.LAYER_COUNT) throw fail(409, "Solve all four layers first.");
    int active = FinalClock.activeSeconds(guard.timer(), guard.now());
    boolean correct = requireContent().isCorrectSuspect(choice);
    if (!finals.submitSuspect(teamId, choice, correct, guard.now(), active)) throw fail(409, "Your final answer is already recorded.");
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_FINAL_SUBMITTED", "team:" + teamId);
    return result(true, SUBMITTED_MESSAGE, 0, write, FinalScreen.SUBMITTED);
  }

  // ---- recovery ---------------------------------------------------------------------------------

  @Transactional(noRollbackFor = FinalTimeUp.class)
  public RecoveryResult recover(Principal principal, UUID idempotencyKey, RecoveryRequest request) {
    TeamWrite write = gate.open(principal, "recovery", request);
    long teamId = write.team().id();
    Optional<RecoveryAttemptRecord> previous = recoveries.findByIdempotencyKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return recoveryResult(previous.get(), currentStatus(write));
    }
    TeamWriteGate.requireNotDisqualified(write);
    requireMaxBytes(request.answer(), 500, "Recovery answer");
    Guard guard = guard(write);
    if (guard.facts().clues() != FinalStageResolver.REQUIRED_CLUES - 1) throw fail(409, "Recovery is not available for your team.");
    EventRoomRecord missingRoom = rooms.lockForUpdate(guard.facts().missingRoomId()).orElseThrow();
    boolean correct = requireContent().acceptsRecoveryAnswer(request.answer());
    RecoveryAttemptRecord attempt = new RecoveryAttemptRecord(
      UUID.randomUUID(),
      teamId,
      missingRoom.id(),
      request.answer(),
      correct,
      guard.now(),
      idempotencyKey,
      write.requestHash()
    );
    recoveries.insert(attempt);
    if (correct) clues.collectFromRecovery(teamId, missingRoom.id(), guard.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_RECOVERY_ATTEMPT", "recovery:" + attempt.id());
    return recoveryResult(attempt, correct ? FinalStage.IN_FINAL : FinalStage.RECOVERY);
  }

  // ---- shared -----------------------------------------------------------------------------------

  /** What every write learns after the gate: the locked state, the team's facts and row, the timer and the time. */
  private record Guard(EventStateRecord state, ClueFacts facts, Row row, RoundTimer timer, Instant now) {}

  /**
   * The checks every final write shares, in order: the final is open, the team qualified (at least 3 clues), its final is
   * not over, the timer has time left (a write that finds none finalises the team first) and is not paused.
   */
  private Guard guard(TeamWrite write) {
    EventStateRecord state = write.state();
    if (!state.finalOpen()) throw fail(409, "The final is not open.");
    long teamId = write.team().id();
    Instant now = clock.now();
    ClueFacts facts = finals.clueFacts(teamId);
    if (facts.clues() < FinalStageResolver.REQUIRED_CLUES - 1) throw fail(409, NOT_QUALIFIED + ".");
    Row row = finals.find(teamId).orElse(null);
    if (row != null && row.finalised()) throw fail(409, "Your final is already over.");
    RoundTimer timer = state.finalTimer().orElseThrow(() -> fail(409, "The final is not open."));
    RoundPhase phase = timer.effectivePhase(now);
    if (phase == RoundPhase.TIME_UP) {
      finals.finalise(teamId, "TIME_UP", now);
      states.bumpRevision();
      throw new FinalTimeUp();
    }
    if (phase == RoundPhase.PAUSED) throw fail(409, "The final is paused.");
    return new Guard(state, facts, row, timer, now);
  }

  private static void requireCaseStarted(Guard guard) {
    if (guard.facts().clues() < FinalStageResolver.REQUIRED_CLUES) throw fail(409, "Solve the Recovery Challenge first.");
    if (guard.row() == null || guard.row().unlockedAt() == null) throw fail(409, "Open the case first.");
    if (guard.row().caseStartedAt() == null) throw fail(409, "Start the case first.");
  }

  private static void requireNoWait(int waitSeconds) {
    if (waitSeconds > 0) throw fail(429, "Too many wrong answers. Wait " + waitSeconds + " seconds and try again.");
  }

  private CryptoContent requireContent() {
    return contents.content().orElseThrow(() -> fail(409, "The final content is not ready."));
  }

  private FinalActionResult result(boolean accepted, String message, int wait, TeamWrite write, FinalScreen screen) {
    return new FinalActionResult(accepted, message, wait, currentStatus(write), screen);
  }

  /** Replays answer with the original verdict; the screen is whatever the team's stored state says now. */
  private FinalActionResult replay(TeamWrite write, boolean correct, String wrongMessage, FinalScreen wrongScreen) {
    return new FinalActionResult(correct, correct ? "Accepted." : wrongMessage, 0, currentStatus(write), correct ? null : wrongScreen);
  }

  private FinalStage currentStatus(TeamWrite write) {
    ClueFacts facts = finals.clueFacts(write.team().id());
    boolean finalised = finals.find(write.team().id()).map(Row::finalised).orElse(false);
    return FinalStageResolver.resolve(write.state().finalEverOpened(), facts.clues(), finalised);
  }

  private RecoveryResult recoveryResult(RecoveryAttemptRecord attempt, FinalStage status) {
    return new RecoveryResult(attempt.id(), attempt.correct(), status, attempt.receivedAt());
  }
}
