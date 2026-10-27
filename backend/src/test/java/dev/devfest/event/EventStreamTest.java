package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.service.EventStreamRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The live revision push over a real HTTP port: single-use tickets, after-commit broadcasts, session closing,
 * heartbeats and many concurrent streams. The heartbeat runs every second here (15 s in production).
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "app.worker-enabled=false",
    "app.seed=false",
    "app.bootstrap-email=",
    "app.event-seed-password=",
    "app.jwt-secret=devfest-test-only-secret-with-48-characters-123456",
    "spring.config.import=",
    "app.event-stream.heartbeat=1s",
  }
)
@Import(ManualClock.Config.class)
class EventStreamTest extends EventIntegrationTest {

  private static final Duration PUSH_WITHIN = Duration.ofSeconds(1);

  @LocalServerPort
  private int port;

  @Autowired
  private ManualClock clock;

  @Autowired
  private EventStreamRegistry registry;

  @Autowired
  private EventStateRepository states;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private DataSource dataSource;

  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private final List<Stream> opened = new ArrayList<>();

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  /** The server notices a closed connection at its next write (heartbeat), so wait for the registry to empty. */
  @AfterEach
  void closeStreams() throws InterruptedException {
    opened.forEach(Stream::close);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (registry.openStreams() > 0 && System.nanoTime() < deadline) Thread.sleep(50);
    assertEquals(0, registry.openStreams(), "closed streams leave the registry");
  }

  @Test
  void streamNeedsAFreshSingleUseTicket() throws Exception {
    TestTeam team = team("Alpha", GroupCode.A);
    assertEquals(401, post("/stream-ticket", null, null).status());
    assertEquals(401, streamStatus(null));
    assertEquals(401, streamStatus("not-a-ticket"));

    String ticket = ticket(team.token());
    assertTrue(ticket.length() >= 43, "256-bit ticket");
    Stream stream = open(ticket);
    assertEquals("text/event-stream", stream.header("content-type").split(";")[0]);
    assertEquals("no-cache", stream.header("cache-control"));
    assertEquals("no", stream.header("x-accel-buffering"));
    assertEquals(401, streamStatus(ticket), "a ticket opens one stream only");

    String expiring = ticket(team.token());
    clock.advance(Duration.ofSeconds(31));
    assertEquals(401, streamStatus(expiring), "tickets live 30 seconds");
  }

  @Test
  void memberStreamReceivesTheNewRevisionWithinOneSecond() {
    TestTeam team = team("Alpha", GroupCode.A);
    Stream stream = open(ticket(team.token()));
    assignRound(1);
    stream.awaitRevision(revision(), PUSH_WITHIN);

    long started = System.nanoTime();
    startTimer();
    long expected = revision();
    assertEquals(expected, stream.awaitRevision(expected, PUSH_WITHIN));
    assertTrue(System.nanoTime() - started < PUSH_WITHIN.toNanos());
  }

  @Test
  void rolledBackAndFailedWritesSendNothing() {
    TestTeam team = team("Alpha", GroupCode.A);
    Stream stream = open(ticket(team.token()));
    long before = revision();

    transactions.executeWithoutResult(status -> {
      states.bumpRevision();
      status.setRollbackOnly();
    });
    assertThrows(
      IllegalStateException.class,
      () ->
        transactions.executeWithoutResult(status -> {
          states.bumpRevision();
          throw new IllegalStateException("write failed after the bump");
        })
    );
    assertEquals(409, post("/admin/round/timer/start", admin, null).status(), "round 0 has no timer");
    assertEquals(before, revision());
    assertNull(stream.nextNamed("revision", Duration.ofMillis(1500)), "nothing was committed");

    assignRound(1);
    assertEquals(before + 1, stream.awaitRevision(before + 1, PUSH_WITHIN));
  }

  @Test
  void revokedSessionsLoseTheirStreamsAndOthersStayOpen() {
    TestTeam team = team("Alpha", GroupCode.A);
    Stream first = open(ticket(team.token()));
    Stream manager = open(ticket(managers.get(RoomCode.AI)));
    Stream organizer = open(ticket(admin));

    String secondDevice = login(email(team));
    assertEquals("REPLACED", first.awaitSessionEnded(PUSH_WITHIN));
    assertTrue(first.awaitEnd(PUSH_WITHIN), "the replaced device's stream closes");

    Stream second = open(ticket(secondDevice));
    expectOk(post("/admin/teams/" + team.id() + "/sign-out", admin, null));
    assertEquals("ORGANIZER", second.awaitSessionEnded(PUSH_WITHIN));
    assertTrue(second.awaitEnd(PUSH_WITHIN));

    assignRound(1);
    long expected = revision();
    assertEquals(expected, manager.awaitRevision(expected, PUSH_WITHIN));
    assertEquals(expected, organizer.awaitRevision(expected, PUSH_WITHIN));
    assertEquals(2, registry.openStreams());
  }

  @Test
  void heartbeatsPingAndRecheckTheSession() {
    TestTeam team = team("Alpha", GroupCode.A);
    Stream stream = open(ticket(team.token()));
    assertNotNull(stream.nextNamed(Stream.COMMENT, Duration.ofSeconds(3)));
    long first = System.nanoTime();
    assertNotNull(stream.nextNamed(Stream.COMMENT, Duration.ofSeconds(3)));
    long gap = Duration.ofNanos(System.nanoTime() - first).toMillis();
    assertTrue(gap > 500 && gap < 2500, "about one heartbeat interval apart: " + gap + " ms");

    // Revoked outside AuthService (nothing announces it): the next heartbeat's session check notices.
    db.update(
      "UPDATE auth_session SET revoked = TRUE, revoked_reason = 'DEACTIVATED' WHERE user_id = ?",
      team.accountId()
    );
    assertEquals("DEACTIVATED", stream.awaitSessionEnded(Duration.ofSeconds(3)));
    assertTrue(stream.awaitEnd(PUSH_WITHIN));
  }

