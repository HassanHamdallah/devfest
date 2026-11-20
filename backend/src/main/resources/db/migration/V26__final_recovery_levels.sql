-- The final recovery system (rules sections 9 and 10): every team plays the final. K = the room keys a team holds when
-- the final opens: 4 keys need no recovery, 3 keys pass the ML level, 0 to 2 keys pass ML then ARDUINO. Passing the
-- last required level grants the team's missing room keys (collected_clue, source RECOVERY) and starts its own case
-- clock S (active final seconds at that moment).
--
-- Retired here: the single Recovery Challenge question. Nothing reads recovery_attempt any more, so it is dropped; past
-- audit entries and snapshots (event_snapshot JSON) are kept untouched. A stored content file with the old "recovery"
-- block no longer validates and is removed; the admin imports the file with "recoveryLevels".

DROP TABLE recovery_attempt;
DELETE FROM final_content;

-- ---- one row per team once the final opened: K, and when the recovery was finished ---------------------------------
-- keys_at_open is written once (when the final opens, or on the team's first recovery write if it was activated later)
-- and never changed; finished_* are set once by a guarded UPDATE (WHERE finished_at IS NULL). Final progress: the
-- final reset deletes it.
CREATE TABLE team_recovery (
 team_id BIGINT PRIMARY KEY REFERENCES event_team(id),
 keys_at_open INT NOT NULL CHECK(keys_at_open BETWEEN 0 AND 4),
 finished_at TIMESTAMPTZ,
 finished_active_seconds INT CHECK(finished_active_seconds >= 0),
 CHECK((finished_at IS NULL) = (finished_active_seconds IS NULL))
);

-- ---- every evaluated recovery check: append-only, one passed row per team, level and stage -------------------------
-- submission is the team's answer as sent (an ML stage's choice, or the Arduino lines and option); outcome is what the
-- team was told (message, Arduino test results, threshold metrics, token), so a replay answers the same. A check refused
-- before evaluation (wrong stage, too soon, malformed) is not stored.
CREATE TABLE recovery_check (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 level VARCHAR(8) NOT NULL CHECK(level IN ('ML','ARDUINO')),
 stage INT NOT NULL CHECK(stage BETWEEN 1 AND 6 AND (level = 'ML' OR stage = 1)),
 submission JSONB NOT NULL CHECK(octet_length(submission::text) <= 65536),
 passed BOOLEAN NOT NULL,
 outcome JSONB,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id, idempotency_key)
);
CREATE UNIQUE INDEX one_passed_recovery_stage ON recovery_check(team_id, level, stage) WHERE passed;
CREATE INDEX recovery_check_team_level ON recovery_check(team_id, level, received_at);
CREATE TRIGGER immutable_recovery_check BEFORE UPDATE OR DELETE ON recovery_check
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();
