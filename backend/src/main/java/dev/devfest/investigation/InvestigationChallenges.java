package dev.devfest.investigation;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.contest.ContestService;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InvestigationChallenges {

  public enum Kind {
    ANSWER,
    FINAL,
  }

  public enum State {
    DRAFT,
    OPEN,
    CLOSED,
  }

  public record Edit(
    @NotBlank @Size(max = 160) String title,
    @NotBlank @Size(max = 65536) String instructions,
    @NotNull
    @Size(max = 20)
    List<@NotBlank @Size(max = 2000) String> evidenceLinks,
    @NotBlank @Size(max = 1000) String answerFormat,
    @NotBlank @Size(max = 65536) String rubric,
    @Min(1) @Max(10000) int maxScore,
    @NotNull Kind kind,
    @NotNull State state,
    @NotNull Instant opensAt,
    @NotNull Instant closesAt,
    @Min(0) long version
  ) {}

  public record Challenge(
    long id,
    long roomId,
    String title,
    String instructions,
    List<String> evidenceLinks,
    String answerFormat,
    String rubric,
    int maxScore,
    Kind kind,
    State state,
    Instant opensAt,
    Instant closesAt,
    long version,
    String availability
  ) {}

  private final JdbcTemplate db;
  private final InvestigationAccess access;
  private final ContestService audit;
  private final ObjectMapper json;
  private final Clock clock;

  public InvestigationChallenges(
    JdbcTemplate db,
    InvestigationAccess access,
    ContestService audit,
    ObjectMapper json,
    Clock clock
  ) {
    this.db = db;
    this.access = access;
    this.audit = audit;
    this.json = json;
    this.clock = clock;
  }

  // A single short transaction lock serializes organizer changes with admissions and publication.
  // It avoids inconsistent authorization/scores during assignment changes; no external I/O occurs under it.
  public void lockSettings() {
    db.queryForObject(
      "SELECT id FROM investigation_settings WHERE id=1 FOR UPDATE",
      Integer.class
    );
  }

  public void unpublish() {
    db.update(
      "UPDATE investigation_settings SET results_published=false WHERE id=1"
    );
  }

  public List<Challenge> list(Principal p, long room) {
    access.room(p, room);
    boolean member = p.role().equals("MEMBER");
    if (member && access.member(p, false).archived()) return List.of();
    return db.query(
      "SELECT * FROM investigation_challenge WHERE room_id=?" +
        (member ? " AND state<>'DRAFT' AND opens_at<=?" : "") +
        " ORDER BY kind,id",
      this::map,
      member
        ? new Object[] { room, Timestamp.from(clock.instant()) }
        : new Object[] { room }
    );
  }

  public Challenge get(long id, boolean lock) {
    var rows = db.query(
      "SELECT * FROM investigation_challenge WHERE id=?" +
        (lock ? " FOR UPDATE" : ""),
      this::map,
      id
    );
    if (rows.isEmpty()) throw fail(404, "Challenge not found.");
    return rows.getFirst();
  }

  private Challenge map(ResultSet r, int n) throws SQLException {
    var state = State.valueOf(r.getString("state"));
    var start = r.getTimestamp("opens_at").toInstant();
    var end = r.getTimestamp("closes_at").toInstant();
    String availability =
      state == State.DRAFT
        ? "DRAFT"
        : state == State.CLOSED || !clock.instant().isBefore(end)
          ? "CLOSED"
          : clock.instant().isBefore(start)
            ? "SCHEDULED"
            : "OPEN";
    return new Challenge(
      r.getLong("id"),
      r.getLong("room_id"),
      r.getString("title"),
      r.getString("instructions"),
      List.of(json.readValue(r.getString("evidence_links"), String[].class)),
      r.getString("answer_format"),
      r.getString("rubric"),
      r.getInt("max_score"),
      Kind.valueOf(r.getString("kind")),
      state,
      start,
      end,
      r.getLong("version"),
      availability
    );
  }

  public static void requireOpen(Challenge c, Instant now) {
    if (
      c.state() != State.OPEN ||
      !ContestService.eligible(now, c.opensAt(), c.closesAt())
    ) throw fail(409, "This challenge is not accepting submissions now.");
  }

  @Transactional
  public Challenge save(Principal p, long room, Long id, Edit e) {
    lockSettings();
    access.reviewer(p, room);
    access.activeRoom(room);
    if (!e.opensAt().isBefore(e.closesAt())) throw fail(
      400,
      "Closing time must follow opening time."
    );
    ContestService.size(e.instructions());
    ContestService.size(e.rubric());
    for (String link : e.evidenceLinks()) {
      URI uri = URI.create(link);
      if (
        uri.getScheme() == null ||
        !Set.of("https", "http").contains(
          uri.getScheme().toLowerCase(Locale.ROOT)
        ) ||
        uri.getHost() == null ||
        uri.getUserInfo() != null
      ) throw fail(
        400,
        "Evidence links must be HTTP or HTTPS URLs without credentials."
      );
    }
    if (id != null) {
      var old = get(id, true);
      if (old.roomId() != room) throw fail(404, "Challenge not found.");
      if (old.version() != e.version()) throw fail(
        409,
        "This challenge changed. Reload before editing."
      );
      boolean hasWork = db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM investigation_submission WHERE challenge_id=?) OR EXISTS(SELECT 1 FROM investigation_draft WHERE challenge_id=?)",
        Boolean.class,
        id,
        id
      );
      if (
        hasWork &&
        (!old.title().equals(e.title()) ||
          !old.instructions().equals(e.instructions()) ||
          !old.evidenceLinks().equals(e.evidenceLinks()) ||
          !old.answerFormat().equals(e.answerFormat()) ||
          !old.rubric().equals(e.rubric()) ||
          old.maxScore() != e.maxScore() ||
          old.kind() != e.kind())
      ) throw fail(
        409,
        "Work already exists. Only availability and deadlines can now change."
      );
      db.update(
        "UPDATE investigation_challenge SET title=?,instructions=?,evidence_links=?,answer_format=?,rubric=?,max_score=?,kind=?,state=?,opens_at=?,closes_at=?,version=version+1 WHERE id=?",
        e.title(),
        e.instructions(),
        json.writeValueAsString(e.evidenceLinks()),
        e.answerFormat(),
        e.rubric(),
        e.maxScore(),
        e.kind().name(),
        e.state().name(),
        Timestamp.from(e.opensAt()),
        Timestamp.from(e.closesAt()),
        id
      );
    } else {
      id = db.queryForObject(
        "INSERT INTO investigation_challenge(room_id,title,instructions,evidence_links,answer_format,rubric,max_score,kind,state,opens_at,closes_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
        Long.class,
        room,
        e.title(),
        e.instructions(),
        json.writeValueAsString(e.evidenceLinks()),
        e.answerFormat(),
        e.rubric(),
        e.maxScore(),
        e.kind().name(),
        e.state().name(),
        Timestamp.from(e.opensAt()),
        Timestamp.from(e.closesAt())
      );
    }
    unpublish();
    audit.audit(
      p.id(),
      "INV_CHALLENGE_" + e.state().name(),
      id + ":" + e.opensAt() + ":" + e.closesAt()
    );
    return get(id, false);
  }
}
