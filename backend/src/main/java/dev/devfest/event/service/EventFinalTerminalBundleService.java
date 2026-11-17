package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.FinalScreen;
import dev.devfest.event.domain.TerminalBundleEtag;
import dev.devfest.event.domain.TerminalBundles;
import dev.devfest.event.dto.FinalTerminalBundleView;
import dev.devfest.event.dto.TerminalBundleDelivery;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.FinalTerminalBundleRepository;
import dev.devfest.event.repository.TerminalBundleBytes;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The final's terminal package (rules section 10): the admin uploads one ZIP, validated and built exactly like a room's
 * server package ({@link TerminalBundles}), and a team downloads the built bundle only while its final shows the case.
 * It is stored apart from every room's package and has its own in-memory cache, so uploading, replacing or deleting it
 * never touches a room's package. Uploading, replacing and deleting follow the content edit lock (read-only once the
 * final opens).
 */
@Service
public class EventFinalTerminalBundleService {

  private static final String NOT_FOUND = "Terminal files not found.";
  /** The one cache key: this service's cache holds the final's package only. */
  private static final long FINAL_KEY = 0L;

  private final EventAccessPolicy access;
  private final EventFinalService finals;
  private final EventStateRepository states;
  private final FinalTerminalBundleRepository bundles;
  private final AuditLog audit;
  private final EventClock clock;
  private final TransactionTemplate transaction;
  private final TerminalBundleCache cache = new TerminalBundleCache();

  public EventFinalTerminalBundleService(
    EventAccessPolicy access,
    EventFinalService finals,
    EventStateRepository states,
    FinalTerminalBundleRepository bundles,
    AuditLog audit,
    EventClock clock,
    TransactionTemplate transaction
  ) {
    this.access = access;
    this.finals = finals;
    this.states = states;
    this.bundles = bundles;
    this.audit = audit;
    this.clock = clock;
    this.transaction = transaction;
  }

  /** The admin's summary (never the archive). Two SQL statements: event state, package. */
  public FinalTerminalBundleView view(Principal principal) {
    access.requireAdmin(principal);
    return view(states.state());
  }

  /**
   * Validates the ZIP and stores the bundle built from it (replacing an earlier one). Validation and building run
   * before the transaction starts, so a large upload holds no connection or lock while it is processed; every problem of
   * a rejected package is reported at once (400).
   */
  public FinalTerminalBundleView upload(Principal principal, byte[] zip) {
    access.requireAdmin(principal);
    TerminalBundles.Built built = TerminalBundles.build(zip);
    return transaction.execute(status -> {
      EventStateRecord state = states.lockForUpdate();
      EventContentService.requireContentEditable(state);
      Optional<FinalTerminalBundleRepository.Stored> previous = bundles.find();
      bundles.save(
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
      cache.forget(FINAL_KEY);
      states.bumpRevision();
      String target = "final sha256:" + built.sha256() + " files:" + built.fileCount() + " bytes:" + built.unpackedBytes();
      if (previous.isEmpty()) {
        audit.record(principal.id(), "EVENT_FINAL_TERMINAL_BUNDLE_UPLOADED", target);
      } else {
        audit.record(principal.id(), "EVENT_FINAL_TERMINAL_BUNDLE_REPLACED", target + " previous:" + previous.get().sha256().substring(0, 16));
      }
      return view(state);
    });
  }

  public FinalTerminalBundleView remove(Principal principal) {
    access.requireAdmin(principal);
    return transaction.execute(status -> {
      EventStateRecord state = states.lockForUpdate();
      EventContentService.requireContentEditable(state);
      FinalTerminalBundleRepository.Stored current = bundles.find().orElseThrow(() -> fail(404, "The final has no terminal package."));
      bundles.delete();
      cache.forget(FINAL_KEY);
      states.bumpRevision();
      audit.record(principal.id(), "EVENT_FINAL_TERMINAL_BUNDLE_DELETED", "final sha256:" + current.sha256() + " files:" + current.fileCount());
      return view(state);
    });
  }

  /** The admin may always download the final's package (to test it). */
  public TerminalBundleDelivery adminBundle(Principal principal, String ifNoneMatch) {
    access.requireAdmin(principal);
    return deliver(ifNoneMatch);
  }

  /** A team's download, only while its final shows the case; 404 for everyone else (like a room's package). */
  public TerminalBundleDelivery teamBundle(Principal principal, String ifNoneMatch) {
    EventTeamRecord team = access.requireTeamOf(principal);
    if (team.disqualified() || finals.screen(team) != FinalScreen.CASE) throw fail(404, NOT_FOUND);
    return deliver(ifNoneMatch);
  }

  /** One light query decides 404 or 304 without touching the archive; only a stale copy makes the server load it. */
  private TerminalBundleDelivery deliver(String ifNoneMatch) {
    FinalTerminalBundleRepository.Stored current = bundles.find().orElseThrow(() -> fail(404, NOT_FOUND));
    if (TerminalBundleEtag.matches(ifNoneMatch, current.sha256())) return new TerminalBundleDelivery(current.sha256(), null);
    TerminalBundleBytes loaded = cache.get(FINAL_KEY, current.sha256(), bundles::archive).orElseThrow(() -> fail(404, NOT_FOUND));
    return new TerminalBundleDelivery(loaded.sha256(), loaded.archive());
  }

  private FinalTerminalBundleView view(EventStateRecord state) {
    boolean readOnly = state.finalEverOpened();
    return bundles
      .find()
      .map(b ->
        new FinalTerminalBundleView(
          true,
          readOnly,
          b.sha256(),
          b.fileCount(),
          b.unpackedBytes(),
          b.archiveBytes(),
          owners(b.owners()),
          b.uploadedByName(),
          b.uploadedAt()
        )
      )
      .orElse(new FinalTerminalBundleView(false, readOnly, null, null, null, null, List.of(), null, null));
  }

  private static List<String> owners(String text) {
    return text == null || text.isBlank() ? List.of() : Arrays.stream(text.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }
}
