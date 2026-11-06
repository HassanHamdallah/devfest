package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.TerminalBundleEtag;
import dev.devfest.event.domain.TerminalBundles;
import dev.devfest.event.dto.TerminalBundleDelivery;
import dev.devfest.event.dto.TerminalFileList;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.RoomTerminalBundleRepository;
import dev.devfest.event.repository.TerminalBundleBytes;
import dev.devfest.event.repository.TerminalBundleRecord;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A room's terminal server package (rules §18): the manager uploads one ZIP, the server validates it and keeps
 * the bundle built from it, and the assigned team downloads that bundle under exactly the gates of the room's
 * text files. Uploading, replacing and removing follow the content edit lock.
 */
@Service
public class EventTerminalBundleService {

  private static final String NOT_FOUND = "Terminal files not found.";
  private static final Instant MIN_CLOCK = Instant.parse("1970-01-01T00:00:00Z");
  private static final Instant MAX_CLOCK = Instant.parse("2100-01-01T00:00:00Z");

  private final EventAccessPolicy access;
  private final EventTerminalService terminal;
  private final EventStateRepository states;
  private final RoomTerminalBundleRepository bundles;
  private final TerminalBundleCache cache;
  private final AuditLog audit;
  private final EventClock clock;
  private final TransactionTemplate transaction;

  public EventTerminalBundleService(
    EventAccessPolicy access,
    EventTerminalService terminal,
    EventStateRepository states,
    RoomTerminalBundleRepository bundles,
    TerminalBundleCache cache,
    AuditLog audit,
    EventClock clock,
    TransactionTemplate transaction
  ) {
    this.access = access;
    this.terminal = terminal;
    this.states = states;
    this.bundles = bundles;
    this.cache = cache;
    this.audit = audit;
    this.clock = clock;
    this.transaction = transaction;
  }

  /**
   * Validates the ZIP and stores the bundle built from it (replacing an earlier one). The validation and the
   * building run before the transaction starts, so a large upload holds no database connection and no lock while
   * it is processed. Every problem of a rejected package is reported at once (400).
   */
  public TerminalFileList upload(Principal principal, long roomId, byte[] zip) {
    access.requireManagerOf(principal, roomId);
    TerminalBundles.Built built = TerminalBundles.build(zip);
    return transaction.execute(status -> store(principal, roomId, built));
  }

  public TerminalFileList remove(Principal principal, long roomId) {
    access.requireManagerOf(principal, roomId);
    return transaction.execute(status -> {
      EventTerminalService.Locked locked = terminal.lockEditable(roomId);
      TerminalBundleRecord current = bundles.find(roomId).orElseThrow(() -> fail(404, "This room has no server package."));
      bundles.delete(roomId);
      cache.forget(roomId);
      states.bumpRevision();
      audit.record(principal.id(), "EVENT_TERMINAL_BUNDLE_DELETED", describe(roomId, current));
      return terminal.fileList(locked.room(), locked.state());
    });
  }

  /**
   * Sets (or, with null, clears) the terminal clock of the room's package without uploading it again. Same edit lock
   * as the package, audited. A page for the room's organizers only: a member or another room's manager gets a 404.
   */
  public TerminalFileList setClock(Principal principal, long roomId, Instant clock) {
    access.requireOrganizerOf(principal, roomId, "Room not found.");
    if (clock != null && (clock.isBefore(MIN_CLOCK) || !clock.isBefore(MAX_CLOCK))) {
      throw fail(400, "The terminal clock must be between 1970 and 2099.");
    }
    return transaction.execute(status -> {
      EventTerminalService.Locked locked = terminal.lockEditable(roomId);
      TerminalBundleRecord current = bundles.find(roomId).orElseThrow(() -> fail(409, "Upload a server package first."));
      if (!java.util.Objects.equals(current.terminalClock(), clock)) {
        bundles.setClock(roomId, clock);
        cache.forget(roomId);
        states.bumpRevision();
        audit.record(principal.id(), "EVENT_TERMINAL_CLOCK_SET", "room:" + roomId + " clock:" + (clock == null ? "none" : clock));
      }
      return terminal.fileList(locked.room(), locked.state());
    });
  }

  /** The assigned team's download: the same gates as the text files (404 for everyone else). */
  public TerminalBundleDelivery teamBundle(Principal principal, long roomId, String ifNoneMatch) {
    terminal.requireDeliverable(principal, roomId);
    return deliver(roomId, ifNoneMatch);
  }

  /** The room's manager (or ADMIN) may always download their room's package ("Test terminal"). */
  public TerminalBundleDelivery managerBundle(Principal principal, long roomId, String ifNoneMatch) {
    access.requireManagerOf(principal, roomId);
    return deliver(roomId, ifNoneMatch);
  }

  private TerminalFileList store(Principal principal, long roomId, TerminalBundles.Built built) {
    EventTerminalService.Locked locked = terminal.lockEditable(roomId);
    TerminalBundleRecord previous = bundles.find(roomId).orElse(null);
    bundles.save(
      roomId,
      built.archive(),
      built.sha256(),
      built.fileCount(),
      built.unpackedBytes(),
      built.accounts().ownersText(),
      built.accounts().passwdText(),
      built.accounts().groupText(),
      principal.id(),
      clock.now()
    );
    cache.forget(roomId);
    states.bumpRevision();
    // Hash and counts only; the previous digest is shortened so the target stays within its column.
    String target = "room:" + roomId + " sha256:" + built.sha256() + " files:" + built.fileCount() + " bytes:" + built.unpackedBytes();
    if (previous == null) {
      audit.record(principal.id(), "EVENT_TERMINAL_BUNDLE_UPLOADED", target);
    } else {
      audit.record(principal.id(), "EVENT_TERMINAL_BUNDLE_REPLACED", target + " previous:" + previous.sha256().substring(0, 16));
    }
    return terminal.fileList(locked.room(), locked.state());
  }

  /**
   * One light query decides 404 or 304 without touching the archive; only a caller whose copy is stale makes the
   * server load the bytes (shared between simultaneous callers by the cache).
   */
  private TerminalBundleDelivery deliver(long roomId, String ifNoneMatch) {
    TerminalBundleRecord current = bundles.find(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    if (TerminalBundleEtag.matches(ifNoneMatch, current.sha256())) return new TerminalBundleDelivery(current.sha256(), null);
    TerminalBundleBytes loaded = cache.get(roomId, current.sha256(), () -> bundles.archive(roomId)).orElseThrow(() -> fail(404, NOT_FOUND));
    return new TerminalBundleDelivery(loaded.sha256(), loaded.archive());
  }

  private static String describe(long roomId, TerminalBundleRecord bundle) {
    return "room:" + roomId + " sha256:" + bundle.sha256() + " files:" + bundle.fileCount() + " bytes:" + bundle.unpackedBytes();
  }
}
