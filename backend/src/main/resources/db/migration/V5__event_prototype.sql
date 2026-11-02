-- DevFest investigation event. Replaces the generic investigation module (V2-V4); only
-- local development/test data ever existed in those tables.
DROP TABLE IF EXISTS investigation_score_history, investigation_score, investigation_submission,
 investigation_draft, investigation_lifecycle, investigation_challenge, investigation_settings,
 team, room_manager, room CASCADE;
DROP FUNCTION IF EXISTS validate_investigation_assignment(), validate_investigation_score(),
 preserve_investigation_submission();

CREATE TABLE event_state (
 id INT PRIMARY KEY CHECK(id=1),
 current_round INT NOT NULL DEFAULT 0 CHECK(current_round BETWEEN 0 AND 5),
 round_started_at TIMESTAMPTZ,
 round_minutes INT NOT NULL DEFAULT 25 CHECK(round_minutes BETWEEN 1 AND 180),
 final_open BOOLEAN NOT NULL DEFAULT FALSE,
 final_started_at TIMESTAMPTZ,
 final_closed_at TIMESTAMPTZ,
 results_published BOOLEAN NOT NULL DEFAULT FALSE,
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(NOT final_open OR final_started_at IS NOT NULL)
);
INSERT INTO event_state(id) VALUES(1);

CREATE TABLE event_room (
 id BIGSERIAL PRIMARY KEY,
 code VARCHAR(20) NOT NULL UNIQUE CHECK(code IN ('AI','PROBLEM_SOLVING','LINUX','HARDWARE')),
 name VARCHAR(80) NOT NULL,
 position INT NOT NULL UNIQUE CHECK(position BETWEEN 0 AND 3),
 manager_id BIGINT UNIQUE REFERENCES app_user(id),
 is_open BOOLEAN NOT NULL DEFAULT FALSE,
 clue_key VARCHAR(500) NOT NULL DEFAULT '' CHECK(octet_length(clue_key)<=500),
 recovery_prompt TEXT NOT NULL DEFAULT '' CHECK(octet_length(recovery_prompt)<=4000),
 recovery_answer VARCHAR(500) NOT NULL DEFAULT '' CHECK(octet_length(recovery_answer)<=500),
 version BIGINT NOT NULL DEFAULT 1 CHECK(version>0)
);
INSERT INTO event_room(code,name,position) VALUES
 ('AI','AI',0),('PROBLEM_SOLVING','Problem solving',1),('LINUX','Linux',2),('HARDWARE','Hardware',3);

CREATE TABLE event_team (
 id BIGSERIAL PRIMARY KEY,
 name VARCHAR(80) NOT NULL UNIQUE,
 group_code CHAR(1) NOT NULL CHECK(group_code IN ('A','B','C','D')),
 account_id BIGINT NOT NULL UNIQUE REFERENCES app_user(id),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX event_team_group ON event_team(group_code) WHERE active;

-- Accepted team records are append-only; corrections happen through new rows.
CREATE FUNCTION reject_event_record_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME USING ERRCODE='23514';
END $$;

CREATE TABLE room_submission (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 technical_output TEXT NOT NULL CHECK(octet_length(technical_output) BETWEEN 1 AND 4000),
 clue_answer TEXT NOT NULL CHECK(octet_length(clue_answer) BETWEEN 1 AND 500),
 clue_correct BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id,idempotency_key)
);
CREATE INDEX room_submission_room_time ON room_submission(room_id,received_at DESC);
CREATE INDEX room_submission_team_room_time ON room_submission(team_id,room_id,received_at DESC);
CREATE TRIGGER immutable_room_submission BEFORE UPDATE OR DELETE ON room_submission
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

CREATE TABLE room_judgement (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 status VARCHAR(10) NOT NULL CHECK(status IN ('PENDING','PASS','FAIL')),
 hints INT NOT NULL DEFAULT 0 CHECK(hints BETWEEN 0 AND 50),
 note VARCHAR(1000) NOT NULL DEFAULT '' CHECK(octet_length(note)<=1000),
 reviewer_id BIGINT NOT NULL REFERENCES app_user(id),
 updated_at TIMESTAMPTZ NOT NULL,
 version BIGINT NOT NULL CHECK(version>0),
 PRIMARY KEY(team_id,room_id)
);
CREATE INDEX room_judgement_room ON room_judgement(room_id);
CREATE INDEX room_judgement_reviewer ON room_judgement(reviewer_id);

