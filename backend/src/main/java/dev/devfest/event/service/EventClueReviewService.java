package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.domain.ClueOverride;
import dev.devfest.event.domain.ClueVerdict;
import dev.devfest.event.dto.ClueOverrideRequest;
import dev.devfest.event.dto.ClueVerdictView;
import dev.devfest.event.repository.ClueVerdictRecord;
import dev.devfest.event.repository.ClueVerdictRepository;
import dev.devfest.event.repository.ClueVerdictRepository.Recheck;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clue verdicts after submission: re-checks when a room's clue key changes, and the manager's per-submission
 * override (Accept / Reject / Automatic). Both recompute the ROOM clues they affect; RECOVERY clues stay.
 * Verdicts are final once the final opens, like all event content.
 */
@Service
public class EventClueReviewService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final ClueVerdictRepository verdicts;
  private final CollectedClueRepository clues;
  private final AuditLog audit;
  private final EventClock clock;

  public EventClueReviewService(
    EventAccessPolicy access,
    EventStateRepository states,
    ClueVerdictRepository verdicts,
    CollectedClueRepository clues,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.verdicts = verdicts;
    this.clues = clues;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Checks every submission of the room against {@code newKey} and recomputes the room's ROOM clues. Run inside
   * the caller's transaction with event_state and the room locked. Four SQL statements whatever the number of
   * submissions: read answers, update verdicts, drop invalid clues, collect new ones. Returns the count re-checked.
   */
  public int recheckRoom(long roomId, String newKey) {
    Instant now = clock.now();
    List<Recheck> rechecks = verdicts
      .answersForRoom(roomId)
      .stream()
      .map(answer -> {
        boolean auto = ClueCheck.isCorrect(answer.clueAnswer(), newKey);
        return new Recheck(answer.submissionId(), auto, ClueVerdict.effective(auto, answer.override()));
      })
      .toList();
    int rechecked = verdicts.applyRechecks(rechecks, now);
    clues.recomputeRoomClues(roomId, null);
    return rechecked;
  }

  /**
   * Repeating the verdict the submission already has is a no-op success (a retried request); any other change
   * needs the version the manager saw.
   */
  @Transactional
  public ClueVerdictView setOverride(Principal principal, long roomId, UUID submissionId, ClueOverrideRequest request) {
    access.requireManagerOf(principal, roomId);
    EventStateRecord state = states.lockForUpdate();
    ClueVerdictRecord current = verdicts
      .lockForUpdate(roomId, submissionId)
      .orElseThrow(() -> fail(404, "Submission not found."));
    if (state.finalEverOpened()) throw fail(409, "Clue verdicts cannot change after the final opens.");
    if (current.override() == request.override()) return EventViews.clueVerdict(current);
    if (current.version() != request.version()) {
      throw fail(409, "This clue verdict was changed by someone else. Reload and try again.");
    }
    boolean effective = ClueVerdict.effective(current.autoCorrect(), request.override());
    boolean automatic = request.override() == ClueOverride.AUTOMATIC;
    Instant now = clock.now();
    long version = current.version() + 1;
    verdicts.setOverride(submissionId, request.override(), effective, automatic ? null : principal.id(), automatic ? null : now, version);
    verdicts.insertHistory(
      submissionId,
      current.override(),
      request.override(),
      current.effectiveCorrect(),
      effective,
      principal.id(),
      now,
      version
    );
    clues.recomputeRoomClues(roomId, current.teamId());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_CLUE_OVERRIDE", "submission:" + submissionId);
    return EventViews.clueVerdict(verdicts.find(submissionId).orElseThrow());
  }
}
