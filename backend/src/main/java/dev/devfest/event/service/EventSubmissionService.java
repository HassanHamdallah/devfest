package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.domain.ClueOverride;
import dev.devfest.event.domain.ClueVerdict;
import dev.devfest.event.domain.PhoneNumber;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.Receipt;
import dev.devfest.event.dto.SubmissionRequest;
import dev.devfest.event.repository.ClueVerdictRepository;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.RoomSubmissionRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room submissions: every accepted one is kept with a clue verdict; an effectively correct clue answer
 * collects the room's clue. Key changes and overrides later re-evaluate the verdict (EventClueReviewService).
 */
@Service
public class EventSubmissionService {

  static final int RECENT_RECEIPTS = 50;
  static final String NO_CLUE_ENTERED = "(no clue entered)";
  static final String ACTIVITY_ROOM = "This room records its result from its activity; there is no answer form.";
  /** The technical output kept for a phone number answer (the team types the number only). */
  static final String PHONE_ANSWER = "Phone number answer";

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final EventRoomRepository rooms;
  private final RoomSubmissionRepository submissions;
  private final CollectedClueRepository clues;
  private final ClueVerdictRepository verdicts;
  private final TeamRoomExceptionRepository exceptions;
  private final EventStateRepository states;
  private final AuditLog audit;
  private final EventClock clock;

  public EventSubmissionService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    EventRoomRepository rooms,
    RoomSubmissionRepository submissions,
    CollectedClueRepository clues,
    ClueVerdictRepository verdicts,
    TeamRoomExceptionRepository exceptions,
    EventStateRepository states,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.gate = gate;
    this.rooms = rooms;
    this.submissions = submissions;
    this.clues = clues;
    this.verdicts = verdicts;
    this.exceptions = exceptions;
    this.states = states;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public Receipt submit(Principal principal, long roomId, UUID idempotencyKey, SubmissionRequest request) {
    TeamWrite write = gate.open(principal, Long.toString(roomId), request);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    Optional<RoomSubmissionRecord> previous = submissions.findByIdempotencyKey(write.team().id(), idempotencyKey);
    if (previous.isPresent()) {
      TeamWriteGate.requireSameRequest(previous.get().requestHash(), write);
      return EventViews.receipt(previous.get());
    }
    TeamWriteGate.requireNotDisqualified(write);
    requireMaxBytes(request.technicalOutput(), 4000, "Technical output");
    requireMaxBytes(request.clueAnswer(), 500, "Clue answer");
    Optional<RoomCode> exception = exceptions.find(write.state().currentRound(), write.team().id());
    requireAcceptingSubmissions(write, room, clock.now(), exception);
    boolean phoneRoom = room.activity() == RoomActivity.PHONE_POINTS;
    if (room.activity() != null && !phoneRoom) throw fail(409, ACTIVITY_ROOM);
    if (phoneRoom && !room.hasPhoneNumber()) throw fail(409, "This room is not ready yet. Ask the organizer.");
    // A phone number room compares the digits with the room's number; a correct one collects the room's existing key.
    boolean correct = phoneRoom ? PhoneNumber.matches(request.clueAnswer(), room.phoneNumber()) : ClueCheck.isCorrect(request.clueAnswer(), room.clueKey());
    String output = phoneRoom ? PHONE_ANSWER : request.technicalOutput();
    RoomSubmissionRecord submission = store(write, room, write.state().currentRound(), idempotencyKey, output, request.clueAnswer(), correct);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_ROOM_SUBMISSION", "submission:" + submission.id());
    return EventViews.receipt(submission);
  }

  /**
   * A room activity's result, recorded like a team's submission (rules §19.6): kept with a clue verdict, and a
   * correct result collects the room's clue. The verdict is the activity's, not a clue check, so later key changes
   * leave it alone; the manager's override works as for any submission. The caller holds the event_state, team and
   * room locks and bumps the revision.
   */
  RoomSubmissionRecord recordActivityResult(
    TeamWrite write,
    EventRoomRecord room,
    UUID idempotencyKey,
    String technicalOutput,
    String resultText,
    boolean correct
  ) {
    return store(write, room, write.state().currentRound(), idempotencyKey, technicalOutput, resultText, correct);
  }

  /**
   * Like {@link #recordActivityResult} for a result the organizer releases outside the team's own round (the contest
   * room, rules §20.6): {@code round} is the round in which the team's group visits the room, always 1..4, whatever
   * the event's current round.
   */
  RoomSubmissionRecord recordActivityResultForRound(
    TeamWrite write,
    EventRoomRecord room,
    int round,
    UUID idempotencyKey,
    String technicalOutput,
    String resultText,
    boolean correct
  ) {
    return store(write, room, round, idempotencyKey, technicalOutput, resultText, correct);
  }

