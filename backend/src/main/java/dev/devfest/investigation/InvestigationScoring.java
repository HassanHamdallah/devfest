package dev.devfest.investigation;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.contest.ContestService;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestigationScoring {

  public static final String RULES =
    "Answers may be resubmitted until closing; each accepted version is retained. Only the latest submission per team and challenge contributes points. Final reports lock on submission. Total points are the sum of the latest submission scores; unscored work contributes zero and is marked pending. Teams are ranked within their room because rooms may have different challenges. Equal totals share competition rank (1, 1, 3). Opening is inclusive and closing exclusive, using server time after transaction locks are acquired. Archived teams remain in results. Any accepted answer, score, challenge, room or team change unpublishes results.";

  public record ScoreEdit(
    @Min(0) int points,
    @NotNull @Size(max = 4000) String feedback,
    @Min(0) long version
  ) {}

  public record Settings(
    boolean rulesConfirmed,
    boolean resultsPublished,
    String rules
  ) {}

  public record Result(
    long teamId,
    String team,
    long roomId,
    long total,
    long pending,
    long rank
  ) {}

  private final JdbcTemplate db;
  private final InvestigationAccess access;
  private final InvestigationChallenges challenges;
  private final InvestigationSubmissions submissions;
  private final ContestService audit;
  private final Clock clock;

  public InvestigationScoring(
    JdbcTemplate db,
    InvestigationAccess access,
    InvestigationChallenges challenges,
    InvestigationSubmissions submissions,
    ContestService audit,
    Clock clock
  ) {
    this.db = db;
    this.access = access;
    this.challenges = challenges;
    this.submissions = submissions;
    this.audit = audit;
    this.clock = clock;
  }

  public Settings settings() {
    return db.queryForObject(
      "SELECT * FROM investigation_settings WHERE id=1",
      (r, n) ->
        new Settings(
          r.getBoolean("rules_confirmed"),
          r.getBoolean("results_published"),
          RULES
        )
    );
  }

  @Transactional
  public void confirm(Principal p) {
    access.admin(p);
    challenges.lockSettings();
    db.update(
      "UPDATE investigation_settings SET rules_confirmed=true WHERE id=1"
    );
    audit.audit(p.id(), "INV_CONFIRM_RULES", "1");
  }

  @Transactional
  public void publish(Principal p, boolean published) {
    access.admin(p);
    challenges.lockSettings();
    if (published && !settings().rulesConfirmed()) throw fail(
      409,
      "Confirm the rules first."
    );
    db.update(
      "UPDATE investigation_settings SET results_published=? WHERE id=1",
      published
    );
    audit.audit(p.id(), "INV_PUBLISH_" + published, "1");
  }

  @Transactional
  public void score(Principal p, UUID id, ScoreEdit e) {
    challenges.lockSettings();
    var s = submissions.get(p, id);
    access.reviewer(p, s.roomId());
    if (e.points() > s.maxScore()) throw fail(
      400,
      "Score exceeds the challenge maximum."
    );
    // Identical retries return success even after the version advanced; changed stale writes conflict.
    if (
      s.points() != null &&
      s.points() == e.points() &&
      Objects.equals(s.feedback(), e.feedback()) &&
      Objects.equals(s.reviewerId(), p.id())
    ) return;
    if (s.scoreVersion() != e.version()) throw fail(
      409,
      "Another reviewer changed this score. Reload before saving."
    );
    var now = Timestamp.from(clock.instant());
    db.update(
      "INSERT INTO investigation_score(submission_id,points,feedback,reviewer_id,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(submission_id) DO UPDATE SET points=excluded.points,feedback=excluded.feedback,reviewer_id=excluded.reviewer_id,updated_at=excluded.updated_at,version=investigation_score.version+1",
      id,
      e.points(),
      e.feedback(),
      p.id(),
      now
    );
    db.update(
      "INSERT INTO investigation_score_history(submission_id,old_points,points,feedback,reviewer_id,changed_at,version) VALUES(?,?,?,?,?,?,?)",
      id,
      s.points(),
      e.points(),
      e.feedback(),
      p.id(),
      now,
      s.scoreVersion() + 1
    );
    challenges.unpublish();
    audit.audit(p.id(), "INV_SCORE", id + ":" + s.points() + "->" + e.points());
  }

  public List<Map<String, Object>> history(Principal p, UUID id) {
    var s = submissions.get(p, id);
    access.reviewer(p, s.roomId());
    return db.queryForList(
      "SELECT old_points,points,feedback,reviewer_id,changed_at,version FROM investigation_score_history WHERE submission_id=? ORDER BY version",
      id
    );
  }

  public List<Result> results(Principal p, long room) {
    access.room(p, room);
    if (p.role().equals("MEMBER") && !settings().resultsPublished()) throw fail(
      403,
      "Results have not been published."
    );
    // Rank within each room: different rooms can have different challenges and maximum points.
    return db.query(
      "WITH latest AS (SELECT DISTINCT ON(team_id,challenge_id) id,team_id FROM investigation_submission ORDER BY team_id,challenge_id,sequence DESC), totals AS (SELECT t.id,t.name,t.room_id,coalesce(sum(s.points),0) total,count(l.id) FILTER(WHERE s.submission_id IS NULL) pending FROM team t LEFT JOIN latest l ON l.team_id=t.id LEFT JOIN investigation_score s ON s.submission_id=l.id WHERE t.room_id=? GROUP BY t.id) SELECT *,rank() OVER(ORDER BY total DESC) position FROM totals ORDER BY total DESC,id",
      (r, n) ->
        new Result(
          r.getLong("id"),
          r.getString("name"),
          r.getLong("room_id"),
          r.getLong("total"),
          r.getLong("pending"),
          r.getLong("position")
        ),
      room
    );
  }
}
