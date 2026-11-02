package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.EventStateView;
import dev.devfest.event.dto.RoomOpenView;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's event controls: rooms, rounds, round length, final and publication. Requests that would not
 * change anything succeed without a write, a revision bump or an audit row, so repeated clicks are harmless.
 */
@Service
public class EventControlService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final EventBoardRepository boards;
  private final AuditLog audit;
  private final EventClock clock;

  public EventControlService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    EventBoardRepository boards,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.boards = boards;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public RoomOpenView setRoomOpen(Principal principal, long roomId, boolean open) {
    access.requireAdmin(principal);
    states.lockForUpdate();
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (open && !ClueCheck.hasKey(room.clueKey())) throw fail(409, "Set the clue key for this room before opening it.");
    if (room.open() != open) {
      rooms.setOpen(roomId, open);
      states.bumpRevision();
      audit.record(principal.id(), open ? "EVENT_ROOM_OPENED" : "EVENT_ROOM_CLOSED", "room:" + roomId);
    }
    return new RoomOpenView(room.id(), room.code(), open);
  }

  /** The client sends the absolute round (current + 1), so a repeated "Next round" click cannot skip a round. */
  @Transactional
  public EventStateView setRound(Principal principal, int round) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    if (state.currentRound() == round) return EventViews.eventState(state, now);
    states.setRound(round, Rotation.isRunningRound(round) ? now : null);
    return changed(principal, "EVENT_ROUND", "round:" + round, now);
  }

  @Transactional
  public EventStateView setRoundMinutes(Principal principal, int roundMinutes) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    if (state.roundMinutes() == roundMinutes) return EventViews.eventState(state, now);
    states.setRoundMinutes(roundMinutes);
    return changed(principal, "EVENT_ROUND_MINUTES", "minutes:" + roundMinutes, now);
  }

  /** Opens the final exactly once; a request while it is already open is a harmless repeat. */
  @Transactional
  public EventStateView openFinal(Principal principal) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    if (state.finalOpen()) return EventViews.eventState(state, now);
    if (state.finalEverOpened()) throw fail(409, "The final was already opened once.");
    requireCompleteContent();
    states.openFinal(now);
    return changed(principal, "EVENT_FINAL_OPENED", "final", now);
  }

  @Transactional
  public EventStateView closeFinal(Principal principal) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    if (!state.finalOpen()) throw fail(409, "The final is not open.");
    Instant now = clock.now();
    states.closeFinal(now);
    return changed(principal, "EVENT_FINAL_CLOSED", "final", now);
  }

  @Transactional
  public EventStateView setResultsPublished(Principal principal, boolean published) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    if (state.resultsPublished() == published) return EventViews.eventState(state, now);
    states.setResultsPublished(published);
    return changed(principal, published ? "EVENT_RESULTS_PUBLISHED" : "EVENT_RESULTS_UNPUBLISHED", "results", now);
  }

  /** Content writers lock event_state first, so nothing can be removed between this check and the opening. */
  private void requireCompleteContent() {
    List<String> missing = EventContentGaps
      .find(boards.contentStatus(), boards.adminRooms())
      .stream()
      .map(EventContentGaps.Gap::missingItem)
      .toList();
    if (!missing.isEmpty()) {
      throw fail(409, "Complete the event content before opening the final: " + String.join(", ", missing));
    }
  }

  private EventStateView changed(Principal principal, String action, String target, Instant now) {
    states.bumpRevision();
    audit.record(principal.id(), action, target);
    return EventViews.eventState(states.state(), now);
  }
}
