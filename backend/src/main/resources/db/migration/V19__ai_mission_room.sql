-- AI Mission Room: SENTINEL (rules §19) replaces the AI quiz: no questions, repairs, sections, cutoff or shuffling.
-- The room's content is one validated JSON document; a team works through ten stages, runs a few experiments in
-- three of them, submits each stage once, and is evaluated once against the room's pass score.

-- The quiz content and answers (V11) are gone. quiz_evaluation (V12) stays: one row per team and room, now named for
-- what it is. Its old LAST_REPAIR rows stay valid; new rows use LAST_STAGE or TIME_UP.
DROP TABLE quiz_mcq_answer, quiz_repair_answer, quiz_team_progress, quiz_snippet, quiz_repair, quiz_option, quiz_question;
ALTER TABLE event_room DROP COLUMN quiz_mcq_minutes;

ALTER TABLE quiz_evaluation RENAME TO sentinel_evaluation;
ALTER INDEX quiz_evaluation_room RENAME TO sentinel_evaluation_room;
ALTER TRIGGER immutable_quiz_evaluation ON sentinel_evaluation RENAME TO immutable_sentinel_evaluation;
ALTER TABLE sentinel_evaluation DROP CONSTRAINT quiz_evaluation_cause_check;
ALTER TABLE sentinel_evaluation
  ADD CONSTRAINT sentinel_evaluation_cause_check CHECK(cause IN ('LAST_REPAIR','LAST_STAGE','TIME_UP'));

-- The room's mission, as authored: one document per room, replaced as a whole by an import. The document holds the
-- answers' source data (the server derives answers from it), so only organizers ever read it.
CREATE TABLE sentinel_content (
 room_id BIGINT PRIMARY KEY REFERENCES event_room(id),
 document JSONB NOT NULL,
 updated_by BIGINT NOT NULL REFERENCES app_user(id),
 updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sentinel_content_updated_by ON sentinel_content(updated_by);

-- One experiment per team, stage and configuration: a repeat of a configuration is free and stores nothing new.
-- seq orders the team's runs. The result is what the server showed the team for that run.
CREATE TABLE sentinel_run (
 seq BIGSERIAL NOT NULL UNIQUE,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 stage INT NOT NULL CHECK(stage BETWEEN 1 AND 10),
 config VARCHAR(40) NOT NULL CHECK(octet_length(config) BETWEEN 1 AND 40),
 result JSONB NOT NULL,
 ran_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(team_id, stage, config),
 UNIQUE(team_id, idempotency_key)
);
CREATE INDEX sentinel_run_room ON sentinel_run(room_id);
CREATE TRIGGER immutable_sentinel_run BEFORE UPDATE OR DELETE ON sentinel_run
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

-- One submission per team and stage, ever: the primary key is the lock. points_earned is fixed at submit time (the
-- content cannot change once any team has worked in the room).
CREATE TABLE sentinel_submission (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 stage INT NOT NULL CHECK(stage BETWEEN 1 AND 10),
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 answer JSONB NOT NULL,
 points_earned NUMERIC(5,2) NOT NULL CHECK(points_earned >= 0),
 submitted_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(team_id, stage),
 UNIQUE(team_id, idempotency_key)
);
CREATE INDEX sentinel_submission_room ON sentinel_submission(room_id);
CREATE TRIGGER immutable_sentinel_submission BEFORE UPDATE OR DELETE ON sentinel_submission
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();
