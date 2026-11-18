package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.PhoneNumber;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.dto.PhoneNumberRequest;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The phone number answer of a points-and-phone room (rules §21.3), room content like the clue key: set by the room's
 * manager or the admin, read-only after the final opens, never sent to members and never written to the audit log. The
 * room's existing key is not touched. Everyone else, and a room without the activity, gets a 404.
 */
@Service
public class RoomPhoneNumberService {

  static final String NOT_FOUND = "Room not found.";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final AuditLog audit;

  public RoomPhoneNumberService(EventAccessPolicy access, EventStateRepository states, EventRoomRepository rooms, AuditLog audit) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.audit = audit;
  }

  /** Sets the number (empty clears it); the audit row names the room and whether a number is set, never the value. */
  @Transactional
  public void save(Principal principal, long roomId, PhoneNumberRequest request) {
    EventRoomRecord room = access.requireOrganizerOf(principal, roomId, NOT_FOUND);
    if (room.activity() != RoomActivity.PHONE_POINTS) throw fail(404, NOT_FOUND);
    String number = request.phoneNumber().strip();
    if (number.length() > PhoneNumber.MAX_CHARACTERS) throw fail(400, "The phone number can have at most 40 characters.");
    if (number.indexOf('\0') >= 0) throw fail(400, "The phone number must be text.");
    if (!number.isEmpty() && !PhoneNumber.isSet(number)) throw fail(400, "The phone number needs at least one digit.");
    EventContentService.requireContentEditable(states.lockForUpdate());
    rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    rooms.setPhoneNumber(roomId, number);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_PHONE_NUMBER_SET", "room:" + roomId + " set:" + !number.isEmpty());
  }
}
