-- In-browser terminal: a per-room switch and the text files a room's terminal starts with.
-- The emulator runs in the team's browser; the server only stores and hands out these files.

ALTER TABLE event_room ADD COLUMN terminal_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- Default for a fresh setup only; the admin toggles it per room afterwards.
UPDATE event_room SET terminal_enabled = TRUE WHERE code = 'LINUX';

-- path: relative to the team's home directory, e.g. logs/lock.log (the service enforces the character set,
-- no "..", no leading "/"). The 5 MiB total per room is enforced by the service under the event_state lock.
CREATE TABLE room_terminal_file (
 id BIGSERIAL PRIMARY KEY,
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 path VARCHAR(120) NOT NULL CHECK(path ~ '^[A-Za-z0-9._-][A-Za-z0-9._/-]*$' AND char_length(path) <= 120),
 content TEXT NOT NULL CHECK(octet_length(content) <= 2097152),
 size_bytes INT NOT NULL CHECK(size_bytes = octet_length(content)),
 updated_by BIGINT NOT NULL REFERENCES app_user(id),
 updated_at TIMESTAMPTZ NOT NULL,
 UNIQUE(room_id, path)
);
CREATE INDEX room_terminal_file_updated_by ON room_terminal_file(updated_by);

-- Terminal file audits name the room, the path (up to 120 characters) and the size; never the content.
ALTER TABLE audit_event ALTER COLUMN target TYPE VARCHAR(200);
