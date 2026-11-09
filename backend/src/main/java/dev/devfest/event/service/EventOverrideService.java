package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireReason;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.domain.GroupCapacity;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.domain.RotationPreview;
import dev.devfest.event.dto.ManualClueRequest;
import dev.devfest.event.dto.MoveTeamRequest;
import dev.devfest.event.dto.OverrideOutcome;
import dev.devfest.event.dto.RoomExceptionRequest;
import dev.devfest.event.dto.RotationPreviewView;
import dev.devfest.event.dto.SwapRequest;
import dev.devfest.event.repository.CollectedClueRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.RoomSubmissionRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's overrides for real-world problems: swap or move teams between groups (with a rotation preview),
 * a per-team room for the current round, manual clues and disqualification. ADMIN only; each
 * needs a reason, is audited with it and bumps the revision, in one transaction with the standard lock order
 * (event_state → teams by ascending id → room). Requests that would change nothing succeed without a write.
 */
@Service
public class EventOverrideService {

  /** Audit actions shown as "Recent overrides" on the admin board. */
  public static final List<String> OVERRIDE_ACTIONS = List.of(
    "EVENT_TEAMS_SWAPPED",
    "EVENT_TEAM_MOVED",
    "EVENT_ROOM_EXCEPTION_SET",
    "EVENT_ROOM_EXCEPTION_CLEARED",
    "EVENT_CLUE_GRANTED",
    "EVENT_CLUE_REVOKED",
    "EVENT_TEAM_DISQUALIFIED",
    "EVENT_TEAM_REINSTATED",
    "EVENT_FINAL_RESET",
    "EVENT_TEAM_RESET",
    "EVENT_RESET",
    "EVENT_SNAPSHOT"
  );

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final TeamRoomExceptionRepository exceptions;
  private final CollectedClueRepository clues;
  private final RoomSubmissionRepository submissions;
  private final AuditLog audit;
  private final EventClock clock;

