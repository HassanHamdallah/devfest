package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.AnswerNormalizer;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.Receipt;
import dev.devfest.event.dto.SubmissionRequest;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.RoomSubmissionRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Room submissions: every accepted one is kept; the first correct clue answer collects the room's clue. */
@Service
public class EventSubmissionService {

  static final int RECENT_RECEIPTS = 50;

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final EventRoomRepository rooms;
  private final RoomSubmissionRepository submissions;
  private final CollectedClueRepository clues;
  private final EventStateRepository states;
  private final AuditLog audit;
  private final EventClock clock;

  public EventSubmissionService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    EventRoomRepository rooms,
    RoomSubmissionRepository submissions,
    CollectedClueRepository clues,
    EventStateRepository states,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.gate = gate;
    this.rooms = rooms;
    this.submissions = submissions;
    this.clues = clues;
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
    requireMaxBytes(request.technicalOutput(), 4000, "Technical output");
    requireMaxBytes(request.clueAnswer(), 500, "Clue answer");
    requireAcceptingSubmissions(write, room);
    RoomSubmissionRecord submission = store(write, room, idempotencyKey, request);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_ROOM_SUBMISSION", "submission:" + submission.id());
    return EventViews.receipt(submission);
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

  /** Contract order: rooms running, then the team's scheduled room, then the room being open. */
  private static void requireAcceptingSubmissions(TeamWrite write, EventRoomRecord room) {
    int round = write.state().currentRound();
    if (!Rotation.isRunningRound(round)) throw fail(409, "Rooms are not running right now.");
    Optional<RoomCode> scheduled = Rotation.roomFor(write.team().groupCode(), round);
    if (scheduled.isEmpty() || scheduled.get() != room.code()) {
      throw fail(409, "This is not your team's room in the current round.");
    }
    if (!room.open()) throw fail(409, "This room is closed.");
  }

  /** The clue verdict is fixed now and never recomputed, even if the key changes later. */
  private RoomSubmissionRecord store(TeamWrite write, EventRoomRecord room, UUID idempotencyKey, SubmissionRequest request) {
    Instant receivedAt = clock.now();
    RoomSubmissionRecord submission = new RoomSubmissionRecord(
      UUID.randomUUID(),
      write.team().id(),
      room.id(),
      room.code(),
      write.state().currentRound(),
      request.technicalOutput(),
      request.clueAnswer(),
      AnswerNormalizer.matches(request.clueAnswer(), room.clueKey()),
      receivedAt,
      idempotencyKey,
      write.requestHash()
    );
    submissions.insert(submission);
    if (submission.clueCorrect()) clues.collectFromRoom(write.team().id(), room.id(), submission.id(), receivedAt);
    return submission;
  }
}
