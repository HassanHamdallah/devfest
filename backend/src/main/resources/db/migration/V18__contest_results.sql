-- Problem Solving room (rules §20): the contest runs on Codeforces; the site only holds the link, the organizer's typed
-- results and the clue release. Scores and totals are never stored: every read computes them from these rows.
-- Nothing here is tied to the room code: any room with the CONTEST_RESULTS activity uses it.

-- The third room activity (one per room). Default for a fresh setup only; the admin changes it per room afterwards.
ALTER TABLE event_room DROP CONSTRAINT event_room_activity_check;
ALTER TABLE event_room ADD CONSTRAINT event_room_activity_check CHECK(activity IN ('AI_QUIZ','LINUX_TERMINAL','CONTEST_RESULTS'));
UPDATE event_room SET activity = 'CONTEST_RESULTS' WHERE code = 'PROBLEM_SOLVING' AND activity IS NULL;

-- The contest link of a room: one https URL, set by the room's manager or the admin (content: read-only once the
-- final opens). No row means "not set". Room content, not team progress: resets keep it.
CREATE TABLE contest_room_link (
 room_id BIGINT PRIMARY KEY REFERENCES event_room(id),
 contest_url VARCHAR(500) NOT NULL CHECK(contest_url ~ '^https://[^[:space:]]+$' AND octet_length(contest_url) <= 500),
 updated_by BIGINT NOT NULL REFERENCES app_user(id),
 updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX contest_room_link_updated_by ON contest_room_link(updated_by);

-- The results the organizer typed from the Codeforces standings: which of the problems A to E the team solved and its
-- penalty in whole minutes. One row per team and room, edited in place (the audit log keeps who changed what).
-- Team progress: resets delete it.
CREATE TABLE contest_result (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 solved_a BOOLEAN NOT NULL,
 solved_b BOOLEAN NOT NULL,
 solved_c BOOLEAN NOT NULL,
 solved_d BOOLEAN NOT NULL,
 solved_e BOOLEAN NOT NULL,
 penalty_minutes INT NOT NULL CHECK(penalty_minutes BETWEEN 0 AND 100000),
 saved_by BIGINT NOT NULL REFERENCES app_user(id),
 saved_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id, room_id)
);
CREATE INDEX contest_result_room ON contest_result(room_id);
CREATE INDEX contest_result_saved_by ON contest_result(saved_by);

-- One release per team and room, ever: the primary key makes a second release impossible, however two requests race.
-- submission_id is the room submission it recorded (CORRECT, which collected the room's clue); round is the round in
-- which the team's group visits the room. Team progress: resets delete it.
CREATE TABLE contest_release (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 submission_id UUID NOT NULL UNIQUE REFERENCES room_submission(id),
 released_by BIGINT NOT NULL REFERENCES app_user(id),
 released_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id, room_id)
);
CREATE INDEX contest_release_room ON contest_release(room_id);
CREATE INDEX contest_release_released_by ON contest_release(released_by);
CREATE TRIGGER immutable_contest_release BEFORE UPDATE OR DELETE ON contest_release
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();
