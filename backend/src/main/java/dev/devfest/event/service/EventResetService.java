package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireReason;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.auth.SessionEnd;
import dev.devfest.event.dto.OverrideOutcome;
import dev.devfest.event.dto.ResetEventRequest;
import dev.devfest.event.dto.SnapshotView;
import dev.devfest.event.repository.EventResetRepository;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventSnapshotRepository;
import dev.devfest.event.repository.EventSnapshotRepository.Scope;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Destructive resets and snapshots. Each reset first saves a snapshot of every row it will delete (same
 * transaction: if the snapshot fails, nothing changes), then enables deletes of append-only rows for this
 * transaction only and deletes in bulk. Setup data (accounts, teams, groups, rooms, content) is never touched.
 */
@Service
public class EventResetService {

  static final String RESET_CONFIRM_TEXT = "RESET";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final EventSnapshotRepository snapshots;
  private final EventResetRepository resets;
  private final AuthService auth;
  private final AuditLog audit;
  private final EventClock clock;

  public EventResetService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    EventSnapshotRepository snapshots,
    EventResetRepository resets,
    AuthService auth,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.snapshots = snapshots;
    this.resets = resets;
    this.auth = auth;
    this.audit = audit;
    this.clock = clock;
  }

  /** The final can be opened again and content becomes editable; room-phase data stays. */
  @Transactional
  public OverrideOutcome resetFinal(Principal principal, String reasonText) {
    access.requireAdmin(principal);
    String reason = requireReason(reasonText);
    states.lockForUpdate();
    long snapshot = snapshots.create(Scope.FINAL, "RESET_FINAL", reason, null, principal.id(), clock.now());
    resets.allowResetDeletes();
    resets.deleteFinalProgress();
    states.resetFinal();
    changed(principal, "EVENT_FINAL_RESET", "snapshot:" + snapshot, reason);
    return new OverrideOutcome("The final was reset and can be opened again.", List.of(), snapshot);
  }

  /** One team starts over; the team, its account and its group stay. Its device is signed out. */
  @Transactional
  public OverrideOutcome resetTeam(Principal principal, long teamId, String reasonText) {
    access.requireAdmin(principal);
    String reason = requireReason(reasonText);
    states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    long snapshot = snapshots.create(Scope.TEAM, "RESET_TEAM", reason, teamId, principal.id(), clock.now());
    resets.allowResetDeletes();
    resets.deleteTeamProgress(teamId);
    auth.revokeAll(team.accountId(), SessionEnd.ORGANIZER);
    changed(principal, "EVENT_TEAM_RESET", "team:" + teamId + " snapshot:" + snapshot, reason);
    return new OverrideOutcome(team.name() + " was reset; its device was signed out.", List.of(), snapshot);
  }

  /**
   * Everything back to "not started" for every team, keeping all setup. Requires the text RESET (400 otherwise)
   * and the admin's own password (403 otherwise; nothing changes).
   */
  @Transactional
  public OverrideOutcome resetEvent(Principal principal, ResetEventRequest request) {
    access.requireAdmin(principal);
    String reason = requireReason(request.reason());
    if (!RESET_CONFIRM_TEXT.equals(request.confirmText())) throw fail(400, "Type RESET to confirm.");
    if (!auth.passwordMatches(principal.id(), request.password())) throw fail(403, "The password is incorrect.");
    states.lockForUpdate();
    long snapshot = snapshots.create(Scope.EVENT, "RESET_EVENT", reason, null, principal.id(), clock.now());
    resets.allowResetDeletes();
    resets.deleteAllProgress();
    states.resetEvent();
    rooms.closeAll();
    changed(principal, "EVENT_RESET", "snapshot:" + snapshot, reason);
    return new OverrideOutcome("The event was reset to not started.", List.of(), snapshot);
  }

  /** A full progress snapshot on demand (kind MANUAL); changes nothing else. */
  @Transactional
  public OverrideOutcome snapshot(Principal principal, String reasonText) {
    access.requireAdmin(principal);
    String reason = requireReason(reasonText);
    states.lockForUpdate();
    long snapshot = snapshots.create(Scope.EVENT, "MANUAL", reason, null, principal.id(), clock.now());
    changed(principal, "EVENT_SNAPSHOT", "snapshot:" + snapshot, reason);
    return new OverrideOutcome("Snapshot " + snapshot + " saved.", List.of(), snapshot);
  }

  public List<SnapshotView> snapshots(Principal principal) {
    access.requireAdmin(principal);
    return snapshots
      .list()
      .stream()
      .map(s -> new SnapshotView(s.id(), s.createdAt(), s.createdByName(), s.kind(), s.reason(), s.teamId(), s.teamName()))
      .toList();
  }

  /** The snapshot document (metadata and data) as JSON text. */
  public String download(Principal principal, long snapshotId) {
    access.requireAdmin(principal);
    return snapshots.document(snapshotId).orElseThrow(() -> fail(404, "Snapshot not found."));
  }

  private void changed(Principal principal, String action, String target, String reason) {
    states.bumpRevision();
    audit.record(principal.id(), action, target, reason);
  }
}
