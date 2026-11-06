-- Linux room, part A (rules §18): the room's server package and the second room activity.
-- No code path tests the room code: the package belongs to any room whose terminal is enabled.

-- The Linux terminal is a team activity too (its form and organizer dashboard follow); one activity per room.
ALTER TABLE event_room DROP CONSTRAINT event_room_activity_check;
ALTER TABLE event_room ADD CONSTRAINT event_room_activity_check CHECK(activity IN ('AI_QUIZ','LINUX_TERMINAL'));
-- Default for a fresh setup only; the admin changes it per room afterwards.
UPDATE event_room SET activity = 'LINUX_TERMINAL' WHERE code = 'LINUX' AND activity IS NULL;

-- One package per room, kept apart from the room rows so that no board or list query ever reads the blob.
-- archive: gzip(ustar) built by the server from the uploaded ZIP; sha256 is its digest (the HTTP ETag).
-- owners: the owner names the package introduces, comma separated; etc_passwd / etc_group: the lines the
-- terminal appends before the shell starts (uids and gids from 2000, by name).
CREATE TABLE room_terminal_bundle (
 room_id BIGINT PRIMARY KEY REFERENCES event_room(id),
 archive BYTEA NOT NULL,
 archive_bytes INT NOT NULL CHECK(archive_bytes BETWEEN 1 AND 31457280 AND archive_bytes = octet_length(archive)),
 sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 file_count INT NOT NULL CHECK(file_count BETWEEN 1 AND 5000),
 unpacked_bytes BIGINT NOT NULL CHECK(unpacked_bytes BETWEEN 0 AND 20971520),
 owners TEXT NOT NULL CHECK(octet_length(owners) <= 4000),
 etc_passwd TEXT NOT NULL CHECK(octet_length(etc_passwd) <= 100000),
 etc_group TEXT NOT NULL CHECK(octet_length(etc_group) <= 100000),
 uploaded_by BIGINT NOT NULL REFERENCES app_user(id),
 uploaded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX room_terminal_bundle_uploaded_by ON room_terminal_bundle(uploaded_by);
-- The archive is already compressed: store it out of line without trying to compress it again.
ALTER TABLE room_terminal_bundle ALTER COLUMN archive SET STORAGE EXTERNAL;
