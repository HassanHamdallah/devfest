package dev.devfest.submission;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService;
import dev.devfest.contest.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionService {

  public record Create(
    long problemId,
    ProblemData.Language language,
    String source,
    String stdin
  ) {}

  public record View(
    UUID id,
    long userId,
    long problemId,
    int problemVersion,
    String kind,
    String language,
    String runtime,
    Instant receivedAt,
    String state,
    int generation,
    int passed,
    int total,
    String source,
    String stdout,
    String stderr,
    String compileOutput
  ) {}

  public record History(
    int generation,
    String state,
    Instant startedAt,
    Instant finishedAt,
    int attempts
  ) {}

  private final JdbcTemplate db;
  private final ContestService contest;
  private final Clock clock;

  public SubmissionService(
    JdbcTemplate db,
    ContestService contest,
    Clock clock
  ) {
    this.db = db;
    this.contest = contest;
    this.clock = clock;
  }

  @Transactional
  public UUID create(long user, String kind, UUID key, Create r) {
    // Serializes a participant's concurrent submissions; duplicate lookup precedes deadline checks.
    var active = db.queryForObject(
      "SELECT active FROM app_user WHERE id=? FOR UPDATE",
      Boolean.class,
      user
    );
    if (!Boolean.TRUE.equals(active)) throw fail(401, "Account inactive.");
    String fingerprint = AuthService.hash(
      r.problemId() +
        "|" +
        r.language() +
        "|" +
        r.source().length() +
        ":" +
        r.source() +
        "|" +
        r.stdin()
    );
    var old = db.queryForList(
      "SELECT id,request_hash FROM submission WHERE user_id=? AND kind=? AND idempotency_key=?",
      user,
      kind,
      key
    );
    if (!old.isEmpty()) {
      if (!old.getFirst().get("request_hash").equals(fingerprint)) throw fail(
        409,
        "This request key was already used for different code."
      );
      return (UUID) old.getFirst().get("id");
    }
    Instant received = clock.instant();
    contest.requireOpen(received);
    ContestService.size(r.source());
    ContestService.size(r.stdin());
    if (
      db.queryForObject(
        "SELECT count(*) FROM submission s JOIN evaluation e ON e.submission_id=s.id AND e.generation=s.current_generation WHERE s.user_id=? AND e.state IN ('QUEUED','RUNNING')",
        Long.class,
        user
      ) >= 3
    ) throw fail(
      429,
      "You already have three evaluations waiting. Please wait."
    );
    int version = contest.version(r.problemId());
    var problem = contest.problem(r.problemId(), version);
    if (!problem.languages().contains(r.language())) throw fail(
      400,
      "This language is not allowed for this problem."
    );
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO submission(id,user_id,problem_id,problem_version,kind,language,runtime,language_id,source,stdin,received_at,idempotency_key,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
      id,
      user,
      r.problemId(),
      version,
      kind,
      r.language().name(),
      r.language().runtime,
      r.language().providerId,
      r.source(),
      kind.equals("RUN") ? r.stdin() : "",
      Timestamp.from(received),
      key,
      fingerprint
    );
    db.update(
      "INSERT INTO evaluation(id,submission_id,generation,state,total) VALUES(?,?,1,'QUEUED',?)",
      UUID.randomUUID(),
      id,
      kind.equals("RUN") ? 1 : problem.tests().size()
    );
    return id;
  }

  private static final String SELECT =
    "SELECT s.*,e.state,e.passed,e.total,e.stdout,e.stderr,e.compile_output FROM submission s JOIN evaluation e ON e.submission_id=s.id AND e.generation=s.current_generation ";

  private View view(java.sql.ResultSet r, boolean detail)
    throws java.sql.SQLException {
    boolean run = r.getString("kind").equals("RUN");
    return new View(
      (UUID) r.getObject("id"),
      r.getLong("user_id"),
      r.getLong("problem_id"),
      r.getInt("problem_version"),
      r.getString("kind"),
      r.getString("language"),
      r.getString("runtime"),
      r.getTimestamp("received_at").toInstant(),
      r.getString("state"),
      r.getInt("current_generation"),
      r.getInt("passed"),
      r.getInt("total"),
      detail ? r.getString("source") : null,
      detail && run ? r.getString("stdout") : "",
      detail && run ? r.getString("stderr") : "",
      detail && run ? r.getString("compile_output") : ""
    );
  }

  public View get(UUID id, AuthService.Principal user) {
    var rows = db.query(
      SELECT + "WHERE s.id=? AND (s.user_id=? OR ?)",
      (r, n) -> view(r, true),
      id,
      user.id(),
      user.role().equals("ADMIN")
    );
    if (rows.isEmpty()) throw fail(404, "Submission not found.");
    return rows.getFirst();
  }

  public List<View> list(AuthService.Principal user, int page, String state) {
    return db.query(
      SELECT +
        "WHERE (s.user_id=? OR ?) AND (?='' OR e.state=?) ORDER BY s.received_at DESC LIMIT 50 OFFSET ?",
      (r, n) -> view(r, false),
      user.id(),
      user.role().equals("ADMIN"),
      state,
      state,
      Math.max(0, page) * 50
    );
  }

  public List<History> history(UUID id, AuthService.Principal user) {
    get(id, user);
    return db.query(
      "SELECT * FROM evaluation WHERE submission_id=? ORDER BY generation DESC",
      (r, n) ->
        new History(
          r.getInt("generation"),
          r.getString("state"),
          r.getTimestamp("started_at") == null
            ? null
            : r.getTimestamp("started_at").toInstant(),
          r.getTimestamp("finished_at") == null
            ? null
            : r.getTimestamp("finished_at").toInstant(),
          r.getInt("attempts")
        ),
      id
    );
  }

  @Transactional
  public void rejudge(UUID id, long actor) {
    var rows = db.queryForList(
      "SELECT * FROM submission WHERE id=? AND kind='SUBMIT' FOR UPDATE",
      id
    );
    if (rows.isEmpty()) throw fail(404, "Submission not found.");
    var r = rows.getFirst();
    int generation = ((Number) r.get("current_generation")).intValue();
    String state = db.queryForObject(
      "SELECT state FROM evaluation WHERE submission_id=? AND generation=?",
      String.class,
      id,
      generation
    );
    if (List.of("QUEUED", "RUNNING").contains(state)) throw fail(
      409,
      "Evaluation is still pending."
    );
    db.update(
      "UPDATE submission SET current_generation=current_generation+1 WHERE id=?",
      id
    );
    db.update(
      "INSERT INTO evaluation(id,submission_id,generation,state,total) SELECT ?,submission_id,generation+1,'QUEUED',total FROM evaluation WHERE submission_id=? AND generation=?",
      UUID.randomUUID(),
      id,
      generation
    );
    contest.audit(actor, "REJUDGE", id.toString());
  }
}
