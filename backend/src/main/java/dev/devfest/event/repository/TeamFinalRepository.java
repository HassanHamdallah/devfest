package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableInt;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.RoomCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A team's progress in the crypto final: its one team_final row, the unlock and layer attempts, the one-time verdicts.
 * Every "only once" rule is a guarded UPDATE/INSERT (WHERE ... IS NULL, ON CONFLICT DO NOTHING) so a race cannot
 * record anything twice, whatever the service checked first.
 */
@Repository
public class TeamFinalRepository {

  /** The team_final row; {@code finalisedCause} is SUBMITTED, TIME_UP or CLOSED. */
  public record Row(
    long teamId,
    Instant unlockedAt,
    Instant caseStartedAt,
    Integer suspectId,
    Boolean suspectCorrect,
    Instant submittedAt,
    Integer activeSeconds,
    Instant finalisedAt,
    String finalisedCause
  ) {
    public boolean finalised() {
      return finalisedAt != null;
    }
  }

  /** Wrong answers since the last correct one, and when the latest attempt was evaluated. */
  public record Streak(int wrongInARow, Instant lastAttemptAt) {}

  public record Verdict(int layer, String verdict, boolean correct) {}

  /** The team's clues: how many, and the position-first room it is missing (null at 4). */
  public record ClueFacts(int clues, Long missingRoomId, int recoveryAttempts) {}

  /** One active team's progress as the scores and the status need it (one batch statement for all teams). */
  public record Progress(
    long teamId,
    int clues,
    List<RoomCode> clueRooms,
    Instant unlockedAt,
    int layersCorrect,
    int verdictsCorrect,
    Integer activeSeconds,
    boolean suspectCorrect,
    Instant submittedAt,
    Instant finalisedAt,
    String finalisedCause
  ) {
    public boolean finalised() {
      return finalisedAt != null;
    }
  }

  private static final String ROW = """
    SELECT team_id, unlocked_at, case_started_at, suspect_id, suspect_correct, submitted_at, active_seconds,
           finalised_at, finalised_cause
    FROM team_final
    """;

  private final JdbcTemplate db;

  public TeamFinalRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<Row> find(long teamId) {
    return db.query(ROW + "WHERE team_id = ?", TeamFinalRepository::mapRow, teamId).stream().findFirst();
  }

  /** The team's clue count, its first missing room and its recovery attempts, as ONE statement. */
  public ClueFacts clueFacts(long teamId) {
    return db.queryForObject(
      """
      SELECT (SELECT count(*) FROM collected_clue c WHERE c.team_id = t.id) AS clues,
             (SELECT r.id FROM event_room r
              WHERE NOT EXISTS (SELECT 1 FROM collected_clue c WHERE c.team_id = t.id AND c.room_id = r.id)
              ORDER BY r.position LIMIT 1) AS missing_room_id,
             (SELECT count(*) FROM recovery_attempt ra WHERE ra.team_id = t.id) AS recovery_attempts
      FROM event_team t WHERE t.id = ?""",
      (rs, row) -> new ClueFacts(rs.getInt("clues"), nullableLongOf(rs, "missing_room_id"), rs.getInt("recovery_attempts")),
      teamId
    );
  }

  /** What the team board needs about the final and the hints, as ONE statement. */
  public record BoardFacts(boolean finalised, int hintsUsed) {}

  public BoardFacts boardFacts(long teamId) {
    return db.queryForObject(
      """
      SELECT COALESCE((SELECT f.finalised_at IS NOT NULL FROM team_final f WHERE f.team_id = t.id), FALSE) AS finalised,
             (SELECT count(*) FROM score_deduction d WHERE d.team_id = t.id AND d.kind = 'HINT' AND d.cancelled_at IS NULL) AS hints
      FROM event_team t WHERE t.id = ?""",
      (rs, row) -> new BoardFacts(rs.getBoolean("finalised"), rs.getInt("hints")),
      teamId
    );
  }

  // ---- the row's one-time steps -----------------------------------------------------------------

  /** Marks the unlock (the first time only); creates the row if needed. */
  public void markUnlocked(long teamId, Instant at) {
    db.update(
      """
      INSERT INTO team_final(team_id, unlocked_at) VALUES (?, ?)
      ON CONFLICT (team_id) DO UPDATE SET unlocked_at = EXCLUDED.unlocked_at WHERE team_final.unlocked_at IS NULL""",
      teamId,
      timestamp(at)
    );
  }

