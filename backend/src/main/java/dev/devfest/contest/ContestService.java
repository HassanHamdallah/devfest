package dev.devfest.contest;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ContestService {

  public record Contest(
    String title,
    Instant startAt,
    Instant endAt,
    Instant serverNow
  ) {}

  public record Draft(String source, long version, Instant updatedAt) {}

  public record Score(
    long userId,
    String name,
    long solved,
    Instant lastAccepted,
    int rank
  ) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;
  private final AuditLog auditLog;

  public ContestService(
    JdbcTemplate db,
    ObjectMapper json,
    Clock clock,
    AuditLog auditLog
  ) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.auditLog = auditLog;
  }

  public Contest contest() {
    return db.queryForObject("SELECT * FROM contest WHERE id=1", (r, n) ->
      new Contest(
        r.getString("title"),
        r.getTimestamp("start_at").toInstant(),
        r.getTimestamp("end_at").toInstant(),
        clock.instant()
      )
    );
  }

  public void requireOpen(Instant received) {
    var c = contest();
    if (!eligible(received, c.startAt(), c.endAt())) throw fail(
      409,
      "The contest is not accepting requests now."
    );
  }

  public static boolean eligible(Instant received, Instant start, Instant end) {
    return !received.isBefore(start) && received.isBefore(end);
  }

  public int version(long id) {
    var v = db.query(
      "SELECT current_version FROM problem WHERE id=?",
      (r, n) -> r.getInt(1),
      id
    );
    if (v.isEmpty()) throw fail(404, "Problem not found.");
    return v.getFirst();
  }

  public ProblemData problem(long id, int version) {
    var rows = db.query(
      "SELECT document FROM problem_version WHERE problem_id=? AND version=?",
      (r, n) -> r.getString(1),
      id,
      version
    );
    if (rows.isEmpty()) throw fail(404, "Problem not found.");
    return json.readValue(rows.getFirst(), ProblemData.class);
  }

  public List<ProblemData.PublicProblem> problems(int page) {
    return db.query(
      "SELECT p.id,p.current_version,v.document FROM problem p JOIN problem_version v ON v.problem_id=p.id AND v.version=p.current_version ORDER BY p.id LIMIT 50 OFFSET ?",
      (r, n) ->
        json
          .readValue(r.getString(3), ProblemData.class)
          .publicView(r.getLong(1), r.getInt(2)),
      Math.max(0, page) * 50
    );
  }

  @Transactional
  public long saveProblem(Long id, ProblemData data, long actor) {
    if (
      data.languages().stream().distinct().count() != data.languages().size()
    ) throw fail(400, "Languages must be unique.");
    for (var t : data.tests()) {
      size(t.input());
      size(t.output());
    }
    int v = 1;
    if (id == null) {
      id = db.queryForObject(
        "INSERT INTO problem DEFAULT VALUES RETURNING id",
        Long.class
      );
    } else {
      var values = db.query(
        "SELECT current_version FROM problem WHERE id=? FOR UPDATE",
        (r, n) -> r.getInt(1),
        id
      );
      if (values.isEmpty()) throw fail(404, "Problem not found.");
      v = values.getFirst() + 1;
      db.update("UPDATE problem SET current_version=? WHERE id=?", v, id);
    }
    db.update(
      "INSERT INTO problem_version VALUES(?,?,?,now())",
      id,
      v,
      json.writeValueAsString(data)
    );
    audit(actor, "SAVE_PROBLEM", id.toString());
    return id;
  }

  public static void size(String text) {
    if (
      text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536
    ) throw fail(400, "Text exceeds the 64 KiB limit.");
  }

  public Draft draft(long user, long problem, ProblemData.Language language) {
    version(problem);
    var rows = db.query(
      "SELECT source,version,updated_at FROM draft WHERE user_id=? AND contest_id=1 AND problem_id=? AND language=?",
      (r, n) ->
        new Draft(r.getString(1), r.getLong(2), r.getTimestamp(3).toInstant()),
      user,
      problem,
      language.name()
    );
    return rows.isEmpty() ? new Draft("", 0, null) : rows.getFirst();
  }

  @Transactional
  public Draft saveDraft(
    long user,
    long problem,
    ProblemData.Language language,
    String source,
    long version
  ) {
    size(source);
    this.version(problem);
    int changed;
    if (version == 0) {
      changed = db.update(
        "INSERT INTO draft(user_id,problem_id,language,source) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
        user,
        problem,
        language.name(),
        source
      );
    } else {
      changed = db.update(
        "UPDATE draft SET source=?,version=version+1,updated_at=now() WHERE user_id=? AND contest_id=1 AND problem_id=? AND language=? AND version=?",
        source,
        user,
        problem,
        language.name(),
        version
      );
    }
    if (changed != 1) throw fail(
      409,
      "A newer draft exists. Keep your local copy or load the server copy."
    );
    return draft(user, problem, language);
  }

  public List<Score> scores() {
    var rows = db.query(
      "WITH solved AS (SELECT s.user_id,s.problem_id,min(s.received_at) accepted FROM submission s JOIN evaluation e ON e.submission_id=s.id AND e.generation=s.current_generation WHERE s.kind='SUBMIT' AND e.state='ACCEPTED' GROUP BY s.user_id,s.problem_id) SELECT u.id,u.name,count(s.problem_id) solved,max(s.accepted) last_accepted FROM app_user u LEFT JOIN solved s ON s.user_id=u.id WHERE u.role='PARTICIPANT' GROUP BY u.id,u.name ORDER BY solved DESC,last_accepted ASC NULLS LAST,u.id",
      (r, n) ->
        new Score(
          r.getLong(1),
          r.getString(2),
          r.getLong(3),
          r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant(),
          0
        )
    );
    var out = new ArrayList<Score>();
    int rank = 0;
    Score previous = null;
    for (int i = 0; i < rows.size(); i++) {
      var s = rows.get(i);
      if (
        previous == null ||
        s.solved() != previous.solved() ||
        !Objects.equals(s.lastAccepted(), previous.lastAccepted())
      ) rank = i + 1;
      out.add(
        new Score(s.userId(), s.name(), s.solved(), s.lastAccepted(), rank)
      );
      previous = s;
    }
    return out;
  }

  public void audit(long actor, String action, String target) {
    auditLog.record(actor, action, target);
  }
}
