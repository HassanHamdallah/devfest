-- Clue verdicts that follow the room's clue key and the manager's override. room_submission.clue_correct stays
-- the immutable result at submission time; the current result lives here.

CREATE TABLE submission_clue_verdict (
 submission_id UUID PRIMARY KEY REFERENCES room_submission(id),
 auto_correct BOOLEAN NOT NULL,
 override VARCHAR(10) NULL CHECK(override IN ('ACCEPT','REJECT')),
 effective_correct BOOLEAN NOT NULL,
 checked_at TIMESTAMPTZ NOT NULL,
 override_by BIGINT NULL REFERENCES app_user(id),
 override_at TIMESTAMPTZ NULL,
 version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
 CHECK(effective_correct = CASE override WHEN 'ACCEPT' THEN TRUE WHEN 'REJECT' THEN FALSE ELSE auto_correct END),
 CHECK((override IS NULL) = (override_by IS NULL) AND (override IS NULL) = (override_at IS NULL))
);

CREATE TABLE submission_clue_verdict_history (
 id BIGSERIAL PRIMARY KEY,
 submission_id UUID NOT NULL REFERENCES submission_clue_verdict(submission_id),
 old_override VARCHAR(10) NULL CHECK(old_override IN ('ACCEPT','REJECT')),
 new_override VARCHAR(10) NULL CHECK(new_override IN ('ACCEPT','REJECT')),
 old_effective BOOLEAN NOT NULL,
 new_effective BOOLEAN NOT NULL,
 changed_by BIGINT NOT NULL REFERENCES app_user(id),
 changed_at TIMESTAMPTZ NOT NULL,
 version BIGINT NOT NULL CHECK(version>1),
 UNIQUE(submission_id, version)
);
CREATE TRIGGER immutable_clue_verdict_history BEFORE UPDATE OR DELETE ON submission_clue_verdict_history
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

INSERT INTO submission_clue_verdict(submission_id, auto_correct, effective_correct, checked_at)
SELECT id, clue_correct, clue_correct, received_at FROM room_submission;
