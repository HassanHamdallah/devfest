package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.PointsLedger;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomScoring;
import dev.devfest.event.dto.PointsHistoryEntry;
import dev.devfest.event.dto.PointsRequest;
import dev.devfest.event.dto.PointsView;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.PointsEntryRecord;
import dev.devfest.event.repository.RoomPointsRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The points of a points-and-phone room (rules §21.1): the room's manager or the admin add or subtract whole points
 * per team at any time, as append-only ledger rows, and can undo the newest change. Only these two accounts reach it;
 * everyone else, and a room without the activity, gets the same 404. Every write carries an Idempotency-Key, so a
 * double click never counts twice. Each add and undo is audited with the team, room, change and new total only.
 */
@Service
public class RoomPointsService {

  static final String NOT_FOUND = "Room points not found.";
  static final String TEAM_NOT_FOUND = "Team not found.";
  static final String NOTHING_TO_UNDO = "There is nothing to undo.";
  static final String KEY_REUSED = "This request key was already used for a different change.";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final RoomPointsRepository points;
  private final AuditLog audit;
  private final EventClock clock;

  public RoomPointsService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    RoomPointsRepository points,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.points = points;
    this.audit = audit;
    this.clock = clock;
  }

  /** Adds (positive) or subtracts (negative) whole points; refused when the total would leave 0..100. */
  @Transactional
  public PointsView add(Principal principal, long roomId, long teamId, UUID key, PointsRequest request) {
    requirePointsRoom(principal, roomId);
    int delta = wholeDelta(request.delta());
    Optional<PointsEntryRecord> replay = points.findByKey(key);
    if (replay.isPresent()) return replay(replay.get(), teamId, roomId, false, delta);
    lock(roomId, teamId);
    List<PointsEntryRecord> entries = points.forTeam(teamId, roomId);
    int total = PointsLedger.total(ledger(entries));
    PointsLedger.problem(total, delta).ifPresent(problem -> {
      throw fail(400, problem);
    });
    points.insert(teamId, roomId, false, delta, total + delta, null, key, principal.id(), clock.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_POINTS_ADDED", "room:" + roomId + " team:" + teamId + " delta:" + delta + " total:" + (total + delta));
    return view(teamId, roomId, points.forTeam(teamId, roomId));
  }

  /** Reverses the newest change that is not an undo and was not undone (appends a reversing row); refused when none. */
  @Transactional
  public PointsView undo(Principal principal, long roomId, long teamId, UUID key) {
    requirePointsRoom(principal, roomId);
    Optional<PointsEntryRecord> replay = points.findByKey(key);
    if (replay.isPresent()) return replay(replay.get(), teamId, roomId, true, 0);
    lock(roomId, teamId);
    List<PointsEntryRecord> entries = points.forTeam(teamId, roomId);
    PointsLedger.Entry target = PointsLedger.undoTarget(ledger(entries)).orElseThrow(() -> fail(409, NOTHING_TO_UNDO));
    int total = PointsLedger.total(ledger(entries));
    int delta = -target.delta();
    points.insert(teamId, roomId, true, delta, total + delta, target.id(), key, principal.id(), clock.now());
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_POINTS_UNDONE", "room:" + roomId + " team:" + teamId + " delta:" + delta + " total:" + (total + delta));
    return view(teamId, roomId, points.forTeam(teamId, roomId));
  }

  /** The team's ledger, newest first. */
  @Transactional(readOnly = true)
  public List<PointsHistoryEntry> history(Principal principal, long roomId, long teamId) {
    requirePointsRoom(principal, roomId);
    teams.find(teamId).orElseThrow(() -> fail(404, TEAM_NOT_FOUND));
    List<PointsEntryRecord> entries = points.forTeam(teamId, roomId);
    Set<Long> undone = undoneIds(entries);
    return entries
      .reversed()
      .stream()
      .map(entry -> new PointsHistoryEntry(entry.id(), entry.undo(), entry.delta(), entry.totalAfter(), entry.enteredByName(), entry.enteredAt(), undone.contains(entry.id())))
      .toList();
  }

  /** What a board row shows for one team: its total, the 15-point score and the change an undo would reverse. */
  static PointsView view(long teamId, long roomId, List<PointsEntryRecord> entries) {
    List<PointsLedger.Entry> ledger = ledger(entries);
    int total = PointsLedger.total(ledger);
    Integer undoDelta = PointsLedger.undoTarget(ledger).map(PointsLedger.Entry::delta).orElse(null);
    return new PointsView(teamId, roomId, total, RoomScoring.pointsBase(total), undoDelta);
  }

  private PointsView replay(PointsEntryRecord found, long teamId, long roomId, boolean undo, int delta) {
    boolean same = found.teamId() == teamId && found.roomId() == roomId && found.undo() == undo && (undo || found.delta() == delta);
    if (!same) throw fail(409, KEY_REUSED);
    return view(teamId, roomId, points.forTeam(teamId, roomId));
  }

  /** Lock order as on every team write: event_state, the team, then the room. */
  private void lock(long roomId, long teamId) {
    states.lockForUpdate();
    teams.lockForUpdate(teamId).filter(team -> team.active()).orElseThrow(() -> fail(404, TEAM_NOT_FOUND));
    rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
  }

  private EventRoomRecord requirePointsRoom(Principal principal, long roomId) {
    EventRoomRecord room = access.requireOrganizerOf(principal, roomId, NOT_FOUND);
    if (room.activity() != RoomActivity.PHONE_POINTS) throw fail(404, NOT_FOUND);
    return room;
  }

  private static int wholeDelta(BigDecimal value) {
    if (value.stripTrailingZeros().scale() > 0) throw fail(400, "The change must be a whole number of points.");
    if (value.abs().compareTo(BigDecimal.valueOf(PointsLedger.MAX_DELTA)) > 0) throw fail(400, "The change must be from -100 to 100.");
    int delta = value.intValueExact();
    if (delta == 0) throw fail(400, "The change cannot be 0.");
    return delta;
  }

  private static List<PointsLedger.Entry> ledger(List<PointsEntryRecord> entries) {
    return entries.stream().map(PointsEntryRecord::entry).toList();
  }

  private static Set<Long> undoneIds(List<PointsEntryRecord> entries) {
    return entries.stream().filter(PointsEntryRecord::undo).map(PointsEntryRecord::reversesId).collect(Collectors.toCollection(HashSet::new));
  }
}
