package dev.devfest.event.service;

import dev.devfest.event.repository.RevisionBumped;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes every committed revision to the open streams. AFTER_COMMIT (the default phase) means a write that rolls
 * back is never announced; a bump outside a transaction (development seed) is already committed and is pushed too.
 */
@Component
public class RevisionBroadcaster {

  private final EventStreamRegistry streams;

  public RevisionBroadcaster(EventStreamRegistry streams) {
    this.streams = streams;
  }

  @TransactionalEventListener(fallbackExecution = true)
  void revisionBumped(RevisionBumped bumped) {
    streams.broadcastRevision(bumped.revision());
  }
}