  /**
   * The final answer of a LINUX_TERMINAL room's form, recorded through the ordinary submission path (rules §18.7):
   * the clue answer gets the automatic clue check against the room's key, a correct one collects the room's clue as
   * a ROOM clue, a blank or wrong one does not, and the manager's override and later key re-checks work as for any
   * submission. The caller holds the event_state, team and room locks and bumps the revision. The room's open flag
   * and the timer are not checked here: the team's own submit checked them, and the round's end submits at Time's up.
   */
  RoomSubmissionRecord recordFormAnswer(TeamWrite write, EventRoomRecord room, String technicalOutput, String clueAnswer) {
    boolean correct = ClueCheck.isCorrect(clueAnswer, room.clueKey());
    // A submission keeps at least one byte of clue answer; a blank one is stored as this note (and never matches).
    String stored = clueAnswer.isBlank() ? NO_CLUE_ENTERED : clueAnswer;
    // A random key: the form's own request key belongs to terminal_form_request, not to room_submission.
    return store(write, room, write.state().currentRound(), UUID.randomUUID(), technicalOutput, stored, correct);
  }

  public List<Receipt> recent(Principal principal) {
    long teamId = access.requireTeamOf(principal).id();
    return submissions.findRecentForTeam(teamId, RECENT_RECEIPTS).stream().map(EventViews::receipt).toList();
  }

  public Receipt find(Principal principal, UUID submissionId) {
    long teamId = access.requireTeamOf(principal).id();
    return submissions
      .findForTeam(teamId, submissionId)
      .map(EventViews::receipt)
      .orElseThrow(() -> fail(404, "Submission not found."));
  }

  /**
   * Contract order: rooms running, then the round's timer running (not ASSIGNED, PAUSED or TIME_UP, judged at
   * {@code now} under the event_state lock), then the team's scheduled room, then the room being open, then its
   * clue key being set (a room cannot open without one, so the last check is only a safety net). Exact retries
   * are answered before these checks, so a receipt issued before time ran out is still returned.
   */
  private static void requireAcceptingSubmissions(
    TeamWrite write,
    EventRoomRecord room,
    Instant now,
    Optional<RoomCode> exception
  ) {
    int round = write.state().currentRound();
    if (!Rotation.isRunningRound(round)) throw fail(409, "Rooms are not running right now.");
    RoundPhase phase = write.state().timer().map(timer -> timer.effectivePhase(now)).orElse(RoundPhase.TIME_UP);
    switch (phase) {
      case ASSIGNED -> throw fail(409, "The timer has not started yet.");
      case PAUSED -> throw fail(409, "The round is paused.");
      case TIME_UP -> throw fail(409, "Time is up for this round.");
      case RUNNING -> {}
    }
    // The admin's room exception for this round replaces the rotation (RoomAssignment is the one resolver).
    Optional<RoomCode> scheduled = RoomAssignment.roomFor(write.team().groupCode(), round, exception);
    if (scheduled.isEmpty() || scheduled.get() != room.code()) {
      throw fail(409, "This is not your team's room in the current round.");
    }
    if (!room.open()) throw fail(409, "This room is closed.");
    if (!ClueCheck.hasKey(room.clueKey())) throw fail(409, "This room is not ready yet. Ask the organizer.");
  }

  /** clue_correct records the result at submission time; the verdict row starts equal and may change later. */
  private RoomSubmissionRecord store(
    TeamWrite write,
    EventRoomRecord room,
    int round,
    UUID idempotencyKey,
    String technicalOutput,
    String clueAnswer,
    boolean clueCorrect
  ) {
    Instant receivedAt = clock.now();
    RoomSubmissionRecord submission = new RoomSubmissionRecord(
      UUID.randomUUID(),
      write.team().id(),
      room.id(),
      room.code(),
      round,
      technicalOutput,
      clueAnswer,
      clueCorrect,
      receivedAt,
      idempotencyKey,
      write.requestHash(),
      false
    );
    submissions.insert(submission);
    verdicts.insert(submission.id(), submission.clueCorrect(), receivedAt);
    boolean effective = ClueVerdict.effective(submission.clueCorrect(), ClueOverride.AUTOMATIC);
    if (effective) clues.collectFromRoom(write.team().id(), room.id(), submission.id(), receivedAt);
    return withEffective(submission, effective);
  }

  private static RoomSubmissionRecord withEffective(RoomSubmissionRecord s, boolean effective) {
    return new RoomSubmissionRecord(
      s.id(),
      s.teamId(),
      s.roomId(),
      s.roomCode(),
      s.round(),
      s.technicalOutput(),
      s.clueAnswer(),
      s.clueCorrect(),
      s.receivedAt(),
      s.idempotencyKey(),
      s.requestHash(),
      effective
    );
  }
}
