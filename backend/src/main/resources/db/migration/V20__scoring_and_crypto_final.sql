-- The 100-point event scoring (four rooms of 15 plus a 40-point final) and the crypto final.
--
-- Retired here: the minute-based penalty system (HINT, MINOR_VIOLATION, PROHIBITED_TOOL, CHEATING, ANSWER_SHARING,
-- RECOVERY, WRONG_ACCUSATION, TIME_ADJUSTMENT), adjusted time and the penalty tie-break; the per-room recovery
-- challenges; the encrypted-message / accusation final and its suspects table. Past audit entries (audit_event) are
-- kept untouched; snapshots keep their JSON.

DROP TABLE team_final, accusation, recovery_attempt;
DROP TABLE event_content;
DROP TABLE suspect;
DROP TABLE penalty;
DROP FUNCTION guard_penalty_change();
ALTER TABLE event_room DROP COLUMN recovery_prompt, DROP COLUMN recovery_answer;

-- ---- score deductions: hints (-2) and cheating (-7.5), points only, append-only log ------------------------------
CREATE TABLE score_deduction (
 id BIGSERIAL PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 kind VARCHAR(10) NOT NULL CHECK(kind IN ('HINT','CHEATING')),
 reason VARCHAR(500) NOT NULL DEFAULT '' CHECK(octet_length(reason) <= 500),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL,
 cancelled_by BIGINT REFERENCES app_user(id),
 cancelled_at TIMESTAMPTZ,
 cancel_reason VARCHAR(500) CHECK(octet_length(cancel_reason) BETWEEN 3 AND 500),
 CHECK(kind = 'HINT' OR octet_length(reason) >= 3),
 CHECK((cancelled_by IS NULL) = (cancelled_at IS NULL) AND (cancelled_at IS NULL) = (cancel_reason IS NULL))
);
CREATE UNIQUE INDEX one_active_cheating ON score_deduction(team_id, room_id) WHERE kind = 'CHEATING' AND cancelled_at IS NULL;
CREATE INDEX score_deduction_team ON score_deduction(team_id);
CREATE INDEX score_deduction_room ON score_deduction(room_id);
CREATE INDEX score_deduction_created_by ON score_deduction(created_by);
CREATE INDEX score_deduction_cancelled_by ON score_deduction(cancelled_by);
-- Rows are never deleted (outside a reset transaction); the only permitted change is one cancellation.
CREATE FUNCTION guard_deduction_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN
  IF current_setting('devfest.allow_reset', true) = 'on' THEN
   RETURN OLD;
  END IF;
  RAISE EXCEPTION 'Deductions can only be cancelled' USING ERRCODE='23514';
 END IF;
 IF OLD.cancelled_at IS NOT NULL OR NEW.cancelled_at IS NULL
  OR (NEW.team_id,NEW.room_id,NEW.kind,NEW.reason,NEW.created_by,NEW.created_at)
   IS DISTINCT FROM (OLD.team_id,OLD.room_id,OLD.kind,OLD.reason,OLD.created_by,OLD.created_at)
 THEN
  RAISE EXCEPTION 'Deductions can only be cancelled once' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_score_deduction BEFORE UPDATE OR DELETE ON score_deduction
 FOR EACH ROW EXECUTE FUNCTION guard_deduction_change();

-- ---- a manager-entered room score (0..15) for a room without an automatic score --------------------------------
CREATE TABLE room_manual_score (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 score NUMERIC(4,2) NOT NULL CHECK(score BETWEEN 0 AND 15),
 entered_by BIGINT NOT NULL REFERENCES app_user(id),
 entered_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id, room_id)
);
CREATE INDEX room_manual_score_room ON room_manual_score(room_id);
CREATE INDEX room_manual_score_entered_by ON room_manual_score(entered_by);

