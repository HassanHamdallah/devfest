package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.TerminalFiles;
import dev.devfest.event.dto.TerminalFileContent;
import dev.devfest.event.dto.TerminalFileList;
import dev.devfest.event.dto.TerminalFileRequest;
import dev.devfest.event.dto.TerminalSettingView;
import dev.devfest.event.dto.TerminalStartFiles;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.RoomTerminalFileRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.repository.TerminalFileRecord;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-browser terminal's server side: the admin's per-room switch and the room's text files. The emulator
 * itself runs in the browser; the server only stores these files and decides who may receive them (D5).
 */
@Service
public class EventTerminalService {

  private static final String NOT_FOUND = "Terminal files not found.";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final RoomTerminalFileRepository files;
  private final TeamRoomExceptionRepository exceptions;
  private final AuditLog audit;
  private final EventClock clock;

  public EventTerminalService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    RoomTerminalFileRepository files,
    TeamRoomExceptionRepository exceptions,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.files = files;
    this.exceptions = exceptions;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * The files for the caller's team (5 SQL statements: team, state, room, the team's exception this round, files).
   * Only the team assigned to the room this round (rotation or room exception), while the round is ASSIGNED,
   * RUNNING or PAUSED and the room's terminal is enabled; every other case looks like an unknown room (404).
   */
  public TerminalStartFiles teamFiles(Principal principal, long roomId) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    EventRoomRecord room = rooms.find(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    int round = state.currentRound();
    RoundPhase phase = state.timer().map(timer -> timer.effectivePhase(clock.now())).orElse(null);
    Optional<RoomCode> exception = Rotation.isRunningRound(round) ? exceptions.find(round, team.id()) : Optional.empty();
    boolean deliverable = TerminalFiles.deliverableTo(
      room.code(),
      room.terminalEnabled(),
      team.groupCode(),
      team.disqualified(),
      round,
      phase,
      exception
    );
    if (!deliverable) throw fail(404, NOT_FOUND);
    return startFiles(roomId);
  }

  /** The room's manager (or ADMIN) may always load the room's files, e.g. for "Test terminal". */
  public TerminalStartFiles managerFiles(Principal principal, long roomId) {
    access.requireManagerOf(principal, roomId);
    return startFiles(roomId);
  }

  /** 3 SQL statements: room (access check), event state, file summaries (no content). */
  public TerminalFileList list(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    return fileList(room, states.state());
  }

  public TerminalFileContent file(Principal principal, long roomId, long fileId) {
    access.requireManagerOf(principal, roomId);
    TerminalFileRecord file = files.find(roomId, fileId).orElseThrow(() -> fail(404, "File not found."));
    return new TerminalFileContent(file.id(), file.path(), file.content(), file.sizeBytes());
  }

  @Transactional
  public TerminalFileList create(Principal principal, long roomId, TerminalFileRequest request) {
    access.requireManagerOf(principal, roomId);
    int size = requireValidFile(request);
    Locked locked = lockEditable(roomId);
    if (files.pathTaken(roomId, request.path(), null)) throw fail(409, "A file with this path already exists.");
    RoomTerminalFileRepository.Usage usage = files.usage(roomId);
    if (usage.files() >= TerminalFiles.MAX_FILES) {
      throw fail(409, "A room can have at most " + TerminalFiles.MAX_FILES + " terminal files.");
    }
    requireRoomTotal(usage.totalBytes() + size);
    files.insert(roomId, request.path(), request.content(), size, principal.id(), clock.now());
    changed(principal, "EVENT_TERMINAL_FILE_CREATED", roomId, request.path(), size);
    return fileList(locked.room(), locked.state());
  }

  @Transactional
  public TerminalFileList update(Principal principal, long roomId, long fileId, TerminalFileRequest request) {
    access.requireManagerOf(principal, roomId);
    int size = requireValidFile(request);
    Locked locked = lockEditable(roomId);
    TerminalFileRecord current = files.find(roomId, fileId).orElseThrow(() -> fail(404, "File not found."));
    if (files.pathTaken(roomId, request.path(), fileId)) throw fail(409, "A file with this path already exists.");
    requireRoomTotal(files.usage(roomId).totalBytes() - current.sizeBytes() + size);
    files.update(roomId, fileId, request.path(), request.content(), size, principal.id(), clock.now());
    changed(principal, "EVENT_TERMINAL_FILE_UPDATED", roomId, request.path(), size);
    return fileList(locked.room(), locked.state());
  }

  @Transactional
  public TerminalFileList delete(Principal principal, long roomId, long fileId) {
    access.requireManagerOf(principal, roomId);
    Locked locked = lockEditable(roomId);
    TerminalFileRecord current = files.find(roomId, fileId).orElseThrow(() -> fail(404, "File not found."));
    files.delete(roomId, fileId);
    changed(principal, "EVENT_TERMINAL_FILE_DELETED", roomId, current.path(), current.sizeBytes());
    return fileList(locked.room(), locked.state());
  }

  /** ADMIN only. Allowed at any time (it is a delivery setting, not judged content); a repeat is a no-op. */
  @Transactional
  public TerminalSettingView setEnabled(Principal principal, long roomId, boolean enabled) {
    access.requireAdmin(principal);
    states.lockForUpdate();
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (room.terminalEnabled() != enabled) {
      rooms.setTerminalEnabled(roomId, enabled);
      states.bumpRevision();
      audit.record(principal.id(), enabled ? "EVENT_TERMINAL_ENABLED" : "EVENT_TERMINAL_DISABLED", "room:" + roomId);
    }
    return new TerminalSettingView(roomId, enabled);
  }

  private record Locked(EventStateRecord state, EventRoomRecord room) {}

  /** Lock order event_state → room; content is read-only after the final opens, files need the terminal on. */
  private Locked lockEditable(long roomId) {
    EventStateRecord state = states.lockForUpdate();
    EventContentService.requireContentEditable(state);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (!room.terminalEnabled()) throw fail(409, "The terminal is not enabled for this room.");
    return new Locked(state, room);
  }

  /** Path rules and the per-file limit; returns the content size in UTF-8 bytes. */
  private static int requireValidFile(TerminalFileRequest request) {
    TerminalFiles.pathProblem(request.path()).ifPresent(problem -> {
      throw fail(400, problem);
    });
    if (request.content().indexOf('\0') >= 0) throw fail(400, "Terminal files must be text.");
    long size = request.content().getBytes(StandardCharsets.UTF_8).length;
    if (size > TerminalFiles.MAX_FILE_BYTES) throw fail(400, "The file is too large (max 2 MB).");
    return (int) size;
  }

  private static void requireRoomTotal(long totalBytes) {
    if (totalBytes > TerminalFiles.MAX_ROOM_BYTES) {
      throw fail(409, "The room's terminal files would exceed 5 MB in total.");
    }
  }

  private TerminalStartFiles startFiles(long roomId) {
    List<TerminalStartFiles.File> start = files
      .withContent(roomId)
      .stream()
      .map(file -> new TerminalStartFiles.File(file.path(), file.content()))
      .toList();
    return new TerminalStartFiles(start);
  }

  private TerminalFileList fileList(EventRoomRecord room, EventStateRecord state) {
    List<TerminalFileList.Entry> entries = files
      .summaries(room.id())
      .stream()
      .map(file -> new TerminalFileList.Entry(file.id(), file.path(), file.sizeBytes(), file.updatedByName(), file.updatedAt()))
      .toList();
    long total = entries.stream().mapToLong(TerminalFileList.Entry::sizeBytes).sum();
    return new TerminalFileList(
      room.id(),
      room.terminalEnabled(),
      !state.finalEverOpened(),
      entries,
      total,
      TerminalFiles.MAX_FILES,
      TerminalFiles.MAX_FILE_BYTES,
      TerminalFiles.MAX_ROOM_BYTES
    );
  }

  /** The audit names the room, path and size; never the content. */
  private void changed(Principal principal, String action, long roomId, String path, int size) {
    states.bumpRevision();
    audit.record(principal.id(), action, "room:" + roomId + " path:" + path + " bytes:" + size);
  }
}