  /** Marks "Start case" (the first time only, after the unlock). */
  public void markCaseStarted(long teamId, Instant at) {
    db.update("UPDATE team_final SET case_started_at = ? WHERE team_id = ? AND case_started_at IS NULL", timestamp(at), teamId);
  }

  /**
   * Records the one suspect and finalises the team in the same statement. Returns false (changing nothing) when a
   * suspect was already submitted or the team is already finalised, which is what makes a second try or a race with
   * the time-up finalisation harmless.
   */
  public boolean submitSuspect(long teamId, int suspectId, boolean correct, Instant at, int activeSeconds) {
    return db.update(
      """
      UPDATE team_final
      SET suspect_id = ?, suspect_correct = ?, submitted_at = ?, active_seconds = ?, finalised_at = ?,
          finalised_cause = 'SUBMITTED'
      WHERE team_id = ? AND finalised_at IS NULL AND unlocked_at IS NOT NULL AND case_started_at IS NOT NULL""",
      suspectId,
      correct,
      timestamp(at),
      activeSeconds,
      timestamp(at),
      teamId
    ) == 1;
  }

  /** Finalises one team once (creating its row if it never had one); false when it already was. */
  public boolean finalise(long teamId, String cause, Instant at) {
    return db.update(
      """
      INSERT INTO team_final(team_id, finalised_at, finalised_cause) VALUES (?, ?, ?)
      ON CONFLICT (team_id) DO UPDATE SET finalised_at = EXCLUDED.finalised_at, finalised_cause = EXCLUDED.finalised_cause
      WHERE team_final.finalised_at IS NULL""",
      teamId,
      timestamp(at),
      cause
    ) == 1;
  }

  /**
   * Finalises every active team that took part (holds at least 3 clues) and is not finalised yet, in ONE statement;
   * returns how many. Teams with fewer than 3 clues never entered the final and stay "Not qualified".
   */
  public int finaliseAllOpen(String cause, Instant at) {
    return db.update(
      """
      INSERT INTO team_final(team_id, finalised_at, finalised_cause)
      SELECT t.id, ?, ? FROM event_team t
      WHERE t.active AND (SELECT count(*) FROM collected_clue c WHERE c.team_id = t.id) >= 3
      ON CONFLICT (team_id) DO UPDATE SET finalised_at = EXCLUDED.finalised_at, finalised_cause = EXCLUDED.finalised_cause
      WHERE team_final.finalised_at IS NULL""",
      timestamp(at),
      cause
    );
  }

  // ---- unlock attempts --------------------------------------------------------------------------

  public void insertUnlockAttempt(UUID id, long teamId, String codes, boolean correct, Instant at, UUID key, String hash) {
    db.update(
      """
      INSERT INTO final_unlock_attempt(id, team_id, codes, correct, received_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?, ?, ?)""",
      id,
      teamId,
      codes,
      correct,
      timestamp(at),
      key,
      hash
    );
  }

  public Optional<StoredAttempt> findUnlockAttemptByKey(long teamId, UUID key) {
    return db.query(
      "SELECT id, correct, received_at, request_hash FROM final_unlock_attempt WHERE team_id = ? AND idempotency_key = ?",
      TeamFinalRepository::mapAttempt,
      teamId,
      key
    ).stream().findFirst();
  }

  public Streak unlockStreak(long teamId) {
    return db.queryForObject(
      """
      SELECT count(*) FILTER (WHERE NOT correct AND received_at > COALESCE(
               (SELECT max(received_at) FROM final_unlock_attempt WHERE team_id = ? AND correct), '-infinity'::timestamptz)) AS wrong,
             max(received_at) AS last_at
      FROM final_unlock_attempt WHERE team_id = ?""",
      (rs, row) -> new Streak(rs.getInt("wrong"), instant(rs, "last_at")),
      teamId,
      teamId
    );
  }

  // ---- layer attempts ---------------------------------------------------------------------------

