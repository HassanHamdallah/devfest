package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.AnswerNormalizer;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.FinalStageResolver;
import dev.devfest.event.domain.PenaltyKind;
import dev.devfest.event.dto.AccusationRequest;
import dev.devfest.event.dto.AccusationResult;
import dev.devfest.event.dto.RecoveryRequest;
import dev.devfest.event.dto.RecoveryResult;
import dev.devfest.event.dto.TeamFinal;
import dev.devfest.event.dto.TeamFinal.FinalSuspect;
import dev.devfest.event.dto.TeamFinal.RecoveryChallenge;
import dev.devfest.event.dto.TeamFinal.TeamFinish;
import dev.devfest.event.repository.AccusationRecord;
import dev.devfest.event.repository.AccusationRepository;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventContentRepository;
import dev.devfest.event.repository.EventRankingRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.PenaltyRepository;
import dev.devfest.event.repository.RankedTeamRecord;
import dev.devfest.event.repository.RecoveryAttemptRecord;
import dev.devfest.event.repository.RecoveryAttemptRepository;
import dev.devfest.event.repository.SuspectRecord;
import dev.devfest.event.repository.SuspectRepository;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamFinalSummaryRecord;
import dev.devfest.event.repository.TeamFinishRecord;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The team's final: its screen, the one-room recovery and the accusations that finish the case. */
@Service
public class EventFinalService {

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final EventStateRepository states;
  private final TeamFinalRepository finals;
  private final EventRoomRepository rooms;
  private final SuspectRepository suspects;
  private final EventContentRepository contents;
  private final RecoveryAttemptRepository recoveries;
  private final AccusationRepository accusations;
  private final CollectedClueRepository clues;
  private final PenaltyRepository penalties;
  private final EventRankingRepository ranking;
  private final AuditLog audit;
  private final EventClock clock;

