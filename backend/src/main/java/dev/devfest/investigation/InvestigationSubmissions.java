package dev.devfest.investigation;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.contest.ContestService;
import jakarta.validation.constraints.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InvestigationSubmissions {

  public record Report(
    @NotNull @Size(max = 65536) String conclusion,
    @NotNull @Size(max = 65536) String reasoning,
    @NotNull @Size(max = 65536) String evidence
  ) {}

  public record DraftEdit(
    @NotNull @jakarta.validation.Valid Report report,
    @Min(0) long version
  ) {}

  public record Draft(
    Report report,
    long version,
    Instant updatedAt,
    boolean locked
  ) {}

  public record Receipt(
    UUID id,
    long teamId,
    long challengeId,
    Instant receivedAt
  ) {}

  public record Submission(
    UUID id,
    long teamId,
    long challengeId,
    long roomId,
    long authorId,
    String kind,
    Report report,
    Instant receivedAt,
    int maxScore,
    String challengeSnapshot,
    Integer points,
    String feedback,
    long scoreVersion,
    Long reviewerId,
    Instant scoredAt,
    boolean latest
  ) {}

  private final JdbcTemplate db;
  private final InvestigationAccess access;
  private final InvestigationChallenges challenges;
  private final ContestService audit;
  private final Clock clock;
  private final ObjectMapper json;

  public InvestigationSubmissions(
    JdbcTemplate db,
    InvestigationAccess access,
    InvestigationChallenges challenges,
    ContestService audit,
    Clock clock,
    ObjectMapper json
  ) {
    this.db = db;
    this.access = access;
    this.challenges = challenges;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  private void size(Report r) {
    ContestService.size(r.conclusion());
    ContestService.size(r.reasoning());
    ContestService.size(r.evidence());
  }

  private boolean locked(long team, long challenge) {
    return db.queryForObject(
      "SELECT EXISTS(SELECT 1 FROM investigation_submission WHERE team_id=? AND challenge_id=? AND kind='FINAL')",
      Boolean.class,
      team,
      challenge
    );
  }

  private InvestigationChallenges.Challenge visible(Principal p, long id) {
    var team = access.member(p, false);
    var c = challenges.get(id, false);
    if (
      c.roomId() != team.roomId() ||
      c.state() == InvestigationChallenges.State.DRAFT ||
      clock.instant().isBefore(c.opensAt()) ||
      team.archived()
    ) throw fail(404, "Challenge not found.");
    return c;
  }

  public Draft draft(Principal p, long id) {
    visible(p, id);
    long team = access.member(p, false).id();
    var accepted = db.query(
      "SELECT conclusion,reasoning,evidence,received_at FROM investigation_submission WHERE team_id=? AND challenge_id=? AND kind='FINAL'",
      (r, n) ->
        new Draft(
          new Report(r.getString(1), r.getString(2), r.getString(3)),
          0,
          r.getTimestamp(4).toInstant(),
          true
        ),
      team,
      id
    );
    if (!accepted.isEmpty()) return accepted.getFirst();
    var rows = db.query(
      "SELECT * FROM investigation_draft WHERE team_id=? AND challenge_id=?",
      (r, n) ->
        new Draft(
          new Report(
            r.getString("conclusion"),
            r.getString("reasoning"),
            r.getString("evidence")
          ),
          r.getLong("version"),
          r.getTimestamp("updated_at").toInstant(),
          locked(team, id)
        ),
      team,
      id
    );
    return rows.isEmpty()
      ? new Draft(new Report("", "", ""), 0, null, locked(team, id))
      : rows.getFirst();
  }

  @Transactional
  public Draft saveDraft(Principal p, long id, DraftEdit edit) {
    challenges.lockSettings();
    var team = access.member(p, true);
    var c = visible(p, id);
    InvestigationChallenges.requireOpen(c, clock.instant());
    size(edit.report());
    if (locked(team.id(), id)) throw fail(
      409,
      "The final report is submitted and locked."
    );
    int changed;
    var r = edit.report();
    var now = Timestamp.from(clock.instant());
    if (edit.version() == 0) changed = db.update(
      "INSERT INTO investigation_draft(team_id,challenge_id,room_id,conclusion,reasoning,evidence,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
      team.id(),
      id,
      team.roomId(),
      r.conclusion(),
      r.reasoning(),
      r.evidence(),
      now
    );
    else changed = db.update(
      "UPDATE investigation_draft SET conclusion=?,reasoning=?,evidence=?,version=version+1,updated_at=? WHERE team_id=? AND challenge_id=? AND version=?",
      r.conclusion(),
      r.reasoning(),
      r.evidence(),
      now,
      team.id(),
      id,
      edit.version()
    );
    if (changed != 1) throw fail(
      409,
      "A newer draft exists. Copy your edits, then reload the server draft."
    );
    return draft(p, id);
  }

  @Transactional
  public Receipt submit(Principal p, long id, UUID key, Report report) {
    challenges.lockSettings();
    var team = access.member(p, true);
    size(report);
    String hash = AuthService.hash(id + ":" + json.writeValueAsString(report));
    var previous = db.queryForList(
      "SELECT id,challenge_id,received_at,request_hash FROM investigation_submission WHERE team_id=? AND idempotency_key=?",
      team.id(),
      key
    );
    if (!previous.isEmpty()) {
      var row = previous.getFirst();
      if (!hash.equals(row.get("request_hash"))) throw fail(
        409,
        "This retry key already belongs to a different answer."
      );
      return new Receipt(
        (UUID) row.get("id"),
        team.id(),
        ((Number) row.get("challenge_id")).longValue(),
        ((Timestamp) row.get("received_at")).toInstant()
      );
    }
    if (
      !db.queryForObject(
        "SELECT rules_confirmed FROM investigation_settings WHERE id=1",
        Boolean.class
      )
    ) throw fail(
      409,
      "The organizer must confirm the investigation rules first."
    );
    var c = visible(p, id);
    Instant now = clock.instant();
    InvestigationChallenges.requireOpen(c, now);
    // PostgreSQL timestamps have microsecond precision. The first receipt must match every retry.
    now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (locked(team.id(), id)) throw fail(
      409,
      "The final report is submitted and locked."
    );
    if (
      report.conclusion().isBlank() ||
      (c.kind() == InvestigationChallenges.Kind.FINAL &&
        (report.reasoning().isBlank() || report.evidence().isBlank()))
    ) throw fail(400, "Complete the answer, or all three final-report fields.");
    UUID submission = UUID.randomUUID();
    db.update(
      "INSERT INTO investigation_submission(id,team_id,challenge_id,room_id,author_id,kind,conclusion,reasoning,evidence,challenge_snapshot,max_score,received_at,idempotency_key,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      submission,
      team.id(),
      id,
      team.roomId(),
      p.id(),
      c.kind().name(),
      report.conclusion(),
      report.reasoning(),
      report.evidence(),
      json.writeValueAsString(c),
      c.maxScore(),
      Timestamp.from(now),
      key,
      hash
    );
    challenges.unpublish();
    audit.audit(p.id(), "INV_ACCEPT_SUBMISSION", submission.toString());
    return new Receipt(submission, team.id(), id, now);
  }

  public List<Submission> list(Principal p, long room, int page) {
    access.room(p, room);
    String own = p.role().equals("MEMBER") ? " AND s.team_id=?" : "";
    Object[] args = p.role().equals("MEMBER")
      ? new Object[] {
          room,
          access.member(p, false).id(),
          Math.max(0, page) * 50,
        }
      : new Object[] { room, Math.max(0, page) * 50 };
    boolean scores =
      !p.role().equals("MEMBER") ||
      db.queryForObject(
        "SELECT results_published FROM investigation_settings WHERE id=1",
        Boolean.class
      );
    return db.query(
      select() +
        " WHERE s.room_id=?" +
        own +
        " ORDER BY s.sequence DESC LIMIT 50 OFFSET ?",
      (r, n) -> map(r, scores),
      args
    );
  }

  public Submission get(Principal p, UUID id) {
    var rows = db.queryForList(
      "SELECT team_id,room_id FROM investigation_submission WHERE id=?",
      id
    );
    if (rows.isEmpty()) throw fail(404, "Submission not found.");
    var row = rows.getFirst();
    if (p.role().equals("MEMBER")) {
      if (
        access.member(p, false).id() !=
        ((Number) row.get("team_id")).longValue()
      ) throw fail(404, "Submission not found.");
    } else access.reviewer(p, ((Number) row.get("room_id")).longValue());
    boolean scores =
      !p.role().equals("MEMBER") ||
      db.queryForObject(
        "SELECT results_published FROM investigation_settings WHERE id=1",
        Boolean.class
      );
    return db.queryForObject(
      select() + " WHERE s.id=?",
      (r, n) -> map(r, scores),
      id
    );
  }

  private String select() {
    return "SELECT s.*,v.points,v.feedback,v.version score_version,v.reviewer_id,v.updated_at scored_at,NOT EXISTS(SELECT 1 FROM investigation_submission newer WHERE newer.team_id=s.team_id AND newer.challenge_id=s.challenge_id AND newer.sequence>s.sequence) latest FROM investigation_submission s LEFT JOIN investigation_score v ON v.submission_id=s.id";
  }

  private Submission map(ResultSet r, boolean scores) throws SQLException {
    var scored = r.getTimestamp("scored_at");
    return new Submission(
      r.getObject("id", UUID.class),
      r.getLong("team_id"),
      r.getLong("challenge_id"),
      r.getLong("room_id"),
      r.getLong("author_id"),
      r.getString("kind"),
      new Report(
        r.getString("conclusion"),
        r.getString("reasoning"),
        r.getString("evidence")
      ),
      r.getTimestamp("received_at").toInstant(),
      r.getInt("max_score"),
      r.getString("challenge_snapshot"),
      scores ? r.getObject("points", Integer.class) : null,
      scores ? r.getString("feedback") : null,
      scores ? r.getLong("score_version") : 0,
      scores ? r.getObject("reviewer_id", Long.class) : null,
      scores && scored != null ? scored.toInstant() : null,
      r.getBoolean("latest")
    );
  }
}
