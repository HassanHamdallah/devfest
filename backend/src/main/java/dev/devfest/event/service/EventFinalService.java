package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.api.Errors;
import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.AnswerNormalizer;
import dev.devfest.event.domain.AttemptThrottle;
import dev.devfest.event.domain.FinalPhase;
import dev.devfest.event.domain.FinalScreen;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.FinalStageResolver;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.domain.ShipmentContent;
import dev.devfest.event.domain.recovery.RecoveryPath;
import dev.devfest.event.dto.FinalActionResult;
import dev.devfest.event.dto.PassphraseRequest;
import dev.devfest.event.dto.SuspectChoiceRequest;
import dev.devfest.event.dto.TeamFinal;
import dev.devfest.event.dto.TeamFinal.CaseClue;
import dev.devfest.event.dto.TeamFinal.CaseSuspect;
import dev.devfest.event.dto.TeamFinal.KeyIndexEntry;
import dev.devfest.event.dto.TeamFinal.Mission;
import dev.devfest.event.dto.TeamFinal.RecoveredKey;
import dev.devfest.event.dto.TeamFinal.RecoveryPathView;
import dev.devfest.event.dto.TeamFinal.Submission;
import dev.devfest.event.dto.TeamFinal.Unlock;
import dev.devfest.event.dto.TerminalBundleInfo;
import dev.devfest.event.dto.UnlockRequest;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.FinalTerminalBundleRepository;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamFinalRepository.ClueAttempts;
import dev.devfest.event.repository.TeamFinalRepository.Row;
import dev.devfest.event.repository.TeamFinalRepository.Streak;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.service.FinalWriteGuard.Guard;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The team's final: its status (recovery first when it held fewer than 4 room keys when the final opened; the levels
 * themselves are {@link EventFinalRecoveryService}) and the case "The Last Shipment" (unlock, mission, then the case:
 * four evidence files opened by their passphrases in any order, and one suspect). Every rule is enforced here on
 * the server and the screen is always derived from stored facts, so a refresh resumes exactly where the team was.
 * Nothing returned to a member holds a report of an unsolved clue, a passphrase hash, the correct suspect or a score.
 *
 * <p>All writes take the event_state lock first (TeamWriteGate), so the final's single timer, a team's submission and
 * the time-up finalisation are serialized: whichever comes first wins and a team's final is finalised exactly once.
 * There is no scheduler; a write that finds the timer at zero finalises the team in its own transaction (kept through
 * {@link FinalTimeUp}) and the admin's close finalises everyone still open. The case's two phases follow the team's own
 * case start S (rules section 9).
 */
@Service
public class EventFinalService {

  static final String UNLOCK_WRONG = "These room outputs do not open the case.";
  static final String PASSPHRASE_WRONG = "That passphrase does not open this clue.";
  static final String PASSPHRASE_RIGHT = "The evidence file is decrypted.";
  static final String SUBMITTED_MESSAGE = "Your final answer is recorded. The result stays confidential until judging.";
  static final String TIME_UP_MESSAGE = "Time is up. The final is over for your team.";
  static final String DECRYPT_FIRST = "Decrypt all four files to open the investigation.";
  static final String RECOVERY_FIRST = "Finish your recovery levels first.";
  static final String KEYS_MISSING = "Your team does not hold all four room keys. Ask the organizer.";
  /** At most this many UTF-8 bytes of a typed passphrase (the attempt row's limit). */
  static final int MAX_PASSPHRASE_BYTES = 400;
  private static final Set<FinalScreen> CONTENT_SCREENS = Set.of(FinalScreen.MISSION, FinalScreen.CASE, FinalScreen.SUBMITTED);

  /** The final's time ran out; thrown after the team was finalised, so the finalisation commits with the refusal. */
  public static final class FinalTimeUp extends Errors.ApiException {

    FinalTimeUp() {
      super(409, "Time is up for the final.");
    }
  }

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final FinalWriteGuard guards;
  private final EventStateRepository states;
  private final TeamFinalRepository finals;
  private final EventRoomRepository rooms;
  private final CollectedClueRepository clues;
  private final FinalContentReader contents;
  private final FinalTerminalBundleRepository terminalBundle;
  private final EventBoardRepository boards;
  private final TeamRoomExceptionRepository exceptions;
  private final AuditLog audit;
  private final EventClock clock;
  private final ObjectMapper json;