  public void insertLayerAttempt(UUID id, long teamId, int layer, String value, boolean correct, Instant at, UUID key, String hash) {
    db.update(
      """
      INSERT INTO final_layer_attempt(id, team_id, layer, value, correct, received_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
      id,
      teamId,
      layer,
      value,
      correct,
      timestamp(at),
      key,
      hash
    );
  }

  public Optional<StoredAttempt> findLayerAttemptByKey(long teamId, UUID key) {
    return db.query(
      "SELECT id, correct, received_at, request_hash FROM final_layer_attempt WHERE team_id = ? AND idempotency_key = ?",
      TeamFinalRepository::mapAttempt,
      teamId,
      key
    ).stream().findFirst();
  }

  /** The layers the team solved, ascending. */
  public Set<Integer> solvedLayers(long teamId) {
    return new TreeSet<>(db.queryForList("SELECT layer FROM final_layer_attempt WHERE team_id = ? AND correct", Integer.class, teamId));
  }

  public Streak layerStreak(long teamId, int layer) {
    return db.queryForObject(
      """
      SELECT count(*) FILTER (WHERE NOT correct AND received_at > COALESCE(
               (SELECT max(received_at) FROM final_layer_attempt WHERE team_id = ? AND layer = ? AND correct), '-infinity'::timestamptz)) AS wrong,
             max(received_at) AS last_at
      FROM final_layer_attempt WHERE team_id = ? AND layer = ?""",
      (rs, row) -> new Streak(rs.getInt("wrong"), instant(rs, "last_at")),
      teamId,
      layer,
      teamId,
      layer
    );
  }

  // ---- verdicts ---------------------------------------------------------------------------------

  /** False when the team already gave its verdict for this report (the first one stands). */
  public boolean insertVerdict(long teamId, int layer, String verdict, boolean correct, Instant at) {
    return db.update(
      """
      INSERT INTO final_verdict(team_id, layer, verdict, correct, submitted_at) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (team_id, layer) DO NOTHING""",
      teamId,
      layer,
      verdict,
      correct,
      timestamp(at)
    ) == 1;
  }

  public List<Verdict> verdicts(long teamId) {
    return db.query(
      "SELECT layer, verdict, correct FROM final_verdict WHERE team_id = ? ORDER BY layer",
      (rs, row) -> new Verdict(rs.getInt("layer"), rs.getString("verdict"), rs.getBoolean("correct")),
      teamId
    );
  }

  // ---- all teams at once ------------------------------------------------------------------------

  /** Every active team's progress, as ONE statement whatever the number of teams. */
  public List<Progress> progressOfActiveTeams() {
    return db.query(
      """
      SELECT t.id AS team_id, COALESCE(cl.n, 0) AS clues, cl.rooms AS clue_rooms, f.unlocked_at, COALESCE(ly.n, 0) AS layers,
             COALESCE(vd.n, 0) AS verdicts, f.active_seconds, COALESCE(f.suspect_correct, FALSE) AS suspect_correct,
             f.submitted_at, f.finalised_at, f.finalised_cause
      FROM event_team t
      LEFT JOIN team_final f ON f.team_id = t.id
      LEFT JOIN (
        SELECT c.team_id, count(*) AS n, array_agg(r.code ORDER BY r.position) AS rooms
        FROM collected_clue c JOIN event_room r ON r.id = c.room_id GROUP BY c.team_id
      ) cl ON cl.team_id = t.id
      LEFT JOIN (SELECT team_id, count(*) AS n FROM final_layer_attempt WHERE correct GROUP BY team_id) ly ON ly.team_id = t.id
      LEFT JOIN (SELECT team_id, count(*) AS n FROM final_verdict WHERE correct GROUP BY team_id) vd ON vd.team_id = t.id
      WHERE t.active
      ORDER BY t.id""",
      (rs, row) ->
        new Progress(
          rs.getLong("team_id"),
          rs.getInt("clues"),
          clueRooms(rs),
          instant(rs, "unlocked_at"),
          rs.getInt("layers"),
          rs.getInt("verdicts"),
          nullableInt(rs, "active_seconds"),
          rs.getBoolean("suspect_correct"),
          instant(rs, "submitted_at"),
          instant(rs, "finalised_at"),
          rs.getString("finalised_cause")
        )
    );
  }

  /** One active team's counters for the admin's live table (one batch statement). */
  public record AdminCounts(
    long teamId,
    int clues,
    boolean unlocked,
    int unlockAttempts,
    int unlockWrong,
    int layerAttempts,
    int layerWrong,
    int recoveryAttempts,
    String verdictE03,
    Boolean verdictE03Correct,
    String verdictE04,
    Boolean verdictE04Correct,
    Integer suspectId,
    Boolean suspectCorrect,
    Instant caseStartedAt,
    String finalisedCause
  ) {}

  /** Attempts, verdicts and the suspect of every active team, as ONE statement whatever the number of teams. */
  public List<AdminCounts> adminCounts() {
    return db.query(
      """
      SELECT t.id AS team_id,
             (SELECT count(*) FROM collected_clue c WHERE c.team_id = t.id) AS clues,
             (f.unlocked_at IS NOT NULL) AS unlocked,
             (SELECT count(*) FROM final_unlock_attempt a WHERE a.team_id = t.id) AS unlock_attempts,
             (SELECT count(*) FROM final_unlock_attempt a WHERE a.team_id = t.id AND NOT a.correct) AS unlock_wrong,
             (SELECT count(*) FROM final_layer_attempt a WHERE a.team_id = t.id) AS layer_attempts,
             (SELECT count(*) FROM final_layer_attempt a WHERE a.team_id = t.id AND NOT a.correct) AS layer_wrong,
             (SELECT count(*) FROM recovery_attempt a WHERE a.team_id = t.id) AS recovery_attempts,
             (SELECT v.verdict FROM final_verdict v WHERE v.team_id = t.id AND v.layer = 3) AS v3,
             (SELECT v.correct FROM final_verdict v WHERE v.team_id = t.id AND v.layer = 3) AS v3c,
             (SELECT v.verdict FROM final_verdict v WHERE v.team_id = t.id AND v.layer = 4) AS v4,
             (SELECT v.correct FROM final_verdict v WHERE v.team_id = t.id AND v.layer = 4) AS v4c,
             f.suspect_id, f.suspect_correct, f.case_started_at, f.finalised_cause
      FROM event_team t LEFT JOIN team_final f ON f.team_id = t.id
      WHERE t.active ORDER BY t.id""",
      (rs, row) ->
        new AdminCounts(
          rs.getLong("team_id"),
          rs.getInt("clues"),
          rs.getBoolean("unlocked"),
          rs.getInt("unlock_attempts"),
          rs.getInt("unlock_wrong"),
          rs.getInt("layer_attempts"),
          rs.getInt("layer_wrong"),
          rs.getInt("recovery_attempts"),
          rs.getString("v3"),
          rs.getObject("v3c", Boolean.class),
          rs.getString("v4"),
          rs.getObject("v4c", Boolean.class),
          nullableInt(rs, "suspect_id"),
          rs.getObject("suspect_correct", Boolean.class),
          instant(rs, "case_started_at"),
          rs.getString("finalised_cause")
        )
    );
  }

  /** The rooms whose clue the team holds, in room position order (empty when none). */
  private static List<RoomCode> clueRooms(ResultSet rs) throws SQLException {
    java.sql.Array array = rs.getArray("clue_rooms");
    if (array == null) return List.of();
    try {
      return java.util.Arrays.stream((String[]) array.getArray()).map(RoomCode::valueOf).toList();
    } finally {
      array.free();
    }
  }

  // ---- mapping ----------------------------------------------------------------------------------

  /** A stored attempt, enough to replay its answer and to check the idempotency hash. */
  public record StoredAttempt(UUID id, boolean correct, Instant receivedAt, String requestHash) {}

  private static StoredAttempt mapAttempt(ResultSet rs, int row) throws SQLException {
    return new StoredAttempt(uuid(rs, "id"), rs.getBoolean("correct"), instant(rs, "received_at"), rs.getString("request_hash"));
  }

  private static Row mapRow(ResultSet rs, int row) throws SQLException {
    return new Row(
      rs.getLong("team_id"),
      instant(rs, "unlocked_at"),
      instant(rs, "case_started_at"),
      nullableInt(rs, "suspect_id"),
      rs.getObject("suspect_correct", Boolean.class),
      instant(rs, "submitted_at"),
      nullableInt(rs, "active_seconds"),
      instant(rs, "finalised_at"),
      rs.getString("finalised_cause")
    );
  }

  private static Long nullableLongOf(ResultSet rs, String column) throws SQLException {
    return EventJdbc.nullableLong(rs, column);
  }
}