  public EventOverrideService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    TeamRoomExceptionRepository exceptions,
    CollectedClueRepository clues,
    RoomSubmissionRepository submissions,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.exceptions = exceptions;
    this.clues = clues;
    this.submissions = submissions;
    this.audit = audit;
    this.clock = clock;
  }

  // ---- O1 swap ------------------------------------------------------------------------------------

  public List<RotationPreviewView> swapPreview(Principal principal, long teamA, long teamB) {
    access.requireAdmin(principal);
    EventTeamRecord a = requireTeam(teamA);
    EventTeamRecord b = requireTeam(teamB);
    requireSwappable(a, b);
    EventStateRecord state = states.state();
    Map<RoomCode, String> names = roomNames();
    return List.of(preview(state, a, b.groupCode(), names), preview(state, b, a.groupCode(), names));
  }

  /** Exchanges the groups of two active teams; group sizes, and so capacity, are unchanged. */
  @Transactional
  public OverrideOutcome swap(Principal principal, SwapRequest request) {
    access.requireAdmin(principal);
    String reason = requireReason(request.reason());
    requireTeam(request.teamA());
    requireTeam(request.teamB());
    EventStateRecord state = states.lockForUpdate();
    List<EventTeamRecord> locked = teams.lockForUpdate(List.of(request.teamA(), request.teamB()));
    EventTeamRecord a = locked.stream().filter(team -> team.id() == request.teamA()).findFirst().orElseThrow();
    EventTeamRecord b = locked.stream().filter(team -> team.id() == request.teamB()).findFirst().orElseThrow();
    requireSwappable(a, b);
    Map<RoomCode, String> names = roomNames();
    List<String> warnings = new ArrayList<>(preview(state, a, b.groupCode(), names).warnings());
    warnings.addAll(preview(state, b, a.groupCode(), names).warnings());
    teams.setGroup(a.id(), b.groupCode());
    teams.setGroup(b.id(), a.groupCode());
    changed(principal, "EVENT_TEAMS_SWAPPED", "team:" + a.id() + " team:" + b.id(), reason);
    String message = "%s moved to group %s and %s to group %s.".formatted(a.name(), b.groupCode(), b.name(), a.groupCode());
    return new OverrideOutcome(message, warnings, null);
  }

  // ---- O2 move ------------------------------------------------------------------------------------

  public List<RotationPreviewView> movePreview(Principal principal, long teamId, GroupCode group) {
    access.requireAdmin(principal);
    EventTeamRecord team = requireTeam(teamId);
    return List.of(preview(states.state(), team, group, roomNames()));
  }

  /**
   * Moves a team to another group within the cap ceil(N / 4). Once round 1 has started the request must carry
   * {@code confirmImpact: true}: the admin saw the preview of missed or repeated rooms.
   */
  @Transactional
  public OverrideOutcome move(Principal principal, long teamId, MoveTeamRequest request) {
    access.requireAdmin(principal);
    String reason = requireReason(request.reason());
    EventStateRecord state = states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    if (team.groupCode() == request.group()) return OverrideOutcome.done(team.name() + " is already in group " + request.group() + ".");
    if (state.currentRound() >= Rotation.FIRST_ROUND && !Boolean.TRUE.equals(request.confirmImpact())) {
      throw fail(409, "Groups are locked after round 1 starts. Review the preview and confirm the impact to move the team.");
    }
    int max = GroupCapacity.maxPerGroup(state.plannedTeamCount());
    if (team.active() && teams.countOtherActiveInGroup(request.group(), teamId) >= max) {
      throw fail(409, "Group " + request.group() + " already has " + max + " active teams. Swap the team with one in that group instead.");
    }
    List<String> warnings = preview(state, team, request.group(), roomNames()).warnings();
    teams.setGroup(teamId, request.group());
    changed(principal, "EVENT_TEAM_MOVED", "team:" + teamId + " group:" + request.group(), reason);
    return new OverrideOutcome(team.name() + " moved to group " + request.group() + ".", warnings, null);
  }

  // ---- O3 room exception --------------------------------------------------------------------------

  /** Warnings for sending the team to the room this round; nothing is changed. */
  public List<String> roomExceptionPreview(Principal principal, long teamId, long roomId) {
    access.requireAdmin(principal);
    EventStateRecord state = states.state();
    return exceptionWarnings(state, requireTeam(teamId), requireRoom(roomId));
  }

  /** Sends the team to the room for the current round only; the rotation applies again from the next round. */
  @Transactional
  public OverrideOutcome setRoomException(Principal principal, RoomExceptionRequest request) {
    access.requireAdmin(principal);
    String reason = requireReason(request.reason());
    EventStateRecord state = states.lockForUpdate();
    int round = requireRunningRound(state);
    EventTeamRecord team = teams.lockForUpdate(request.teamId()).orElseThrow(() -> fail(404, "Team not found."));
    EventRoomRecord room = rooms.lockForUpdate(request.roomId()).orElseThrow(() -> fail(404, "Room not found."));
    List<String> warnings = exceptionWarnings(state, team, room);
    exceptions.upsert(round, team.id(), room.id(), reason, principal.id(), clock.now());
    changed(principal, "EVENT_ROOM_EXCEPTION_SET", "team:" + team.id() + " room:" + room.id() + " round:" + round, reason);
    return new OverrideOutcome("This round %s goes to the %s room.".formatted(team.name(), room.name()), warnings, null);
  }

  @Transactional
  public OverrideOutcome clearRoomException(Principal principal, long teamId, String reasonText) {
    access.requireAdmin(principal);
    String reason = requireReason(reasonText);
    EventStateRecord state = states.lockForUpdate();
    int round = requireRunningRound(state);
    EventTeamRecord team = teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    if (!exceptions.delete(round, teamId)) throw fail(404, "This team has no room exception in the current round.");
    changed(principal, "EVENT_ROOM_EXCEPTION_CLEARED", "team:" + teamId + " round:" + round, reason);
    return OverrideOutcome.done(team.name() + " follows the rotation again this round.");
  }

  // ---- O4 manual clue -----------------------------------------------------------------------------

  /**
   * GRANT keeps a clue the team already holds (any source) or adds a MANUAL one. REVOKE removes ROOM and MANUAL
   * clues; a RECOVERY clue only with {@code alsoRemoveRecovery}. Submissions and verdicts are not touched.
   */
  @Transactional
  public OverrideOutcome clue(Principal principal, ManualClueRequest request) {
    access.requireAdmin(principal);
    String reason = requireReason(request.reason());
    states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(request.teamId()).orElseThrow(() -> fail(404, "Team not found."));
    EventRoomRecord room = rooms.lockForUpdate(request.roomId()).orElseThrow(() -> fail(404, "Room not found."));
    String target = "team:" + team.id() + " room:" + room.id();
    if (request.action() == ManualClueRequest.Action.GRANT) {
      if (!clues.grantManual(team.id(), room.id(), clock.now())) {
        return OverrideOutcome.done("%s already holds the %s clue; nothing changed.".formatted(team.name(), room.name()));
      }
      changed(principal, "EVENT_CLUE_GRANTED", target, reason);
      return OverrideOutcome.done("%s now holds the %s clue.".formatted(team.name(), room.name()));
    }
    boolean includeRecovery = Boolean.TRUE.equals(request.alsoRemoveRecovery());
    if (clues.revoke(team.id(), room.id(), includeRecovery) == 0) {
      if (clues.source(team.id(), room.id()).filter("RECOVERY"::equals).isPresent()) {
        throw fail(409, "This clue was won in the recovery challenge. Tick \"also remove recovery clue\" to remove it.");
      }
      return OverrideOutcome.done("%s does not hold the %s clue; nothing changed.".formatted(team.name(), room.name()));
    }
    changed(principal, "EVENT_CLUE_REVOKED", target + (includeRecovery ? " recovery" : ""), reason);
    return OverrideOutcome.done("%s no longer holds the %s clue.".formatted(team.name(), room.name()));
  }

  // ---- O5 disqualify / reinstate ------------------------------------------------------------------

  @Transactional
  public OverrideOutcome setDisqualified(Principal principal, long teamId, boolean disqualified, String reasonText) {
    access.requireAdmin(principal);
    String reason = requireReason(reasonText);
    states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    if (team.disqualified() == disqualified) {
      return OverrideOutcome.done(team.name() + (disqualified ? " is already disqualified." : " is not disqualified."));
    }
    if (disqualified) teams.setDisqualified(teamId, true, reason, clock.now(), principal.id());
    else teams.setDisqualified(teamId, false, null, null, null);
    changed(principal, disqualified ? "EVENT_TEAM_DISQUALIFIED" : "EVENT_TEAM_REINSTATED", "team:" + teamId, reason);
    return OverrideOutcome.done(team.name() + (disqualified ? " is disqualified." : " is reinstated."));
  }

  // ---- shared -------------------------------------------------------------------------------------

  private RotationPreviewView preview(EventStateRecord state, EventTeamRecord team, GroupCode target, Map<RoomCode, String> names) {
    int first = RotationPreview.firstRemainingRound(
      state.currentRound(),
      state.timer().map(timer -> timer.effectivePhase(clock.now()))
    );
    var visited = RotationPreview.visitedRooms(
      team.groupCode(),
      first,
      exceptions.findForTeam(team.id()),
      submissions.submittedRooms(team.id())
    );
    RotationPreview preview = RotationPreview.of(visited, target, first);
    List<String> warnings = preview
      .warnings()
      .stream()
      .map(warning ->
        switch (warning.kind()) {
          case MISS -> "%s will miss the %s room.".formatted(team.name(), names.get(warning.room()));
          case REPEAT -> "%s will visit the %s room twice.".formatted(team.name(), names.get(warning.room()));
        }
      )
      .toList();
    return new RotationPreviewView(
      team.id(),
      team.name(),
      team.groupCode(),
      target,
      preview.visited(),
      preview.remaining().stream().map(visit -> new RotationPreviewView.Visit(visit.round(), visit.room())).toList(),
      warnings
    );
  }

  private List<String> exceptionWarnings(EventStateRecord state, EventTeamRecord team, EventRoomRecord room) {
    List<String> warnings = new ArrayList<>();
    if (!room.open()) warnings.add("The " + room.name() + " room is closed.");
    if (!ClueCheck.hasKey(room.clueKey())) warnings.add("The " + room.name() + " room has no clue key.");
    if (clues.source(team.id(), room.id()).isPresent()) warnings.add(team.name() + " already collected the " + room.name() + " clue.");
    Optional<RoomCode> rotation = RoomAssignment.roomFor(team.groupCode(), state.currentRound(), Optional.empty());
    if (rotation.filter(room.code()::equals).isPresent()) warnings.add("This is already the team's room in this round.");
    return warnings;
  }

  private static void requireSwappable(EventTeamRecord a, EventTeamRecord b) {
    if (a.id() == b.id()) throw fail(400, "Choose two different teams.");
    if (!a.active() || !b.active()) throw fail(409, "Only active teams can be swapped.");
    if (a.groupCode() == b.groupCode()) throw fail(409, "Both teams are already in group " + a.groupCode() + ".");
  }

  private static int requireRunningRound(EventStateRecord state) {
    if (!Rotation.isRunningRound(state.currentRound())) throw fail(409, "No round is running.");
    return state.currentRound();
  }

  private EventTeamRecord requireTeam(long teamId) {
    return teams.find(teamId).orElseThrow(() -> fail(404, "Team not found."));
  }

  private EventRoomRecord requireRoom(long roomId) {
    return rooms.find(roomId).orElseThrow(() -> fail(404, "Room not found."));
  }

  private Map<RoomCode, String> roomNames() {
    return rooms.findAll().stream().collect(Collectors.toMap(EventRoomRecord::code, EventRoomRecord::name));
  }

  private void changed(Principal principal, String action, String target, String reason) {
    states.bumpRevision();
    audit.record(principal.id(), action, target, reason);
  }
}