-- ---- the final: validated content (read-only once the final opens) ----------------------------------------------
CREATE TABLE final_content (
 id INT PRIMARY KEY CHECK(id = 1),
 content JSONB NOT NULL,
 full_until_minutes INT NOT NULL CHECK(full_until_minutes BETWEEN 1 AND 40),
 imported_by BIGINT NOT NULL REFERENCES app_user(id),
 imported_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX final_content_imported_by ON final_content(imported_by);

-- ---- the final's one server-side timer (the same RoundTimer arithmetic as the rounds) ----------------------------
ALTER TABLE event_state
 ADD COLUMN final_phase VARCHAR(10) CHECK(final_phase IN ('RUNNING','PAUSED')),
 ADD COLUMN final_duration_seconds INT CHECK(final_duration_seconds > 0),
 ADD COLUMN final_elapsed_before_seconds INT NOT NULL DEFAULT 0 CHECK(final_elapsed_before_seconds >= 0),
 ADD COLUMN final_resumed_at TIMESTAMPTZ,
 ADD COLUMN final_ended BOOLEAN NOT NULL DEFAULT FALSE;

-- Added time is not idempotent, so every addition is keyed (like round_time_adjustment).
CREATE TABLE final_time_adjustment (
 idempotency_key UUID PRIMARY KEY,
 delta_seconds INT NOT NULL CHECK(delta_seconds <> 0),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX final_time_adjustment_created_by ON final_time_adjustment(created_by);
CREATE TRIGGER immutable_final_time_adjustment BEFORE UPDATE OR DELETE ON final_time_adjustment
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

-- ---- a team's progress in the final -------------------------------------------------------------------------------
CREATE TABLE team_final (
 team_id BIGINT PRIMARY KEY REFERENCES event_team(id),
 unlocked_at TIMESTAMPTZ,
 case_started_at TIMESTAMPTZ,
 suspect_id INT CHECK(suspect_id BETWEEN 1 AND 6),
 suspect_correct BOOLEAN,
 submitted_at TIMESTAMPTZ,
 active_seconds INT CHECK(active_seconds >= 0),
 finalised_at TIMESTAMPTZ,
 finalised_cause VARCHAR(10) CHECK(finalised_cause IN ('SUBMITTED','TIME_UP','CLOSED')),
 CHECK((finalised_at IS NULL) = (finalised_cause IS NULL)),
 CHECK((suspect_id IS NULL) = (suspect_correct IS NULL) AND (suspect_id IS NULL) = (submitted_at IS NULL)
  AND (suspect_id IS NULL) = (active_seconds IS NULL)),
 CHECK(submitted_at IS NULL OR finalised_cause = 'SUBMITTED')
);

CREATE TABLE final_unlock_attempt (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 codes TEXT NOT NULL CHECK(octet_length(codes) <= 2500),
 correct BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id, idempotency_key)
);
CREATE TRIGGER immutable_final_unlock_attempt BEFORE UPDATE OR DELETE ON final_unlock_attempt
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

CREATE TABLE final_layer_attempt (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 layer INT NOT NULL CHECK(layer BETWEEN 1 AND 4),
 value TEXT NOT NULL CHECK(octet_length(value) BETWEEN 1 AND 20),
 correct BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id, idempotency_key)
);
CREATE UNIQUE INDEX one_correct_layer ON final_layer_attempt(team_id, layer) WHERE correct;
CREATE INDEX final_layer_attempt_team_layer ON final_layer_attempt(team_id, layer, received_at);
CREATE TRIGGER immutable_final_layer_attempt BEFORE UPDATE OR DELETE ON final_layer_attempt
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

-- One verdict per team and signed report (layer 3 = E03, layer 4 = E04), never changed.
CREATE TABLE final_verdict (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 layer INT NOT NULL CHECK(layer IN (3, 4)),
 verdict VARCHAR(3) NOT NULL CHECK(verdict IN ('YES','NO')),
 correct BOOLEAN NOT NULL,
 submitted_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id, layer)
);
CREATE TRIGGER immutable_final_verdict BEFORE UPDATE OR DELETE ON final_verdict
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

-- The one Recovery Challenge: unlimited attempts, no penalty. room_id is the room whose clue a correct answer fills.
CREATE TABLE recovery_attempt (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 answer TEXT NOT NULL CHECK(octet_length(answer) BETWEEN 1 AND 500),
 correct BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id, idempotency_key)
);
CREATE INDEX recovery_attempt_room ON recovery_attempt(room_id);
CREATE TRIGGER immutable_recovery_attempt BEFORE UPDATE OR DELETE ON recovery_attempt
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();