  @Test
  void hundredStreamsGetTheFinalRevisionWithoutExhaustingThreadsOrConnections() throws Exception {
    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
    List<Stream> streams = new ArrayList<>();
    for (int account = 0; account < 25; account++) {
      String token = login(account("MANAGER"));
      for (int tab = 0; tab < 4; tab++) streams.add(open(ticket(token)));
    }
    assertEquals(100, registry.openStreams());

    long started = System.nanoTime();
    assertEquals(200, revisionStatus(admin), "requests still get a thread with 100 streams open");
    assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());

    assignRound(1);
    startTimer();
    for (int i = 0; i < 9; i++) {
      expectOk(post("/admin/round/timer/pause", admin, null));
      expectOk(post("/admin/round/timer/resume", admin, null));
    }
    long last = revision();
    for (Stream stream : streams) assertEquals(last, stream.awaitRevision(last, Duration.ofSeconds(3)));

    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (pool.getHikariPoolMXBean().getActiveConnections() > 0 && System.nanoTime() < deadline) Thread.sleep(50);
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(), "streams hold no JDBC connection");
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private String ticket(String token) {
    return expectOk(post("/stream-ticket", token, null)).json("$.ticket");
  }

  private Stream open(String ticket) {
    Stream stream = Stream.open(http, streamUri(ticket));
    assertEquals(200, stream.status(), "stream with a fresh ticket");
    opened.add(stream);
    return stream;
  }

  private int streamStatus(String ticket) throws IOException, InterruptedException {
    HttpResponse<String> response = http.send(
      HttpRequest.newBuilder(streamUri(ticket)).header("Accept", "text/event-stream").timeout(Duration.ofSeconds(5)).build(),
      HttpResponse.BodyHandlers.ofString()
    );
    return response.statusCode();
  }

  private int revisionStatus(String token) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/event/revision"))
      .header("Authorization", "Bearer " + token)
      .timeout(Duration.ofSeconds(5))
      .build();
    return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private URI streamUri(String ticket) {
    return URI.create("http://127.0.0.1:" + port + "/api/v1/event/stream" + (ticket == null ? "" : "?ticket=" + ticket));
  }

  private String email(TestTeam team) {
    return db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, team.accountId());
  }

  /** One SSE connection read on a virtual thread; comments arrive as events named {@link #COMMENT}. */
  private static final class Stream {

    static final String COMMENT = ":comment";
    private static final Event END = new Event("", "");

    record Event(String name, String data) {}

    private final HttpResponse<InputStream> response;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();

    private Stream(HttpResponse<InputStream> response) {
      this.response = response;
      if (response.statusCode() == 200) Thread.ofVirtual().start(this::read);
    }

    static Stream open(HttpClient http, URI uri) {
      try {
        return new Stream(
          http.send(HttpRequest.newBuilder(uri).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream())
        );
      } catch (IOException | InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }

    int status() {
      return response.statusCode();
    }

    String header(String name) {
      return response.headers().firstValue(name).orElse(null);
    }

    /** The first "revision" event carrying at least {@code expected}; fails after {@code within}. */
    long awaitRevision(long expected, Duration within) {
      long deadline = System.nanoTime() + within.toNanos();
      while (true) {
        Event event = nextNamed("revision", Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        assertNotNull(event, "no revision " + expected + " within " + within);
        long revision = ((Number) JsonPath.read(event.data(), "$.revision")).longValue();
        if (revision >= expected) return revision;
      }
    }

    String awaitSessionEnded(Duration within) {
      Event event = nextNamed("session-ended", within);
      assertNotNull(event, "no session-ended within " + within);
      return JsonPath.read(event.data(), "$.reason");
    }

    /** True once the server closed the stream; events before the end are skipped. */
    boolean awaitEnd(Duration within) {
      return nextNamed("", within) == END;
    }

    /** The next event with this name (others are skipped), or null on timeout or end of stream. */
    Event nextNamed(String name, Duration within) {
      long deadline = System.nanoTime() + within.toNanos();
      while (true) {
        Event event = next(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        if (event == null) return null;
        if (event == END) {
          events.add(END);
          return name.isEmpty() ? END : null;
        }
        if (event.name().equals(name)) return event;
      }
    }

    private Event next(Duration within) {
      try {
        return events.poll(within.toNanos(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }

    private void read() {
      try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String name = "message";
        StringBuilder data = new StringBuilder();
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          if (line.startsWith(":")) events.add(new Event(COMMENT, line.substring(1)));
          else if (line.startsWith("event:")) name = line.substring(6).trim();
          else if (line.startsWith("data:")) data.append(line.substring(5).trim());
          else if (line.isEmpty() && !data.isEmpty()) {
            events.add(new Event(name, data.toString()));
            name = "message";
            data.setLength(0);
          }
        }
      } catch (IOException closed) {
        // Closed by the test or the server.
      }
      events.add(END);
    }

    void close() {
      try {
        response.body().close();
      } catch (IOException ignored) {
        // Already closed.
      }
    }
  }
}