CREATE TABLE room_judgement_history (
 id BIGSERIAL PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 old_status VARCHAR(10) CHECK(old_status IN ('PENDING','PASS','FAIL')),
 new_status VARCHAR(10) NOT NULL CHECK(new_status IN ('PENDING','PASS','FAIL')),
 hints INT NOT NULL CHECK(hints BETWEEN 0 AND 50),
 note VARCHAR(1000) NOT NULL CHECK(octet_length(note)<=1000),
 reviewer_id BIGINT NOT NULL REFERENCES app_user(id),
 changed_at TIMESTAMPTZ NOT NULL,
 version BIGINT NOT NULL CHECK(version>0),
 UNIQUE(team_id,room_id,version)
);
CREATE INDEX room_judgement_history_room ON room_judgement_history(room_id);
CREATE INDEX room_judgement_history_reviewer ON room_judgement_history(reviewer_id);
CREATE TRIGGER immutable_room_judgement_history BEFORE UPDATE OR DELETE ON room_judgement_history
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

CREATE TABLE collected_clue (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 source VARCHAR(10) NOT NULL CHECK(source IN ('ROOM','RECOVERY')),
 submission_id UUID REFERENCES room_submission(id),
 collected_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id,room_id),
 CHECK((source='ROOM')=(submission_id IS NOT NULL))
);
CREATE INDEX collected_clue_room ON collected_clue(room_id);
CREATE INDEX collected_clue_submission ON collected_clue(submission_id);

CREATE TABLE penalty (
 id BIGSERIAL PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT REFERENCES event_room(id),
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('HINT','MINOR_VIOLATION','PROHIBITED_TOOL','CHEATING',
  'ANSWER_SHARING','RECOVERY','WRONG_ACCUSATION')),
 minutes INT NOT NULL CHECK(minutes>0),
 note VARCHAR(500) NOT NULL DEFAULT '' CHECK(octet_length(note)<=500),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL,
 voided_by BIGINT REFERENCES app_user(id),
 voided_at TIMESTAMPTZ,
 void_reason VARCHAR(500) CHECK(octet_length(void_reason)<=500),
 CHECK((voided_by IS NULL)=(voided_at IS NULL) AND (voided_at IS NULL)=(void_reason IS NULL))
);
CREATE UNIQUE INDEX one_recovery_penalty ON penalty(team_id) WHERE kind='RECOVERY';
CREATE INDEX penalty_team ON penalty(team_id);
CREATE INDEX penalty_room ON penalty(room_id);
CREATE INDEX penalty_created_by ON penalty(created_by);
CREATE INDEX penalty_voided_by ON penalty(voided_by);
-- Penalties are never deleted; the only permitted change is a single void.
CREATE FUNCTION guard_penalty_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.voided_at IS NOT NULL OR NEW.voided_at IS NULL
  OR (NEW.team_id,NEW.room_id,NEW.kind,NEW.minutes,NEW.note,NEW.created_by,NEW.created_at)
   IS DISTINCT FROM (OLD.team_id,OLD.room_id,OLD.kind,OLD.minutes,OLD.note,OLD.created_by,OLD.created_at)
 THEN
  RAISE EXCEPTION 'Penalties can only be voided once' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_penalty BEFORE UPDATE OR DELETE ON penalty
 FOR EACH ROW EXECUTE FUNCTION guard_penalty_change();

CREATE TABLE suspect (
 id BIGSERIAL PRIMARY KEY,
 name VARCHAR(80) NOT NULL UNIQUE,
 description VARCHAR(300) NOT NULL DEFAULT '' CHECK(octet_length(description)<=300),
 position INT NOT NULL CHECK(position BETWEEN 0 AND 5)
);

CREATE TABLE event_content (
 id INT PRIMARY KEY CHECK(id=1),
 encrypted_message TEXT NOT NULL DEFAULT '' CHECK(octet_length(encrypted_message)<=4000),
 correct_suspect_id BIGINT REFERENCES suspect(id),
 version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO event_content(id) VALUES(1);
CREATE INDEX event_content_suspect ON event_content(correct_suspect_id);

CREATE TABLE recovery_attempt (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 answer TEXT NOT NULL CHECK(octet_length(answer) BETWEEN 1 AND 500),
 correct BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id,idempotency_key)
);
CREATE INDEX recovery_attempt_room ON recovery_attempt(room_id);
CREATE TRIGGER immutable_recovery_attempt BEFORE UPDATE OR DELETE ON recovery_attempt
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

CREATE TABLE accusation (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 suspect_id BIGINT NOT NULL REFERENCES suspect(id),
 decrypted_message TEXT NOT NULL CHECK(octet_length(decrypted_message) BETWEEN 1 AND 4000),
 evidence TEXT NOT NULL CHECK(octet_length(evidence) BETWEEN 1 AND 4000),
 correct BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id,idempotency_key)
);
CREATE UNIQUE INDEX one_correct_accusation ON accusation(team_id) WHERE correct;
CREATE INDEX accusation_suspect ON accusation(suspect_id);
CREATE TRIGGER immutable_accusation BEFORE UPDATE OR DELETE ON accusation
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

CREATE TABLE team_final (
 team_id BIGINT PRIMARY KEY REFERENCES event_team(id),
 finished_at TIMESTAMPTZ NOT NULL,
 finishing_accusation_id UUID NOT NULL UNIQUE REFERENCES accusation(id)
);
