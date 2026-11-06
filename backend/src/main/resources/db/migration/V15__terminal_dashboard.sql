-- Linux room, part C (rules §18.8): the organizer's answer keys and score overrides for a LINUX_TERMINAL room.
-- Scores themselves are never stored: they are computed on every read from the form, these keys and these overrides.

-- One row per room. path, username, file_size and pattern accept several values, one per line; message_key has one.
-- An empty key can never be matched. The final clue is checked against the room's ordinary clue key.
CREATE TABLE terminal_answer_key (
 room_id BIGINT PRIMARY KEY REFERENCES event_room(id),
 path_keys TEXT NOT NULL DEFAULT '' CHECK(octet_length(path_keys) <= 10500),
 username_keys TEXT NOT NULL DEFAULT '' CHECK(octet_length(username_keys) <= 10500),
 file_size_keys TEXT NOT NULL DEFAULT '' CHECK(octet_length(file_size_keys) <= 10500),
 pattern_keys TEXT NOT NULL DEFAULT '' CHECK(octet_length(pattern_keys) <= 10500),
 message_key TEXT NOT NULL DEFAULT '' CHECK(octet_length(message_key) <= 1000),
 updated_by BIGINT NOT NULL REFERENCES app_user(id),
 updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX terminal_answer_key_updated_by ON terminal_answer_key(updated_by);

-- An organizer's award or denial of one scored item for one team; the row's absence means "automatic". Clearing an
-- override deletes the row (the audit log keeps who set and cleared it). Team progress: resets delete it.
CREATE TABLE terminal_form_override (
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 item VARCHAR(12) NOT NULL CHECK(item IN ('PATH','USERNAME','FILE_SIZE','PATTERN','MESSAGE_CLUE')),
 awarded BOOLEAN NOT NULL,
 set_by BIGINT NOT NULL REFERENCES app_user(id),
 set_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id, room_id, item)
);
CREATE INDEX terminal_form_override_room ON terminal_form_override(room_id);
CREATE INDEX terminal_form_override_set_by ON terminal_form_override(set_by);
