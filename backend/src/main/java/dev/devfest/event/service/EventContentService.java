package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.dto.AdminContent;
import dev.devfest.event.dto.AdminContentRequest;
import dev.devfest.event.dto.RoomContentRequest;
import dev.devfest.event.dto.RoomContentView;
import dev.devfest.event.dto.SuspectRequest;
import dev.devfest.event.dto.SuspectView;
import dev.devfest.event.repository.AccusationRepository;
import dev.devfest.event.repository.EventContentRecord;
import dev.devfest.event.repository.EventContentRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.SuspectRecord;
import dev.devfest.event.repository.SuspectRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event content edits. Everything becomes read-only once the final has been opened. Suspect changes do not
 * bump the event_content version, so an open message editor is not invalidated by a suspect edit.
 */
@Service
public class EventContentService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final EventContentRepository content;
  private final SuspectRepository suspects;
  private final AccusationRepository accusations;
  private final EventClueReviewService clueReview;
  private final AuditLog audit;

  public EventContentService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    EventContentRepository content,
    SuspectRepository suspects,
    AccusationRepository accusations,
    EventClueReviewService clueReview,
    AuditLog audit
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.content = content;
    this.suspects = suspects;
    this.accusations = accusations;
    this.clueReview = clueReview;
    this.audit = audit;
  }

  /** 4 SQL statements: event state, content, suspects, rooms. */
  public AdminContent adminContent(Principal principal) {
    access.requireAdmin(principal);
    return currentContent(states.state());
  }

  @Transactional
  public AdminContent updateAdminContent(Principal principal, AdminContentRequest request) {
    access.requireAdmin(principal);
    requireMaxBytes(request.encryptedMessage(), 4000, "Encrypted message");
    EventStateRecord state = states.lockForUpdate();
    requireContentEditable(state);
    if (request.correctSuspectId() != null) requireSuspect(request.correctSuspectId());
    if (!content.update(request.version(), request.encryptedMessage(), request.correctSuspectId())) {
      throw fail(409, "The event content was changed by someone else. Reload and try again.");
    }
    changed(principal, "EVENT_CONTENT", "content");
    return currentContent(state);
  }

  @Transactional
  public SuspectView createSuspect(Principal principal, SuspectRequest request) {
    access.requireAdmin(principal);
    requireSuspectText(request);
    requireContentEditable(states.lockForUpdate());
    int position = suspects
      .firstFreePosition()
      .orElseThrow(() -> fail(409, "There are already " + SuspectRepository.MAX_SUSPECTS + " suspects."));
    long id = suspects.insert(request.name().trim(), request.description().trim(), position);
    changed(principal, "EVENT_SUSPECT_CREATED", "suspect:" + id);
    return suspectView(suspects.find(id).orElseThrow());
  }

  @Transactional
  public SuspectView updateSuspect(Principal principal, long suspectId, SuspectRequest request) {
    access.requireAdmin(principal);
    requireSuspectText(request);
    requireContentEditable(states.lockForUpdate());
    requireSuspect(suspectId);
    suspects.update(suspectId, request.name().trim(), request.description().trim());
    changed(principal, "EVENT_SUSPECT_UPDATED", "suspect:" + suspectId);
    return suspectView(suspects.find(suspectId).orElseThrow());
  }

  /** The correct suspect and suspects named in an accusation are referenced records and stay. */
  @Transactional
  public AdminContent deleteSuspect(Principal principal, long suspectId) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    requireContentEditable(state);
    requireSuspect(suspectId);
    if (Objects.equals(content.get().correctSuspectId(), suspectId)) {
      throw fail(409, "Choose another correct suspect before deleting this one.");
    }
    if (accusations.existsForSuspect(suspectId)) throw fail(409, "This suspect was already named in an accusation.");
    suspects.delete(suspectId);
    changed(principal, "EVENT_SUSPECT_DELETED", "suspect:" + suspectId);
    return currentContent(state);
  }

  /** Call with the event_state row locked so the final cannot open between the check and the write. */
  public static void requireContentEditable(EventStateRecord lockedState) {
    if (lockedState.finalEverOpened()) throw fail(409, "Event content is read-only after the final opens.");
  }

  /**
   * A manager edits only their own room (ADMIN any room); the clue key and recovery answer are never logged.
   * An open room keeps a non-empty key. When the key accepts different answers than before, every submission
   * of the room is re-checked in this transaction (EventClueReviewService.recheckRoom).
   */
  @Transactional
  public RoomContentView updateRoomContent(Principal principal, long roomId, RoomContentRequest request) {
    access.requireManagerOf(principal, roomId);
    requireMaxBytes(request.clueKey(), 500, "Clue key");
    requireMaxBytes(request.recoveryPrompt(), 4000, "Recovery prompt");
    requireMaxBytes(request.recoveryAnswer(), 500, "Recovery answer");
    requireContentEditable(states.lockForUpdate());
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (room.open() && !ClueCheck.hasKey(request.clueKey())) {
      throw fail(409, "Close the room before removing its clue key.");
    }
    boolean written = rooms.updateContent(
      roomId,
      request.version(),
      request.clueKey(),
      request.recoveryPrompt(),
      request.recoveryAnswer()
    );
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
    EventContentRecord current = content.get();
    return new AdminContent(
      current.encryptedMessage(),
      current.correctSuspectId(),
      current.version(),
      suspects.findAll().stream().map(EventContentService::suspectView).toList(),
      rooms.findAll().stream().map(EventViews::roomContent).toList(),
      state.finalEverOpened()
    );
  }

  private void requireSuspect(long suspectId) {
    suspects.find(suspectId).orElseThrow(() -> fail(404, "Suspect not found."));
  }

  private static void requireSuspectText(SuspectRequest request) {
    requireMaxBytes(request.name(), 80, "Name");
    requireMaxBytes(request.description(), 300, "Description");
  }

  private void changed(Principal principal, String action, String target) {
    states.bumpRevision();
    audit.record(principal.id(), action, target);
  }

  private static SuspectView suspectView(SuspectRecord suspect) {
    return new SuspectView(suspect.id(), suspect.name(), suspect.description(), suspect.position());
  }
}
