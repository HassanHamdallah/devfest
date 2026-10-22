package dev.devfest.judge;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueueStore {

  public record Job(
    UUID id,
    UUID submission,
    UUID owner,
    int attempts,
    String kind,
    long problem,
    int version,
    String language,
    int languageId,
    String source,
    String stdin
  ) {}

  private final JdbcTemplate db;

  public QueueStore(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public Job claim(int concurrency) {
    // A database-wide scheduler lock bounds concurrency even if two app instances run.
    db.queryForList("SELECT pg_advisory_xact_lock(9182301)");
    db.update(
      "UPDATE evaluation SET state='SYSTEM_ERROR',finished_at=now(),lease_owner=NULL,lease_until=NULL WHERE state='RUNNING' AND lease_until<now() AND attempts>=3"
    );
    db.update(
      "UPDATE evaluation SET state='QUEUED',lease_owner=NULL,lease_until=NULL,available_at=now() WHERE state='RUNNING' AND lease_until<now() AND attempts<3"
    );
    db.update(
      "UPDATE evaluation SET state='SYSTEM_ERROR',finished_at=now() WHERE state='QUEUED' AND available_at<now()-interval '30 minutes'"
    );
    if (
      db.queryForObject(
        "SELECT count(*) FROM evaluation WHERE state='RUNNING'",
        Long.class
      ) >= concurrency
    ) return null;
    var rows = db.queryForList(
      "SELECT e.id FROM evaluation e JOIN submission s ON s.id=e.submission_id JOIN app_user u ON u.id=s.user_id WHERE e.state='QUEUED' AND e.available_at<=now() AND NOT EXISTS(SELECT 1 FROM evaluation x JOIN submission y ON y.id=x.submission_id WHERE x.state='RUNNING' AND y.user_id=s.user_id) ORDER BY u.last_dispatch,s.received_at LIMIT 1 FOR UPDATE OF e SKIP LOCKED"
    );
    if (rows.isEmpty()) return null;
    UUID id = (UUID) rows.getFirst().get("id"),
      owner = UUID.randomUUID();
    db.update(
      "UPDATE evaluation SET state='RUNNING',attempts=attempts+1,lease_owner=?,lease_until=now()+interval '45 seconds',started_at=coalesce(started_at,now()) WHERE id=?",
      owner,
      id
    );
    db.update(
      "UPDATE app_user SET last_dispatch=now() WHERE id=(SELECT s.user_id FROM submission s JOIN evaluation e ON e.submission_id=s.id WHERE e.id=?)",
      id
    );
    return db.queryForObject(
      "SELECT e.*,s.kind,s.problem_id,s.problem_version,s.language,s.language_id,s.source,s.stdin FROM evaluation e JOIN submission s ON s.id=e.submission_id WHERE e.id=?",
      (r, n) ->
        new Job(
          id,
          (UUID) r.getObject("submission_id"),
          owner,
          r.getInt("attempts"),
          r.getString("kind"),
          r.getLong("problem_id"),
          r.getInt("problem_version"),
          r.getString("language"),
          r.getInt("language_id"),
          r.getString("source"),
          r.getString("stdin")
        ),
      id
    );
  }

  public boolean renew(Job j) {
    return (
      db.update(
        "UPDATE evaluation SET lease_until=now()+interval '45 seconds' WHERE id=? AND lease_owner=? AND state='RUNNING' AND lease_until>now()",
        j.id(),
        j.owner()
      ) == 1
    );
  }

  public String token(Job j, int index) {
    var rows = db.query(
      "SELECT provider_token FROM execution_task WHERE evaluation_id=? AND test_index=?",
      (r, n) -> r.getString(1),
      j.id(),
      index
    );
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void token(Job j, int index, String token) {
    db.update(
      "INSERT INTO execution_task VALUES(?,?,?) ON CONFLICT DO NOTHING",
      j.id(),
      index,
      token
    );
  }

  public void forgetToken(Job j, int index) {
    db.update(
      "DELETE FROM execution_task WHERE evaluation_id=? AND test_index=?",
      j.id(),
      index
    );
  }

  public void finish(
    Job j,
    String state,
    int passed,
    JudgeClient.Result result
  ) {
    boolean run = j.kind().equals("RUN");
    db.update(
      "UPDATE evaluation SET state=?,passed=?,stdout=?,stderr=?,compile_output=?,finished_at=now(),lease_owner=NULL,lease_until=NULL WHERE id=? AND state='RUNNING' AND lease_owner=? AND lease_until>now()",
      state,
      passed,
      run && result != null ? result.stdout() : "",
      run && result != null ? result.stderr() : "",
      run && result != null ? result.compileOutput() : "",
      j.id(),
      j.owner()
    );
  }

  public void retry(Job j) {
    db.update(
      "UPDATE evaluation SET state=CASE WHEN attempts>=3 THEN 'SYSTEM_ERROR' ELSE 'QUEUED' END,available_at=now()+interval '5 seconds',finished_at=CASE WHEN attempts>=3 THEN now() ELSE NULL END,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=? AND state='RUNNING'",
      j.id(),
      j.owner()
    );
  }
}
