package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableInt;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.recovery.RecoveryLevel;
import dev.devfest.event.domain.recovery.RecoveryPath.Step;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The final's recovery: one team_recovery row per team (K = the room keys it held when the final opened, and when it
 * finished its recovery) and the append-only recovery checks. Every "only once" rule is a guarded statement (ON
 * CONFLICT DO NOTHING, WHERE ... IS NULL, a unique index on the passed stage).
 */
@Repository
public class RecoveryRepository {

  /** A team's row: K, and the moment (time and active final seconds) its last required level was passed. */
  public record TeamRow(long teamId, int keysAtOpen, Instant finishedAt, Integer finishedActiveSeconds) {}

  /** One stored check, enough to replay its answer. {@code outcome} is what the team was told, as JSON. */
  public record Check(
    UUID id,
    long teamId,
    RecoveryLevel level,
    int stage,
    String submission,
    boolean passed,
    String outcome,
    Instant receivedAt,
    String requestHash
  ) {}

  /** One active team for the admin's recovery table (K falls back to its own keys while it has no row yet). */
  public record TeamProgress(long teamId, String teamName, int keysAtOpen, Instant finishedAt, Integer finishedActiveSeconds) {}

  /** The checks of one team on one stage: how many, whether one passed, and the latest. */
  public record StageCount(long teamId, RecoveryLevel level, int stage, int checks, boolean passed, Instant lastAt) {}

  /** K counted from the team's held keys, never from keys the recovery itself granted. */
  private static final String OWN_KEYS = "(SELECT count(*) FROM collected_clue c WHERE c.team_id = t.id AND c.source <> 'RECOVERY')";

  private final JdbcTemplate db;

  public RecoveryRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Records K for every active team at the moment the final opens (a team that already has a row keeps it). */
  public void snapshotKeysAtOpen() {
    db.update(
      "INSERT INTO team_recovery(team_id, keys_at_open) SELECT t.id, " + OWN_KEYS + " FROM event_team t WHERE t.active ON CONFLICT (team_id) DO NOTHING"
    );
  }

  /**
   * The team's row, locked for this transaction; created first from the keys it holds now if it was activated after the
   * final opened. Two statements.
   */
  public TeamRow lockTeamRow(long teamId) {
    db.update(
      "INSERT INTO team_recovery(team_id, keys_at_open) SELECT t.id, " + OWN_KEYS + " FROM event_team t WHERE t.id = ? ON CONFLICT (team_id) DO NOTHING",
      teamId
    );
    return db.queryForObject(
      "SELECT team_id, keys_at_open, finished_at, finished_active_seconds FROM team_recovery WHERE team_id = ? FOR UPDATE",
      (rs, row) -> new TeamRow(rs.getLong("team_id"), rs.getInt("keys_at_open"), instant(rs, "finished_at"), nullableInt(rs, "finished_active_seconds")),
      teamId
    );
  }

  /** Marks the recovery finished once; false (changing nothing) when it already was. */
  public boolean finish(long teamId, Instant at, int activeSeconds) {
    return db.update(
      "UPDATE team_recovery SET finished_at = ?, finished_active_seconds = ? WHERE team_id = ? AND finished_at IS NULL",
      timestamp(at),
      activeSeconds,
      teamId
    ) == 1;
  }

  // ---- checks -------------------------------------------------------------------------------------------------------

  public void insertCheck(Check check, UUID idempotencyKey) {
    db.update(
      """
      INSERT INTO recovery_check(id, team_id, level, stage, submission, passed, outcome, received_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)""",
      check.id(),
      check.teamId(),
      check.level().name(),
      check.stage(),
      check.submission(),
      check.passed(),
      check.outcome(),
      timestamp(check.receivedAt()),
      idempotencyKey,
      check.requestHash()
    );
  }

  public Optional<Check> findByIdempotencyKey(long teamId, UUID idempotencyKey) {
    return db.query(CHECK + "WHERE team_id = ? AND idempotency_key = ?", RecoveryRepository::mapCheck, teamId, idempotencyKey).stream().findFirst();
  }

  /** The team's latest check of the level (the rate limit and the last test results). */
  public Optional<Check> latest(long teamId, RecoveryLevel level) {
    return db.query(CHECK + "WHERE team_id = ? AND level = ? ORDER BY received_at DESC, id LIMIT 1", RecoveryRepository::mapCheck, teamId, level.name())
      .stream()
      .findFirst();
  }

  /** Every stage the team has passed, as ONE statement. */
  public Set<Step> passedSteps(long teamId) {
    Set<Step> passed = new HashSet<>();
    db.query(
      "SELECT level, stage FROM recovery_check WHERE team_id = ? AND passed",
      (ResultSet rs) -> {
        passed.add(new Step(RecoveryLevel.valueOf(rs.getString("level")), rs.getInt("stage")));
      },
      teamId
    );
    return passed;
  }

  // ---- all teams at once (admin) --------------------------------------------------------------------------------------

  /** Every active team's K and finish, as ONE statement whatever the number of teams. */
  public List<TeamProgress> progressOfActiveTeams() {
    return db.query(
      """
      SELECT t.id AS team_id, t.name AS team_name, COALESCE(r.keys_at_open, %s) AS keys_at_open,
             r.finished_at, r.finished_active_seconds
      FROM event_team t LEFT JOIN team_recovery r ON r.team_id = t.id
      WHERE t.active ORDER BY t.name, t.id""".formatted(OWN_KEYS),
      (rs, row) ->
        new TeamProgress(
          rs.getLong("team_id"),
          rs.getString("team_name"),
          rs.getInt("keys_at_open"),
          instant(rs, "finished_at"),
          nullableInt(rs, "finished_active_seconds")
        )
    );
  }

  /** Every team's checks per stage, as ONE statement whatever the number of teams. */
  public List<StageCount> stageCounts() {
    return db.query(
      """
      SELECT team_id, level, stage, count(*) AS checks, bool_or(passed) AS passed, max(received_at) AS last_at
      FROM recovery_check GROUP BY team_id, level, stage""",
      (rs, row) ->
        new StageCount(
          rs.getLong("team_id"),
          RecoveryLevel.valueOf(rs.getString("level")),
          rs.getInt("stage"),
          rs.getInt("checks"),
          rs.getBoolean("passed"),
          instant(rs, "last_at")
        )
    );
  }

  // ---- mapping ------------------------------------------------------------------------------------------------------

  private static final String CHECK = """
    SELECT id, team_id, level, stage, submission::text AS submission, passed, outcome::text AS outcome, received_at, request_hash
    FROM recovery_check
    """;

  private static Check mapCheck(ResultSet rs, int row) throws SQLException {
    return new Check(
      uuid(rs, "id"),
      rs.getLong("team_id"),
      RecoveryLevel.valueOf(rs.getString("level")),
      rs.getInt("stage"),
      rs.getString("submission"),
      rs.getBoolean("passed"),
      rs.getString("outcome"),
      instant(rs, "received_at"),
      rs.getString("request_hash")
    );
  }
}
