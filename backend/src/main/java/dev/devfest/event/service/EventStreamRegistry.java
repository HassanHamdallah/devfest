package dev.devfest.event.service;

import dev.devfest.auth.AuthService;
import dev.devfest.auth.SessionEnd;
import dev.devfest.auth.SessionEndedException;
import dev.devfest.auth.SessionsRevoked;
import dev.devfest.event.dto.RevisionView;
import dev.devfest.event.service.StreamTicketService.StreamOwner;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Every open live event stream, keyed by auth session. Streams carry only signals — "revision" with the new number
 * and "session-ended" with the reason — never board data, so pages still read through their authorized board
 * endpoints.
 *
 * <p>Streams are async (SseEmitter): no request thread, transaction or JDBC connection is held while one is open.
 * All writes go through one sender thread, so a broadcast never runs on the thread that committed the write. The
 * heartbeat thread runs the session check once per session and sends a ":ping" comment; a session that can no
 * longer be used gets "session-ended" and its streams close.
 */
@Service
public class EventStreamRegistry {

  public static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

  private static final Logger log = LoggerFactory.getLogger(EventStreamRegistry.class);

  private record LiveStream(StreamOwner owner, SseEmitter emitter) {}

  private final AuthService auth;
  private final Map<UUID, Set<LiveStream>> streams = new ConcurrentHashMap<>();
  private final ExecutorService sender = Executors.newSingleThreadExecutor(
    Thread.ofPlatform().name("event-stream-sender").daemon().factory()
  );
  private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
    Thread.ofPlatform().name("event-stream-heartbeat").daemon().factory()
  );
  /** Highest committed revision not sent yet (0 = none); a burst of commits collapses into one send. */
  private final AtomicLong pendingRevision = new AtomicLong();

  public EventStreamRegistry(AuthService auth, @Value("${app.event-stream.heartbeat:15s}") Duration heartbeat) {
    this.auth = auth;
    heartbeats.scheduleWithFixedDelay(this::heartbeat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Opens a stream for a consumed ticket after one session check (1 SQL statement, no transaction). The first
   * comment flushes the response so the browser reports the stream open right away.
   */
  public SseEmitter open(StreamOwner owner) {
    auth.checkSession(owner.userId(), owner.session());
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
    LiveStream stream = new LiveStream(owner, emitter);
    emitter.onCompletion(() -> remove(stream));
    emitter.onError(error -> remove(stream));
    emitter.onTimeout(() -> {
      remove(stream);
      emitter.complete();
    });
    streams.compute(owner.session(), (session, set) -> {
      Set<LiveStream> live = set == null ? ConcurrentHashMap.newKeySet() : set;
      live.add(stream);
      return live;
    });
    queue(() -> deliver(stream, () -> SseEmitter.event().comment("connected")));
    return emitter;
  }

  /** Number of open streams (all sessions). */
  public int openStreams() {
    return streams.values().stream().mapToInt(Set::size).sum();
  }

  /** Queues "revision" for every stream; called only after the bumping transaction committed. */
  public void broadcastRevision(long revision) {
    pendingRevision.accumulateAndGet(revision, Math::max);
    queue(this::sendLatestRevision);
  }

  /** Revoked sessions lose their streams as soon as the revocation commits (logout, replaced, organizer, …). */
  @TransactionalEventListener(fallbackExecution = true)
  void sessionsRevoked(SessionsRevoked revoked) {
    end(revoked.sessions(), revoked.reason());
  }

  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    heartbeats.shutdownNow();
    sender.shutdownNow();
    streams.values().forEach(set -> set.forEach(stream -> complete(stream.emitter())));
    streams.clear();
  }

  private void sendLatestRevision() {
    long revision = pendingRevision.getAndSet(0);
    if (revision == 0) return;
    forEachStream(stream ->
      deliver(stream, () -> SseEmitter.event().name("revision").data(new RevisionView(revision), MediaType.APPLICATION_JSON))
    );
  }

  private void end(Collection<UUID> sessions, SessionEnd reason) {
    queue(() -> {
      for (UUID session : sessions) {
        Set<LiveStream> ended = streams.remove(session);
        if (ended == null) continue;
        for (LiveStream stream : ended) {
          deliver(stream, () ->
            SseEmitter.event().name("session-ended").data(Map.of("reason", reason.name()), MediaType.APPLICATION_JSON)
          );
          complete(stream.emitter());
        }
      }
    });
  }

  /** One session check per session, not per stream; a database hiccup keeps the streams and skips nothing else. */
  private void heartbeat() {
    try {
      for (var entry : streams.entrySet()) {
        Set<LiveStream> set = entry.getValue();
        var any = set.stream().findFirst();
        if (any.isEmpty()) continue;
        try {
          auth.checkSession(any.get().owner().userId(), entry.getKey());
        } catch (SessionEndedException ended) {
          end(List.of(entry.getKey()), ended.reason);
          continue;
        } catch (RuntimeException unavailable) {
          log.debug("Stream session check failed; keeping the stream", unavailable);
        }
        queue(() -> set.forEach(stream -> deliver(stream, () -> SseEmitter.event().comment("ping"))));
      }
    } catch (RuntimeException unexpected) {
      log.warn("Event stream heartbeat failed", unexpected);
    }
  }

  /** After shutdown nothing is sent; closeAll already completed every stream. */
  private void queue(Runnable send) {
    try {
      sender.execute(send);
    } catch (RejectedExecutionException shuttingDown) {
      // The application is stopping.
    }
  }

  private void forEachStream(Consumer<LiveStream> action) {
    streams.values().forEach(set -> set.forEach(action));
  }

  /** A builder is single-use, hence the supplier. A closed connection just drops the stream. */
  private void deliver(LiveStream stream, Supplier<SseEventBuilder> event) {
    try {
      stream.emitter().send(event.get());
    } catch (IOException | IllegalStateException gone) {
      remove(stream);
    }
  }

  private void remove(LiveStream stream) {
    streams.computeIfPresent(stream.owner().session(), (session, set) -> {
      set.remove(stream);
      return set.isEmpty() ? null : set;
    });
  }

  private static void complete(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (IllegalStateException alreadyClosed) {
      // Completed by the container or a timeout first.
    }
  }
}
