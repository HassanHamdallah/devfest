package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.domain.TerminalFormCause;
import dev.devfest.event.domain.TerminalFormStatus;
import dev.devfest.event.domain.TerminalFormValues;
import dev.devfest.event.dto.TerminalFormRequest;
import dev.devfest.event.dto.TerminalFormView;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.repository.TerminalFormRecord;
import dev.devfest.event.repository.TerminalFormRepository;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The team form of a LINUX_TERMINAL room (rules §18.7): five answers the team Saves as often as it likes and then
 * submits once. The final answer, by the team or at the round's end for a form with something saved, is recorded
 * through the ordinary room-submission path, so the room counts as finished and the clue check, evidence, boards and
 * the final keep working. The team only ever sees "Saved" or "Submitted". Saves do not bump the event revision
 * (nothing on other screens changes); a submission does, and the revision is broadcast after the commit.
 *
 * <p>Every write holds the event_state and team locks (TeamWriteGate), and the round's end holds the event_state
 * lock too, so the team's Final submit and Time's up run one after the other and the second finds the form
 * submitted (the guarded UPDATE in {@link TerminalFormRepository#submit} backs this up): one submission, one clue.
 */
@Service
public class TerminalFormService {

  static final String NOT_FOUND = "Form not found.";
  static final String ALREADY_SUBMITTED = "Your team has already submitted its final answer.";
  private static final String SAVE = "SAVE";
  private static final String SUBMIT = "SUBMIT";

  private final EventAccessPolicy access;
  private final TeamWriteGate gate;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final EventTeamRepository teams;
  private final TeamRoomExceptionRepository exceptions;
  private final TerminalFormRepository forms;
  private final EventSubmissionService submissions;
  private final AuditLog audit;
  private final EventClock clock;

  public TerminalFormService(
    EventAccessPolicy access,
    TeamWriteGate gate,
    EventStateRepository states,
    EventRoomRepository rooms,
    EventTeamRepository teams,
    TeamRoomExceptionRepository exceptions,
    TerminalFormRepository forms,
    EventSubmissionService submissions,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.gate = gate;
    this.states = states;
    this.rooms = rooms;
    this.teams = teams;
    this.exceptions = exceptions;
    this.forms = forms;
    this.submissions = submissions;
    this.audit = audit;
    this.clock = clock;
  }

  /** The team's form, also after Time's up (locked then). 5 statements: team, state, room, exception, form. */
  public TerminalFormView view(Principal principal, long roomId) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    EventRoomRecord room = rooms.find(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    requireAssigned(team, state, room, exceptionThisRound(state, team));
    return view(room.id(), forms.find(team.id(), room.id()));
  }

  /**
   * Saves the five values. Order: replay of the same Idempotency-Key (same request → the stored state, other request
   * → 409), disqualification, the team's form room this round (404), room open, timer running, not submitted yet,
   * then the values. Blanks are fine; the event revision is not bumped.
   */
  @Transactional
  public TerminalFormView save(Principal principal, long roomId, UUID key, TerminalFormRequest request) {
    TeamWrite write = gate.openActivity(principal, "form-save:" + roomId, request);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    long teamId = write.team().id();
    Optional<TerminalFormView> replay = replay(write, room, key, SAVE);
    if (replay.isPresent()) return replay.get();
    TeamWriteGate.requireNotDisqualified(write);
    Instant now = clock.now();
    requireWritable(write, room, now);
    TerminalFormValues values = valuesOf(request);
    forms.save(teamId, room.id(), values, now);
    forms.insertRequest(teamId, key, room.id(), SAVE, write.requestHash(), now);
    audit.record(principal.id(), "EVENT_TERMINAL_FORM_SAVED", "room:" + room.id() + " team:" + teamId);
    return view(room.id(), forms.find(teamId, room.id()));
  }

  /**
   * The team's Final submit: stores the values it carries, locks the form and records the final answer as a room
   * submission (clue check, ROOM clue when correct). Irreversible. Same order of checks as {@link #save}.
   */
  @Transactional
  public TerminalFormView submit(Principal principal, long roomId, UUID key, TerminalFormRequest request) {
    TeamWrite write = gate.openActivity(principal, "form-submit:" + roomId, request);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    long teamId = write.team().id();
    Optional<TerminalFormView> replay = replay(write, room, key, SUBMIT);
    if (replay.isPresent()) return replay.get();
    TeamWriteGate.requireNotDisqualified(write);
    Instant now = clock.now();
    requireWritable(write, room, now);
    TerminalFormValues values = valuesOf(request);
    record(write, room, values, TerminalFormCause.SUBMIT, now);
    forms.insertRequest(teamId, key, room.id(), SUBMIT, write.requestHash(), now);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_TERMINAL_FORM_SUBMITTED", "room:" + room.id() + " team:" + teamId);
    return view(room.id(), forms.find(teamId, room.id()));
  }

  /**
   * The round's end (Time's up by End round or removed time, or the next round): every active, not disqualified team
   * assigned to a LINUX_TERMINAL room this round whose form has something saved and is not submitted yet is
   * submitted, once. The caller holds the event_state lock and bumps the revision when the count is above zero.
   */
  int submitRoundEnd(long actorId, EventStateRecord state, Instant now) {
    int round = state.currentRound();
    if (!Rotation.isRunningRound(round)) return 0;
    List<EventRoomRecord> formRooms = rooms.findAll().stream().filter(room -> room.activity() == RoomActivity.LINUX_TERMINAL).toList();
    if (formRooms.isEmpty()) return 0;
    Map<Long, RoomCode> roundExceptions = exceptions.findForRound(round);
    List<EventTeamRecord> active = teams.findActive();
    int submitted = 0;
    for (EventRoomRecord formRoom : formRooms) {
      List<Long> due = forms
        .unsubmitted(formRoom.id())
        .stream()
        .filter(form -> form.values().hasContent())
        .map(TerminalFormRecord::teamId)
        .filter(teamId -> active.stream().anyMatch(team -> team.id() == teamId && !team.disqualified() && isAssigned(team, formRoom, round, roundExceptions)))
        .toList();
      if (due.isEmpty()) continue;
      // Lock order event_state → teams → room, as on every team write.
      List<EventTeamRecord> locked = teams.lockForUpdate(due);
      EventRoomRecord room = rooms.lockForUpdate(formRoom.id()).orElseThrow();
      for (EventTeamRecord team : locked) {
        Optional<TerminalFormRecord> form = forms.find(team.id(), room.id());
        if (form.isEmpty() || form.get().submitted()) continue;
        TeamWrite write = new TeamWrite(team, state, REQUEST_HASH);
        record(write, room, form.get().values(), TerminalFormCause.TIME_UP, now);
        audit.record(actorId, "EVENT_TERMINAL_FORM_SUBMITTED", "room:" + room.id() + " team:" + team.id() + " cause:TIME_UP");
        submitted++;
      }
    }
    return submitted;
  }

  private static final String REQUEST_HASH = AuthService.hash("terminal-form-time-up");

  /** Records the final answer as a room submission and locks the form with it. */
  private void record(TeamWrite write, EventRoomRecord room, TerminalFormValues values, TerminalFormCause cause, Instant now) {
    RoomSubmissionRecord submission = submissions.recordFormAnswer(write, room, values.summary(cause), values.finalClue());
    int locked = forms.submit(write.team().id(), room.id(), values, submission.id(), cause, now);
    if (locked != 1) throw new IllegalStateException("The form was submitted twice: team " + write.team().id() + ", room " + room.id());
  }

  /** The stored result of a request that was already handled; empty when the key is new. */
  private Optional<TerminalFormView> replay(TeamWrite write, EventRoomRecord room, UUID key, String kind) {
    Optional<TerminalFormRepository.StoredRequest> stored = forms.findRequest(write.team().id(), key);
    if (stored.isEmpty()) return Optional.empty();
    TeamWriteGate.requireSameRequest(stored.get().requestHash(), write);
    return Optional.of(view(room.id(), forms.find(write.team().id(), room.id())));
  }

  private static TerminalFormValues valuesOf(TerminalFormRequest request) {
    TerminalFormValues values = new TerminalFormValues(
      request.path(),
      request.username(),
      request.fileSize(),
      request.pattern(),
      request.finalClue()
    );
    values.problem().ifPresent(problem -> {
      throw fail(400, problem);
    });
    return values;
  }

  /** The team's form room this round, timer running, room open, form not submitted; same order as submissions. */
  private void requireWritable(TeamWrite write, EventRoomRecord room, Instant now) {
    var timer = requireAssigned(write.team(), write.state(), room, exceptionThisRound(write.state(), write.team()));
    switch (timer.effectivePhase(now)) {
      case ASSIGNED -> throw fail(409, "The timer has not started yet.");
      case PAUSED -> throw fail(409, "The round is paused.");
      case TIME_UP -> throw fail(409, "Time is up for this round.");
      case RUNNING -> {}
    }
    if (!room.open()) throw fail(409, "This room is closed.");
    if (forms.find(write.team().id(), room.id()).map(TerminalFormRecord::submitted).orElse(false)) {
      throw fail(409, ALREADY_SUBMITTED);
    }
  }

  /** 404 unless the room runs the Linux terminal activity and is the team's room in the current round. */
  private static dev.devfest.event.domain.RoundTimer requireAssigned(
    EventTeamRecord team,
    EventStateRecord state,
    EventRoomRecord room,
    Optional<RoomCode> exception
  ) {
    if (room.activity() != RoomActivity.LINUX_TERMINAL || !RoomAssignment.isIn(room.code(), team.groupCode(), state.currentRound(), exception)) {
      throw fail(404, NOT_FOUND);
    }
    return state.timer().orElseThrow(() -> fail(404, NOT_FOUND));
  }

  private static boolean isAssigned(EventTeamRecord team, EventRoomRecord room, int round, Map<Long, RoomCode> roundExceptions) {
    return RoomAssignment.isIn(room.code(), team.groupCode(), round, Optional.ofNullable(roundExceptions.get(team.id())));
  }

  private Optional<RoomCode> exceptionThisRound(EventStateRecord state, EventTeamRecord team) {
    int round = state.currentRound();
    return Rotation.isRunningRound(round) ? exceptions.find(round, team.id()) : Optional.empty();
  }

  private static TerminalFormView view(long roomId, Optional<TerminalFormRecord> form) {
    if (form.isEmpty()) {
      TerminalFormValues empty = TerminalFormValues.empty();
      return new TerminalFormView(roomId, TerminalFormStatus.EMPTY, empty.path(), empty.username(), empty.fileSize(), empty.pattern(), empty.finalClue(), null, null);
    }
    TerminalFormRecord record = form.get();
    TerminalFormValues values = record.values();
    return new TerminalFormView(
      roomId,
      record.submitted() ? TerminalFormStatus.SUBMITTED : TerminalFormStatus.SAVED,
      values.path(),
      values.username(),
      values.fileSize(),
      values.pattern(),
      values.finalClue(),
      record.savedAt(),
      record.submittedAt()
    );
  }
}