  public EventFinalService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    EventStateRepository states,
    TeamFinalRepository finals,
    EventRoomRepository rooms,
    SuspectRepository suspects,
    EventContentRepository contents,
    RecoveryAttemptRepository recoveries,
    AccusationRepository accusations,
    CollectedClueRepository clues,
    PenaltyRepository penalties,
    EventRankingRepository ranking,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.gate = gate;
    this.states = states;
    this.finals = finals;
    this.rooms = rooms;
    this.suspects = suspects;
    this.contents = contents;
    this.recoveries = recoveries;
    this.accusations = accusations;
    this.clues = clues;
    this.penalties = penalties;
    this.ranking = ranking;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * At most 6 SQL statements: team, event state, team summary; then RECOVERY +1 (missing room),
   * MESSAGE +2 (content, suspects), FINISHED +3 (content, suspects, ranking).
   */
  public TeamFinal view(Principal principal) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    TeamFinalSummaryRecord summary = finals.summary(team.id());
    FinalStage stage = stageOf(state, summary);
    boolean showsMessage = stage == FinalStage.MESSAGE || stage == FinalStage.FINISHED;
    return new TeamFinal(
      stage,
      state.finalStartedAt(),
      clock.now(),
      summary.clues(),
      stage == FinalStage.RECOVERY ? recoveryChallenge(summary) : null,
      showsMessage ? contents.get().encryptedMessage() : null,
      showsMessage ? finalSuspects() : List.of(),
      summary.accusationAttempts(),
      summary.wrongAccusations(),
      summary.penaltyMinutes(),
      stage == FinalStage.FINISHED ? finish(summary.finish()) : null,
      state.resultsPublished()
    );
  }

  @Transactional
  public RecoveryResult recover(Principal principal, UUID idempotencyKey, RecoveryRequest request) {
    TeamWrite write = gate.open(principal, "recovery", request);
    Optional<RecoveryAttemptRecord> previous = recoveries.findByIdempotencyKey(write.team().id(), idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return recoveryResult(previous.get(), currentStage(write));
    }
    TeamWriteGate.requireNotDisqualified(write);
    requireMaxBytes(request.answer(), 500, "Recovery answer");
    TeamFinalSummaryRecord summary = finals.summary(write.team().id());
    requireFinalOpen(write.state());
    if (stageOf(write.state(), summary) != FinalStage.RECOVERY) {
      throw fail(409, "Recovery is not available for your team.");
    }
    EventRoomRecord missingRoom = rooms.lockForUpdate(summary.missingRoomId()).orElseThrow();
    RecoveryAttemptRecord attempt = storeRecovery(write, missingRoom, idempotencyKey, request);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_RECOVERY_ATTEMPT", "recovery:" + attempt.id());
    return recoveryResult(attempt, attempt.correct() ? FinalStage.MESSAGE : FinalStage.RECOVERY);
  }

  @Transactional
  public AccusationResult accuse(Principal principal, UUID idempotencyKey, AccusationRequest request) {
    TeamWrite write = gate.open(principal, "accusation", request);
    Optional<AccusationRecord> previous = accusations.findByIdempotencyKey(write.team().id(), idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return accusationResult(previous.get(), currentStage(write));
    }
    TeamWriteGate.requireNotDisqualified(write);
    requireMaxBytes(request.decryptedMessage(), 4000, "Decrypted message");
    requireMaxBytes(request.evidence(), 4000, "Evidence");
    requireAccusationsAllowed(write);
    SuspectRecord suspect = suspects
      .find(request.suspectId())
      .orElseThrow(() -> fail(400, "Choose one of the listed suspects."));
    AccusationRecord accusation = storeAccusation(write, suspect, idempotencyKey, request);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_ACCUSATION", "accusation:" + accusation.id());
    return accusationResult(accusation, accusation.correct() ? FinalStage.FINISHED : FinalStage.MESSAGE);
  }

  private static FinalStage stageOf(EventStateRecord state, TeamFinalSummaryRecord summary) {
    return FinalStageResolver.resolve(state.finalEverOpened(), state.finalOpen(), summary.finished(), summary.clues());
  }

  /** Replays report the stage as it is now, not as it was when the original request was stored. */
  private FinalStage currentStage(TeamWrite write) {
    return stageOf(write.state(), finals.summary(write.team().id()));
  }

  private static void requireFinalOpen(EventStateRecord state) {
    if (!state.finalOpen()) throw fail(409, "The final is not open.");
  }

  private void requireAccusationsAllowed(TeamWrite write) {
    requireFinalOpen(write.state());
    FinalStage stage = currentStage(write);
    if (stage == FinalStage.FINISHED) throw fail(409, "Your team has already solved the case.");
    if (stage != FinalStage.MESSAGE) throw fail(409, "Accusations are not available for your team.");
  }

  private RecoveryAttemptRecord storeRecovery(
    TeamWrite write,
    EventRoomRecord room,
    UUID idempotencyKey,
    RecoveryRequest request
  ) {
    long teamId = write.team().id();
    Instant receivedAt = clock.now();
    boolean correct = AnswerNormalizer.matches(request.answer(), room.recoveryAnswer());
    RecoveryAttemptRecord attempt = new RecoveryAttemptRecord(
      UUID.randomUUID(),
      teamId,
      room.id(),
      request.answer(),
      correct,
      receivedAt,
      idempotencyKey,
      write.requestHash()
    );
    recoveries.insert(attempt);
    if (correct) {
      clues.collectFromRecovery(teamId, room.id(), receivedAt);
      penalties.insertRecoveryOnce(teamId, room.id(), write.team().accountId(), receivedAt);
    }
    return attempt;
  }

  /** The verdict compares ids only; a wrong answer adds the automatic penalty and reveals nothing. */
  private AccusationRecord storeAccusation(
    TeamWrite write,
    SuspectRecord suspect,
    UUID idempotencyKey,
    AccusationRequest request
  ) {
    long teamId = write.team().id();
    Instant receivedAt = clock.now();
    boolean correct = Long.valueOf(suspect.id()).equals(contents.get().correctSuspectId());
    AccusationRecord accusation = new AccusationRecord(
      UUID.randomUUID(),
      teamId,
      suspect.id(),
      request.decryptedMessage(),
      request.evidence(),
      correct,
      receivedAt,
      idempotencyKey,
      write.requestHash()
    );
    accusations.insert(accusation);
    if (correct) finals.insert(teamId, receivedAt, accusation.id());
    else penalties.insert(teamId, null, PenaltyKind.WRONG_ACCUSATION, "", write.team().accountId(), receivedAt);
    return accusation;
  }

  private RecoveryChallenge recoveryChallenge(TeamFinalSummaryRecord summary) {
    EventRoomRecord room = rooms.find(summary.missingRoomId()).orElseThrow();
    return new RecoveryChallenge(room.id(), room.code(), room.name(), room.recoveryPrompt(), summary.recoveryAttempts());
  }

  private List<FinalSuspect> finalSuspects() {
    return suspects
      .findAll()
      .stream()
      .map(suspect -> new FinalSuspect(suspect.id(), suspect.name(), suspect.description()))
      .toList();
  }

  /** Adjusted time comes from the one ranking definition so the team sees exactly what the ranking uses. */
  private TeamFinish finish(TeamFinishRecord finish) {
    RankedTeamRecord ranked = ranking
      .ranking()
      .stream()
      .filter(row -> row.teamId() == finish.teamId())
      .findFirst()
      .orElseThrow();
    return new TeamFinish(finish.finishedAt(), ranked.adjustedSeconds(), finish.finishingAccusationId(), finish.suspectName());
  }

  private static RecoveryResult recoveryResult(RecoveryAttemptRecord attempt, FinalStage stage) {
    return new RecoveryResult(attempt.id(), attempt.correct(), stage, attempt.receivedAt());
  }

  private static AccusationResult accusationResult(AccusationRecord accusation, FinalStage stage) {
    return new AccusationResult(accusation.id(), accusation.correct(), stage, accusation.receivedAt());
  }
}
