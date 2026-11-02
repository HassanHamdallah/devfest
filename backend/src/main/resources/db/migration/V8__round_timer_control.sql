-- Manual round timer: a round begins ASSIGNED (teams go to their rooms), and the admin starts, pauses, resumes,
-- adjusts and ends the countdown. TIME_UP is derived from these fields and the server clock, never stored.

ALTER TABLE event_state
  ADD COLUMN round_phase VARCHAR(10) NULL CHECK(round_phase IN ('ASSIGNED','RUNNING','PAUSED')),
  ADD COLUMN round_duration_seconds INT NULL CHECK(round_duration_seconds > 0),
  ADD COLUMN round_elapsed_before_seconds INT NOT NULL DEFAULT 0 CHECK(round_elapsed_before_seconds >= 0),
  ADD COLUMN round_resumed_at TIMESTAMPTZ NULL,
  ADD COLUMN round_ended BOOLEAN NOT NULL DEFAULT FALSE;

-- A round that was running before V8 keeps running for its configured length from its start.
UPDATE event_state
SET round_phase = 'RUNNING',
    round_duration_seconds = round_minutes * 60,
    round_started_at = COALESCE(round_started_at, now()),
    round_resumed_at = COALESCE(round_started_at, now())
WHERE current_round BETWEEN 1 AND 4;
UPDATE event_state SET round_started_at = NULL WHERE current_round NOT BETWEEN 1 AND 4;

ALTER TABLE event_state
  ADD CONSTRAINT round_phase_only_in_running_rounds
    CHECK((current_round BETWEEN 1 AND 4) = (round_phase IS NOT NULL)),
  ADD CONSTRAINT round_duration_with_phase CHECK((round_phase IS NULL) = (round_duration_seconds IS NULL)),
  ADD CONSTRAINT round_running_has_resume_time CHECK(round_phase <> 'RUNNING' OR round_resumed_at IS NOT NULL),
  ADD CONSTRAINT round_assigned_not_started
    CHECK(round_phase <> 'ASSIGNED' OR (round_started_at IS NULL AND NOT round_ended AND round_elapsed_before_seconds = 0));

-- "All teams are here" per room and round. Rows of earlier rounds stay as history.
CREATE TABLE room_arrival (
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 confirmed_by BIGINT NOT NULL REFERENCES app_user(id),
 confirmed_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(round, room_id)
);

-- Applied time adjustments; the primary key makes a repeated Idempotency-Key within a round a no-op.
CREATE TABLE round_time_adjustment (
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 idempotency_key UUID NOT NULL,
 delta_seconds INT NOT NULL CHECK(delta_seconds <> 0 AND delta_seconds BETWEEN -3600 AND 3600),
 applied_by BIGINT NOT NULL REFERENCES app_user(id),
 applied_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(round, idempotency_key)
);
