-- Linux room, part B (rules §18.7): the team's six-field form of a LINUX_TERMINAL room. Nothing here is tied to the
-- room code: any room with that activity uses it.

-- One row per team and room: the values of the last save, locked by the final submit (or by the round's end).
-- submission_id is the room submission the final answer was recorded as (through the ordinary room-submission
-- path), so the row says how the room finished for the team: cause SUBMIT (the team) or TIME_UP (the round's end).
CREATE TABLE terminal_form (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 path_value TEXT NOT NULL DEFAULT '' CHECK(octet_length(path_value) <= 500),
 username_value TEXT NOT NULL DEFAULT '' CHECK(octet_length(username_value) <= 500),
 file_size_value TEXT NOT NULL DEFAULT '' CHECK(octet_length(file_size_value) <= 500),
 pattern_value TEXT NOT NULL DEFAULT '' CHECK(octet_length(pattern_value) <= 500),
 message_value TEXT NOT NULL DEFAULT '' CHECK(octet_length(message_value) <= 500),
 clue_value TEXT NOT NULL DEFAULT '' CHECK(octet_length(clue_value) <= 500),
 saved_at TIMESTAMPTZ NOT NULL,
 submitted_at TIMESTAMPTZ NULL,
 submission_id UUID NULL UNIQUE REFERENCES room_submission(id),
 cause VARCHAR(8) NULL CHECK(cause IN ('SUBMIT','TIME_UP')),
 CHECK((submitted_at IS NULL) = (submission_id IS NULL) AND (submitted_at IS NULL) = (cause IS NULL)),
 PRIMARY KEY(team_id, room_id)
);
CREATE INDEX terminal_form_room ON terminal_form(room_id);

-- Every Save and Final submit request by its Idempotency-Key: a retry of the same request is answered again without
-- a second write, and a stale retry that arrives after a newer save never overwrites it. Append-only.
CREATE TABLE terminal_form_request (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 idempotency_key UUID NOT NULL,
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 kind VARCHAR(6) NOT NULL CHECK(kind IN ('SAVE','SUBMIT')),
 request_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id, idempotency_key)
);
CREATE INDEX terminal_form_request_room ON terminal_form_request(room_id);
