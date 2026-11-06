-- AI room activity (rules §19): auto-scored multiple-choice questions, code-snippet ordering repairs with partial
-- credit, and a team-specific Vigenère key whose letters the repairs reveal. Content is data the room's manager
-- authors; no code path tests the room code.

-- The room's activity replaces the technical-output field on the team page. Only AI_QUIZ exists today.
ALTER TABLE event_room
  ADD COLUMN activity VARCHAR(20) NULL CHECK(activity IN ('AI_QUIZ')),
  ADD COLUMN quiz_mcq_minutes INT NOT NULL DEFAULT 14 CHECK(quiz_mcq_minutes BETWEEN 1 AND 180);
-- Default for a fresh setup only; the admin toggles it per room afterwards.
UPDATE event_room SET activity = 'AI_QUIZ' WHERE code = 'AI';

-- Questions keep their authoring order (position). Options and snippets are inserted in a random order, so their
-- ids reveal neither the correct option nor the correct snippet order.
CREATE TABLE quiz_question (
 id BIGSERIAL PRIMARY KEY,
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 section VARCHAR(10) NOT NULL CHECK(section IN ('EASY','MEDIUM','ADVANCED','LANDMARK','RECENT')),
 position INT NOT NULL CHECK(position >= 0),
 prompt TEXT NOT NULL CHECK(octet_length(prompt) BETWEEN 1 AND 2000),
 points INT NOT NULL CHECK(points BETWEEN 1 AND 10),
 explanation TEXT NOT NULL DEFAULT '' CHECK(octet_length(explanation) <= 2000),
 updated_by BIGINT NOT NULL REFERENCES app_user(id),
 updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX quiz_question_room ON quiz_question(room_id, position);
CREATE INDEX quiz_question_updated_by ON quiz_question(updated_by);

CREATE TABLE quiz_option (
 id BIGSERIAL PRIMARY KEY,
 question_id BIGINT NOT NULL REFERENCES quiz_question(id) ON DELETE CASCADE,
 position INT NOT NULL CHECK(position BETWEEN 0 AND 5),
 text VARCHAR(500) NOT NULL CHECK(octet_length(text) BETWEEN 1 AND 500),
 correct BOOLEAN NOT NULL,
 UNIQUE(question_id, position)
);
-- Exactly one correct option: at most one here, at least one checked by the service.
CREATE UNIQUE INDEX quiz_option_one_correct ON quiz_option(question_id) WHERE correct;

CREATE TABLE quiz_repair (
 id BIGSERIAL PRIMARY KEY,
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 position INT NOT NULL CHECK(position >= 0),
 title VARCHAR(120) NOT NULL CHECK(octet_length(title) BETWEEN 1 AND 120),
 instructions TEXT NOT NULL DEFAULT '' CHECK(octet_length(instructions) <= 2000),
 points INT NOT NULL DEFAULT 10 CHECK(points BETWEEN 1 AND 100),
 updated_by BIGINT NOT NULL REFERENCES app_user(id),
 updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX quiz_repair_room ON quiz_repair(room_id, position);
CREATE INDEX quiz_repair_updated_by ON quiz_repair(updated_by);

-- correct_position: 0-based place of the snippet in the repaired code.
CREATE TABLE quiz_snippet (
 id BIGSERIAL PRIMARY KEY,
 repair_id BIGINT NOT NULL REFERENCES quiz_repair(id) ON DELETE CASCADE,
 correct_position INT NOT NULL CHECK(correct_position BETWEEN 0 AND 5),
 code TEXT NOT NULL CHECK(octet_length(code) BETWEEN 1 AND 2000),
 UNIQUE(repair_id, correct_position)
);

-- Per team and room: the team's cipher key (created once, at its first activity action) and "Finish MCQs".
CREATE TABLE quiz_team_progress (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 cipher_key CHAR(3) NOT NULL CHECK(cipher_key ~ '^[A-Z]{3}$'),
 created_at TIMESTAMPTZ NOT NULL,
 mcq_finished_at TIMESTAMPTZ NULL,
 PRIMARY KEY(team_id, room_id)
);
CREATE INDEX quiz_team_progress_room ON quiz_team_progress(room_id);

-- One answer per team and question; the score is fixed at answer time (content locks once answered).
CREATE TABLE quiz_mcq_answer (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 question_id BIGINT NOT NULL REFERENCES quiz_question(id),
 option_id BIGINT NOT NULL REFERENCES quiz_option(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 points_earned NUMERIC(6,2) NOT NULL CHECK(points_earned >= 0),
 answered_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(team_id, question_id),
 UNIQUE(team_id, idempotency_key)
);
CREATE INDEX quiz_mcq_answer_question ON quiz_mcq_answer(question_id);
CREATE INDEX quiz_mcq_answer_option ON quiz_mcq_answer(option_id);
CREATE INDEX quiz_mcq_answer_room ON quiz_mcq_answer(room_id);
CREATE TRIGGER immutable_quiz_mcq_answer BEFORE UPDATE OR DELETE ON quiz_mcq_answer
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

-- snippet_order: the snippet ids in the order the team submitted them.
CREATE TABLE quiz_repair_answer (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 repair_id BIGINT NOT NULL REFERENCES quiz_repair(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 round INT NOT NULL CHECK(round BETWEEN 1 AND 4),
 snippet_order BIGINT[] NOT NULL,
 correct_positions INT NOT NULL CHECK(correct_positions >= 0),
 snippet_count INT NOT NULL CHECK(snippet_count BETWEEN 1 AND 6 AND correct_positions <= snippet_count),
 points_earned NUMERIC(6,2) NOT NULL CHECK(points_earned >= 0),
 submitted_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(team_id, repair_id),
 UNIQUE(team_id, idempotency_key)
);
CREATE INDEX quiz_repair_answer_repair ON quiz_repair_answer(repair_id);
CREATE INDEX quiz_repair_answer_room ON quiz_repair_answer(room_id);
CREATE TRIGGER immutable_quiz_repair_answer BEFORE UPDATE OR DELETE ON quiz_repair_answer
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();
