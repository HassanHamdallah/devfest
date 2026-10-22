package dev.devfest.investigation;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observability only: admission deadlines are always checked synchronously in the submission transaction. */
@Service
public class InvestigationLifecycle {

  private final JdbcTemplate db;
  private final InvestigationChallenges challenges;
  private final Clock clock;

  public InvestigationLifecycle(
    JdbcTemplate db,
    InvestigationChallenges challenges,
    Clock clock
  ) {
    this.db = db;
    this.challenges = challenges;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 15000, initialDelay = 15000)
  @Transactional
  public void observe() {
    challenges.lockSettings();
    var now = Timestamp.from(clock.instant());
    db.update(
      """
      WITH states AS (
        SELECT id, CASE WHEN state='DRAFT' THEN 'DRAFT'
          WHEN state='CLOSED' OR closes_at<=? THEN 'CLOSED'
          WHEN opens_at>? THEN 'SCHEDULED' ELSE 'OPEN' END availability
        FROM investigation_challenge
      ), changed AS (
        INSERT INTO investigation_lifecycle(challenge_id,availability,observed_at)
        SELECT id,availability,? FROM states
        ON CONFLICT(challenge_id) DO UPDATE SET availability=excluded.availability,observed_at=excluded.observed_at
        WHERE investigation_lifecycle.availability<>excluded.availability
        RETURNING challenge_id,availability,observed_at
      ) INSERT INTO audit_event(action,target,created_at)
        SELECT 'INV_LIFECYCLE_'||availability,challenge_id::text,observed_at FROM changed
      """,
      now,
      now,
      now
    );
  }
}
