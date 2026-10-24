-- Admin overrides: per-team room exceptions, manual clues, time adjustments, disqualification, audited reasons,
-- snapshots, and resets that may delete append-only rows only through an explicit, transaction-local flag.

-- One team sent to another room for one round (instead of its rotation room).
CREATE TABLE team_room_exception (
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 reason VARCHAR(500) NOT NULL CHECK(octet_length(reason) BETWEEN 3 AND 500),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(round, team_id)
);
CREATE INDEX team_room_exception_team ON team_room_exception(team_id);
CREATE INDEX team_room_exception_room ON team_room_exception(room_id);
CREATE INDEX team_room_exception_created_by ON team_room_exception(created_by);

-- A clue granted by the admin; like RECOVERY it has no submission.
ALTER TABLE collected_clue DROP CONSTRAINT collected_clue_source_check;
ALTER TABLE collected_clue ADD CONSTRAINT collected_clue_source_check CHECK(source IN ('ROOM','RECOVERY','MANUAL'));

-- Signed time adjustments (compensation) are penalty rows that never count as a penalty entry.
ALTER TABLE penalty DROP CONSTRAINT penalty_kind_check;
ALTER TABLE penalty ADD CONSTRAINT penalty_kind_check CHECK(kind IN ('HINT','MINOR_VIOLATION','PROHIBITED_TOOL',
 'CHEATING','ANSWER_SHARING','RECOVERY','WRONG_ACCUSATION','TIME_ADJUSTMENT'));
ALTER TABLE penalty DROP CONSTRAINT penalty_minutes_check;
ALTER TABLE penalty ADD CONSTRAINT penalty_minutes_check CHECK(
 CASE WHEN kind = 'TIME_ADJUSTMENT' THEN minutes BETWEEN -60 AND 60 AND minutes <> 0 ELSE minutes > 0 END);

ALTER TABLE event_team
  ADD COLUMN disqualified BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN disqualified_reason VARCHAR(500) NULL CHECK(octet_length(disqualified_reason) <= 500),
  ADD COLUMN disqualified_at TIMESTAMPTZ NULL,
  ADD COLUMN disqualified_by BIGINT NULL REFERENCES app_user(id),
  ADD CONSTRAINT event_team_disqualification CHECK(
   disqualified = (disqualified_at IS NOT NULL) AND disqualified = (disqualified_by IS NOT NULL)
   AND disqualified = (disqualified_reason IS NOT NULL));
CREATE INDEX event_team_disqualified_by ON event_team(disqualified_by);

-- "Final ever opened" is derived from event_state.final_started_at, which the final reset clears; no column needed.

-- Every override records why.
ALTER TABLE audit_event ADD COLUMN reason VARCHAR(500) NULL CHECK(octet_length(reason) <= 500);
CREATE INDEX audit_event_action_id ON audit_event(action, id);

-- Copies of the rows a reset deletes (and the event state before it); never deleted by the application.
CREATE TABLE event_snapshot (
 id BIGSERIAL PRIMARY KEY,
 created_at TIMESTAMPTZ NOT NULL,
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('RESET_FINAL','RESET_TEAM','RESET_EVENT','MANUAL')),
 reason VARCHAR(500) NOT NULL CHECK(octet_length(reason) BETWEEN 3 AND 500),
 team_id BIGINT NULL REFERENCES event_team(id),
 data JSONB NOT NULL
);
CREATE INDEX event_snapshot_created_by ON event_snapshot(created_by);
CREATE INDEX event_snapshot_team ON event_snapshot(team_id);

-- Append-only records: UPDATE is always refused; DELETE only inside a reset transaction that has run
-- SELECT set_config('devfest.allow_reset', 'on', true). No normal code path sets this flag.
CREATE OR REPLACE FUNCTION reject_event_record_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' AND current_setting('devfest.allow_reset', true) = 'on' THEN
  RETURN OLD;
 END IF;
 RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME USING ERRCODE='23514';
END $$;

-- Penalties: the only permitted change is a single void; deletes only inside a reset transaction.
CREATE OR REPLACE FUNCTION guard_penalty_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN
  IF current_setting('devfest.allow_reset', true) = 'on' THEN
   RETURN OLD;
  END IF;
  RAISE EXCEPTION 'Penalties can only be voided once' USING ERRCODE='23514';
 END IF;
 IF OLD.voided_at IS NOT NULL OR NEW.voided_at IS NULL
  OR (NEW.team_id,NEW.room_id,NEW.kind,NEW.minutes,NEW.note,NEW.created_by,NEW.created_at)
   IS DISTINCT FROM (OLD.team_id,OLD.room_id,OLD.kind,OLD.minutes,OLD.note,OLD.created_by,OLD.created_at)
 THEN
  RAISE EXCEPTION 'Penalties can only be voided once' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
