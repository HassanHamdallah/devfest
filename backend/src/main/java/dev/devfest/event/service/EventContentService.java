package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.dto.AdminContent;
import dev.devfest.event.dto.RoomContentRequest;
import dev.devfest.event.dto.RoomContentView;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room content edits. Everything becomes read-only once the final has been opened.
 */
@Service
public class EventContentService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final EventClueReviewService clueReview;
  private final AuditLog audit;

  public EventContentService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    EventClueReviewService clueReview,
    AuditLog audit
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.clueReview = clueReview;
    this.audit = audit;
  }

  /** 2 SQL statements: event state, rooms. */
  public AdminContent adminContent(Principal principal) {
    access.requireAdmin(principal);
    return currentContent(states.state());
  }

  /** Call with the event_state row locked so the final cannot open between the check and the write. */
  public static void requireContentEditable(EventStateRecord lockedState) {
    if (lockedState.finalEverOpened()) throw fail(409, "Event content is read-only after the final opens.");
  }

  /**
   * A manager edits only their own room (ADMIN any room); the clue key is never logged.
   * An open room keeps a non-empty key. When the key accepts different answers than before, every submission
   * of the room is re-checked in this transaction (EventClueReviewService.recheckRoom).
   */
  @Transactional
  public RoomContentView updateRoomContent(Principal principal, long roomId, RoomContentRequest request) {
    access.requireManagerOf(principal, roomId);
    requireMaxBytes(request.clueKey(), 500, "Clue key");
    requireContentEditable(states.lockForUpdate());
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (room.open() && !ClueCheck.hasKey(request.clueKey())) {
      throw fail(409, "Close the room before removing its clue key.");
    }
    boolean written = rooms.updateContent(roomId, request.version(), request.clueKey());
    if (!written) throw fail(409, "This room content was changed by someone else. Reload and try again.");
    int rechecked = 0;
    if (ClueCheck.keyChanged(room.clueKey(), request.clueKey())) {
      rechecked = clueReview.recheckRoom(roomId, request.clueKey());
      audit.record(principal.id(), "EVENT_CLUE_KEY_CHANGED", "room:" + roomId + " rechecked:" + rechecked);
    }
    changed(principal, "EVENT_ROOM_CONTENT", "room:" + roomId);
    return EventViews.roomContent(rooms.find(roomId).orElseThrow(), rechecked);
  }

  private AdminContent currentContent(EventStateRecord state) {
    return new AdminContent(rooms.findAll().stream().map(EventViews::roomContent).toList(), state.finalEverOpened());
  }

  private void changed(Principal principal, String action, String target) {
    states.bumpRevision();
    audit.record(principal.id(), action, target);
  }
}
