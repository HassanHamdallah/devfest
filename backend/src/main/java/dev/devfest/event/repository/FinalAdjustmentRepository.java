package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Time added to the final's one timer; the key makes a repeated click harmless. */
@Repository
public class FinalAdjustmentRepository {

  private final JdbcTemplate db;

  public FinalAdjustmentRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** False when this key was already recorded (the addition was applied once already). */
  public boolean record(UUID idempotencyKey, int deltaSeconds, long createdBy, Instant at) {
    return db.update(
      """
      INSERT INTO final_time_adjustment(idempotency_key, delta_seconds, created_by, created_at) VALUES (?, ?, ?, ?)
      ON CONFLICT (idempotency_key) DO NOTHING""",
      idempotencyKey,
      deltaSeconds,
      createdBy,
      timestamp(at)
    ) == 1;
  }
}
