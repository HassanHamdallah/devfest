package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Applied round time adjustments, keyed by (round, Idempotency-Key). */
@Repository
public class RoundAdjustmentRepository {

  private final JdbcTemplate db;

  public RoundAdjustmentRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Returns false when this key was already used in this round, i.e. the request is a repeat. */
  public boolean record(int round, UUID idempotencyKey, int deltaSeconds, long appliedBy, Instant at) {
    return db.update(
      """
      INSERT INTO round_time_adjustment(round, idempotency_key, delta_seconds, applied_by, applied_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (round, idempotency_key) DO NOTHING""",
      round,
      idempotencyKey,
      deltaSeconds,
      appliedBy,
      timestamp(at)
    ) == 1;
  }
}
