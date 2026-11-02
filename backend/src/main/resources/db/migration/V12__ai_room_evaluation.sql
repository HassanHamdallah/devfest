-- AI room rework (rules §19): the team cipher of V11 is removed. Each team is evaluated once against the room's pass
-- score; the result is recorded as an ordinary room submission (CORRECT collects the room's clue, INCORRECT does not).

ALTER TABLE quiz_team_progress DROP COLUMN cipher_key;

-- Points out of the quiz total (100 when the plan is followed). Locked once the room's first team is evaluated.
ALTER TABLE event_room
  ADD COLUMN quiz_pass_score INT NOT NULL DEFAULT 60 CHECK(quiz_pass_score BETWEEN 0 AND 100);

-- One evaluation per team and room, ever: the primary key makes a second evaluation impossible, whichever trigger
-- (the team's last repair or the round's Time's up) comes second. submission_id is the room submission it recorded.
CREATE TABLE quiz_evaluation (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 submission_id UUID NOT NULL UNIQUE REFERENCES room_submission(id),
 total NUMERIC(6,2) NOT NULL CHECK(total >= 0),
 pass_score INT NOT NULL CHECK(pass_score BETWEEN 0 AND 100),
 passed BOOLEAN NOT NULL CHECK(passed = (total >= pass_score)),
 cause VARCHAR(12) NOT NULL CHECK(cause IN ('LAST_REPAIR','TIME_UP')),
 evaluated_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id, room_id)
);
CREATE INDEX quiz_evaluation_room ON quiz_evaluation(room_id);
CREATE TRIGGER immutable_quiz_evaluation BEFORE UPDATE OR DELETE ON quiz_evaluation
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();