  public EventFinalService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    FinalWriteGuard guards,
    EventStateRepository states,
    TeamFinalRepository finals,
    EventRoomRepository rooms,
    CollectedClueRepository clues,
    FinalContentReader contents,
    FinalTerminalBundleRepository terminalBundle,
    EventBoardRepository boards,
    TeamRoomExceptionRepository exceptions,
    AuditLog audit,
    EventClock clock,
    ObjectMapper json
  ) {
    this.access = access;
    this.gate = gate;
    this.guards = guards;
    this.states = states;
    this.finals = finals;
    this.rooms = rooms;
    this.clues = clues;
    this.contents = contents;
    this.terminalBundle = terminalBundle;
    this.boards = boards;
    this.exceptions = exceptions;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  // ---- the screen -------------------------------------------------------------------------------

  /** What a team's screen is derived from, read without locks. */
  private record Situation(EventStateRecord state, Instant now, TeamFinalRepository.ClueFacts facts, Row row, FinalStage status, FinalScreen screen) {}

  private Situation situation(EventTeamRecord team) {
    EventStateRecord state = states.state();
    Instant now = clock.now();
    TeamFinalRepository.ClueFacts facts = finals.clueFacts(team.id());
    Row row = finals.find(team.id()).orElse(null);
    boolean finalised = (row != null && row.finalised()) || FinalClock.timeUp(state, now);
    FinalStage status = FinalStageResolver.resolve(state.finalEverOpened(), facts.keysAtOpen(), facts.recoveryFinished(), finalised);
    return new Situation(state, now, facts, row, status, screenOf(status, row));
  }

  /** The screen the team's final shows now (the final's terminal package is served only on CASE). */
  public FinalScreen screen(EventTeamRecord team) {
    return situation(team).screen();
  }

  /**
   * A constant number of SQL statements whatever the number of teams: team, event state, clue facts, the team's final
   * row, the rooms, the team's evidence (rooms + exceptions), the content (mission, case and submitted screens), the
   * unlock streak and recovered keys (unlock screen) and, on the case screen, the team's passphrase attempts and the
   * final's terminal package summary.
   */
  public TeamFinal view(Principal principal) {
    EventTeamRecord team = access.requireTeamOf(principal);
    Situation at = situation(team);
    EventStateRecord state = at.state();
    Instant now = at.now();
    FinalScreen screen = at.screen();
    Optional<RoundTimer> timer = state.finalTimer();
    List<EventRoomRecord> roomList = rooms.findAll();
    var evidence = EventViews.evidence(team, state, boards.teamRooms(team.id()), exceptions.findForTeam(team.id()), now);
    Optional<ShipmentContent> content = CONTENT_SCREENS.contains(screen) ? contents.content() : Optional.empty();
    int wait = 0;
    if (screen == FinalScreen.UNLOCK) {
      Streak streak = finals.unlockStreak(team.id());
      wait = AttemptThrottle.waitSeconds(streak.wrongInARow(), streak.lastAttemptAt(), now);
    }
    ShipmentContent caseFile = screen == FinalScreen.CASE ? content.orElse(null) : null;
    boolean inCase = screen == FinalScreen.MISSION || screen == FinalScreen.CASE;
    int phase1 = content.map(ShipmentContent::phase1OrDefault).orElse(FinalPhase.DEFAULT_PHASE1_MINUTES);
    Integer caseStart = at.facts().caseStartSeconds();
    FinalPhase phase = inCase && content.isPresent() && caseStart != null ? FinalPhase.of(at.facts().allDecryptedAtSeconds()) : null;
    boolean investigating = phase == FinalPhase.INVESTIGATION;
    return new TeamFinal(
      at.status(),
      screen,
      now,
      state.finalStartedAt(),
      timer.map(t -> t.effectivePhase(now)).orElse(null),
      timer.map(t -> t.remainingSeconds(now)).orElse(null),
      phase,
      phase == null ? null : phase1,
      at.facts().clues(),
      wait,
      screen == FinalScreen.UNLOCK ? unlockView(team, roomList) : null,
      screen == FinalScreen.RECOVERY ? new RecoveryPathView(RecoveryPath.requiredLevels(at.facts().keysAtOpen())) : null,
      (screen == FinalScreen.MISSION || screen == FinalScreen.CASE) ? content.map(EventFinalService::mission).orElse(null) : null,
      caseFile == null ? List.of() : caseClues(caseFile, finals.clueAttempts(team.id()), now, investigating),
      caseFile == null ? List.of() : caseFile.keyIndex().stream().map(k -> new KeyIndexEntry(k.reference(), k.key())).toList(),
      caseFile == null || !investigating ? List.of() : caseFile.suspects().stream().map(s -> new CaseSuspect(s.id(), s.name(), s.position(), s.statement())).toList(),
      caseFile == null ? null : terminalInfo(),
      at.row() != null && at.row().suspectId() != null,
      screen == FinalScreen.SUBMITTED ? submission(team, at.status(), at.row(), content.orElse(null), now) : null,
      state.resultsPublished(),
      evidence
    );
  }

  private static FinalScreen screenOf(FinalStage status, Row row) {
    return switch (status) {
      case LOCKED -> FinalScreen.LOCKED;
      case RECOVERY -> FinalScreen.RECOVERY;
      case COMPLETED -> FinalScreen.SUBMITTED;
      case IN_FINAL -> {
        if (row == null || row.unlockedAt() == null) yield FinalScreen.UNLOCK;
        yield row.caseStartedAt() == null ? FinalScreen.MISSION : FinalScreen.CASE;
      }
    };
  }

  /** The four room names and, for this team only, the keys its recovery granted. */
  private Unlock unlockView(EventTeamRecord team, List<EventRoomRecord> roomList) {
    List<RecoveredKey> recovered = clues.recoveredKeys(team.id()).stream().map(k -> new RecoveredKey(k.roomName(), k.clueKey())).toList();
    return new Unlock(roomList.stream().map(EventRoomRecord::name).toList(), recovered);
  }

  private static Mission mission(ShipmentContent content) {
    ShipmentContent.Mission m = content.mission();
    return new Mission(m.title(), m.intro(), m.phase1(), m.phase2());
  }

  /** The four evidence files; a report is included only for a file the team has decrypted. */
  private static List<CaseClue> caseClues(ShipmentContent content, Map<Integer, ClueAttempts> attempts, Instant now, boolean investigating) {
    return content
      .clues()
      .stream()
      .map(clue -> {
        ClueAttempts tried = attempts.get(clue.number());
        boolean solved = tried != null && tried.solved();
        int wait = tried == null || solved || investigating ? 0 : AttemptThrottle.waitSeconds(tried.wrong(), tried.lastAttemptAt(), now);
        return new CaseClue(
          clue.number(),
          clue.title(),
          clue.encFile(),
          clue.rsaFile(),
          clue.keyReference(),
          solved,
          wait,
          solved ? clue.report() : null
        );
      })
      .toList();
  }

  /** What the case terminal needs before it downloads the final's package (null while none is uploaded). */
  private TerminalBundleInfo terminalInfo() {
    return terminalBundle.find().map(b -> new TerminalBundleInfo(b.sha256(), b.archiveBytes(), b.etcPasswd(), b.etcGroup(), null)).orElse(null);
  }

  private static Submission submission(EventTeamRecord team, FinalStage status, Row row, ShipmentContent content, Instant now) {
    boolean timeUp = row == null || !"SUBMITTED".equals(row.finalisedCause());
    Instant at = row != null && row.finalisedAt() != null ? row.finalisedAt() : now;
    String suspectId = timeUp ? null : row.suspectId();
    String suspectName = suspectId == null || content == null
      ? null
      : content.suspect(suspectId).map(ShipmentContent.Suspect::name).orElse(null);
    return new Submission(
      team.name(),
      status,
      at,
      timeUp ? TIME_UP_MESSAGE : SUBMITTED_MESSAGE,
      suspectId,
      suspectName,
      suspectId == null ? null : row.report()
    );
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
    Guard guard = guards.check(write);
    requireAllKeys(guard);
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
    Guard guard = guards.check(write);
    if (guard.row() == null || guard.row().unlockedAt() == null) throw fail(409, "Open the case first.");
    if (guard.row().caseStartedAt() != null) return result(true, "The case is started.", 0, write, FinalScreen.CASE);
    finals.markCaseStarted(write.team().id(), guard.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_FINAL_CASE_STARTED", "team:" + write.team().id());
    return result(true, "The case is started.", 0, write, FinalScreen.CASE);
  }

  // ---- passphrases ------------------------------------------------------------------------------

  /**
   * One evidence file's passphrase, in any order: surrounding whitespace and case do not matter (see Passphrase). A
   * correct one decrypts the file for good; a wrong one says only that it does not open the file. Five wrong in a row
   * on one file start a 30-second wait for that file only; an answer refused during the wait is not an attempt.
   */
  @Transactional(noRollbackFor = FinalTimeUp.class)
  public FinalActionResult submitPassphrase(Principal principal, int clue, UUID idempotencyKey, PassphraseRequest request) {
    TeamWrite write = gate.open(principal, "final-clue:" + clue, request);
    long teamId = write.team().id();
    Optional<TeamFinalRepository.StoredAttempt> previous = finals.findClueAttemptByKey(teamId, idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return replay(write, previous.get().correct(), PASSPHRASE_WRONG, FinalScreen.CASE);
    }
    TeamWriteGate.requireNotDisqualified(write);
    if (clue < 1 || clue > ShipmentContent.CLUE_COUNT) throw fail(404, "Clue not found.");
    requireMaxBytes(request.passphrase(), MAX_PASSPHRASE_BYTES, "Passphrase");
    Guard guard = guards.check(write);
    requireCaseStarted(guard);
    ShipmentContent content = requireContent();
    ClueAttempts tried = finals.clueAttempts(teamId).get(clue);
    if (tried != null && tried.solved()) throw fail(409, "This evidence file is already decrypted.");
    int wrongInARow = tried == null ? 0 : tried.wrong();
    requireNoWait(AttemptThrottle.waitSeconds(wrongInARow, tried == null ? null : tried.lastAttemptAt(), guard.now()));
    boolean correct = content.opens(clue, request.passphrase());
    finals.insertClueAttempt(UUID.randomUUID(), teamId, clue, request.passphrase(), correct, guard.now(), idempotencyKey, write.requestHash());
    if (correct) {
      // The fourth accepted passphrase opens the investigation at once (FinalPhase).
      if (finals.clueAttempts(teamId).values().stream().filter(ClueAttempts::solved).count() == ShipmentContent.CLUE_COUNT) {
        finals.markAllDecrypted(teamId, FinalClock.activeSeconds(guard.timer(), guard.now()));
      }
      states.bumpRevision();
      audit.record(principal.id(), "EVENT_FINAL_CLUE_SOLVED", "team:" + teamId + " clue:" + clue);
      return result(true, PASSPHRASE_RIGHT, 0, write, FinalScreen.CASE);
    }
    int wait = AttemptThrottle.waitSeconds(wrongInARow + 1, guard.now(), guard.now());
    return result(false, PASSPHRASE_WRONG, wait, write, FinalScreen.CASE);
  }

  // ---- the suspect ------------------------------------------------------------------------------

  /**
   * One time only, on the case screen. Records the choice, the active seconds of the final's timer at this moment
   * (pauses excluded) and finalises the team in the same guarded statement; it never reveals whether the suspect is
   * right. The optional Final Investigation Report of the request is stored in the same statement, so it is recorded
   * once and never changes; it never affects a score and the audit entry never carries its text. It does not need the
   * four files; the time points do (FinalScoring).
   */
  @Transactional(noRollbackFor = FinalTimeUp.class)
  public FinalActionResult submitSuspect(Principal principal, SuspectChoiceRequest request) {
    TeamWrite write = gate.open(principal, "final-suspect", request);
    TeamWriteGate.requireNotDisqualified(write);
    long teamId = write.team().id();
    String choice = request.suspectId().strip();
    Optional<Row> existing = finals.find(teamId);
    if (existing.isPresent() && existing.get().suspectId() != null) {
      if (!existing.get().suspectId().equals(choice)) throw fail(409, "Your final answer is already recorded.");
      return result(true, SUBMITTED_MESSAGE, 0, write, FinalScreen.SUBMITTED);
    }
    Guard guard = guards.check(write);
    requireCaseStarted(guard);
    ShipmentContent content = requireContent();
    if (FinalPhase.of(guard.facts().allDecryptedAtSeconds()) == FinalPhase.DECRYPTION) throw fail(409, DECRYPT_FIRST);
    if (content.suspect(choice).isEmpty()) throw fail(400, "Choose one of the listed suspects.");
    int active = FinalClock.activeSeconds(guard.timer(), guard.now());
    boolean correct = content.isCorrectSuspect(choice);
    if (!finals.submitSuspect(teamId, choice, correct, guard.now(), active, request.report())) throw fail(409, "Your final answer is already recorded.");
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_FINAL_SUBMITTED", "team:" + teamId);
    return result(true, SUBMITTED_MESSAGE, 0, write, FinalScreen.SUBMITTED);
  }

  // ---- shared -----------------------------------------------------------------------------------

  /**
   * The team may open the case: its recovery is finished (or it needed none) and it holds all four room keys (a key an
   * admin revoked must be granted again).
   */
  private static void requireAllKeys(Guard guard) {
    if (guard.facts().caseStartSeconds() == null) throw fail(409, RECOVERY_FIRST);
    if (guard.facts().clues() < FinalStageResolver.REQUIRED_CLUES) throw fail(409, KEYS_MISSING);
  }

  /** The case screen: all keys, unlocked and started (a finalised team was already refused by the guard). */
  private static void requireCaseStarted(Guard guard) {
    requireAllKeys(guard);
    if (guard.row() == null || guard.row().unlockedAt() == null) throw fail(409, "Open the case first.");
    if (guard.row().caseStartedAt() == null) throw fail(409, "Start the case first.");
  }

  private static void requireNoWait(int waitSeconds) {
    if (waitSeconds > 0) throw fail(429, "Too many wrong answers. Wait " + waitSeconds + " seconds and try again.");
  }

  private ShipmentContent requireContent() {
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
    TeamFinalRepository.ClueFacts facts = finals.clueFacts(write.team().id());
    boolean finalised = finals.find(write.team().id()).map(Row::finalised).orElse(false);
    return FinalStageResolver.resolve(write.state().finalEverOpened(), facts.keysAtOpen(), facts.recoveryFinished(), finalised);
  }
}
