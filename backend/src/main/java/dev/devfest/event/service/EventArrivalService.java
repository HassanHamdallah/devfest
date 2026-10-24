package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.dto.RoomArrivalView;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.RoomArrivalRecord;
import dev.devfest.event.repository.RoomArrivalRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "All teams are here": a room's manager (or ADMIN) confirms that the teams expected in the room have arrived,
 * or withdraws it, while the current round is ASSIGNED. Readiness belongs to one round; a new round starts unready.
 */
@Service
public class EventArrivalService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final RoomArrivalRepository arrivals;
  private final AuditLog audit;
  private final EventClock clock;

  public EventArrivalService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    RoomArrivalRepository arrivals,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.arrivals = arrivals;
    this.audit = audit;
    this.clock = clock;
  }

  /** Lock order: event_state, then the room. Setting the state the room already has changes nothing. */
  @Transactional
  public RoomArrivalView setReady(Principal principal, long roomId, boolean ready) {
    access.requireManagerOf(principal, roomId);
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    boolean assigned = state.timer().map(timer -> timer.effectivePhase(now) == RoundPhase.ASSIGNED).orElse(false);
    if (!assigned) throw fail(409, "Arrival can only be confirmed before the round's timer starts.");
    rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    int round = state.currentRound();
    boolean changed = ready
      ? arrivals.confirm(round, roomId, principal.id(), now)
      : arrivals.withdraw(round, roomId);
    if (changed) {
      states.bumpRevision();
      audit.record(principal.id(), ready ? "EVENT_ROOM_READY" : "EVENT_ROOM_NOT_READY", "room:" + roomId + " round:" + round);
    }
    return view(arrivals.find(round, roomId));
  }

  public static RoomArrivalView view(Optional<RoomArrivalRecord> arrival) {
    return arrival
      .map(found -> new RoomArrivalView(true, found.confirmedByName(), found.confirmedAt()))
      .orElse(RoomArrivalView.WAITING);
  }
}
