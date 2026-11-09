package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.CryptoContent;
import dev.devfest.event.dto.FinalContentView;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.FinalContentRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's import of the crypto final's content file (docs/final/crypto-content.json). The whole file is validated
 * first (every problem is listed, nothing is saved when there is one); it can be replaced until the final opens and is
 * read-only after that, like all event content. The private values (p, q, d, S) live only here and in the stored file;
 * a member never receives them.
 */
@Service
public class EventFinalContentService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final FinalContentRepository repository;
  private final FinalContentReader reader;
  private final AuditLog audit;
  private final EventClock clock;

  public EventFinalContentService(
    EventAccessPolicy access,
    EventStateRepository states,
    FinalContentRepository repository,
    FinalContentReader reader,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.repository = repository;
    this.reader = reader;
    this.audit = audit;
    this.clock = clock;
  }

  /** 2 SQL statements: event state, content. */
  public FinalContentView view(Principal principal) {
    access.requireAdmin(principal);
    return view(states.state());
  }

  @Transactional
  public FinalContentView importContent(Principal principal, String body) {
    access.requireAdmin(principal);
    CryptoContent content = reader.parseAndValidate(body);
    EventStateRecord state = states.lockForUpdate();
    EventContentService.requireContentEditable(state);
    repository.save(body, content.fullUntilMinutes(), principal.id(), clock.now());
    states.bumpRevision();
    // The audit names the file's size only: it holds the private values.
    audit.record(principal.id(), "EVENT_FINAL_CONTENT_IMPORTED", "chars:" + body.length());
    return view(state);
  }

  private FinalContentView view(EventStateRecord state) {
    Optional<FinalContentRepository.Stored> stored = repository.find();
    Optional<CryptoContent> content = stored.map(s -> reader.parseAndValidate(s.json()));
    return new FinalContentView(
      stored.isPresent(),
      state.finalEverOpened(),
      stored.map(FinalContentRepository.Stored::importedAt).orElse(null),
      stored.map(FinalContentRepository.Stored::fullUntilMinutes).orElse(null),
      content.map(CryptoContent::recoveryIsPlaceholder).orElse(false),
      stored.map(FinalContentRepository.Stored::json).orElse(null)
    );
  }
}
