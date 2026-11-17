-- The final's case becomes "The Last Shipment" (rules section 10): four encrypted evidence files opened by their
-- passphrases, a key index, a suspects sheet and one suspect; plus the final's own terminal package.
--
-- Retired here: the four RSA number layers and the two signature verdicts. Nothing reads their tables any more, so they
-- are dropped; past audit entries (audit_event) and snapshots (event_snapshot JSON) are kept untouched. There is no live
-- event data to keep. The final content row (format 1) no longer validates and is removed; the admin imports format 2.

DROP TABLE final_verdict;
DROP TABLE final_layer_attempt;
DELETE FROM final_content;

-- Suspects are now named by the content file's ids ("S11"), no longer by a position 1..6.
ALTER TABLE team_final DROP CONSTRAINT team_final_suspect_id_check;
ALTER TABLE team_final ALTER COLUMN suspect_id TYPE VARCHAR(10) USING suspect_id::text;
ALTER TABLE team_final ADD CONSTRAINT team_final_suspect_id_check CHECK(suspect_id ~ '^[A-Za-z0-9_-]{1,10}$');

-- ---- passphrase attempts: append-only, one correct row per team and clue (the clue is solved for good) ------------
CREATE TABLE final_clue_attempt (
 id UUID PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 clue INT NOT NULL CHECK(clue BETWEEN 1 AND 4),
 value TEXT NOT NULL CHECK(octet_length(value) BETWEEN 1 AND 400),
 correct BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL,
 idempotency_key UUID NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id, idempotency_key)
);
CREATE UNIQUE INDEX one_correct_clue ON final_clue_attempt(team_id, clue) WHERE correct;
CREATE INDEX final_clue_attempt_team_clue ON final_clue_attempt(team_id, clue, received_at);
CREATE TRIGGER immutable_final_clue_attempt BEFORE UPDATE OR DELETE ON final_clue_attempt
 FOR EACH ROW EXECUTE FUNCTION reject_event_record_change();

-- ---- the final's terminal package: one row, kept apart from every room's package (room_terminal_bundle) ------------
-- Same columns and limits as a room's package; uploading, replacing or deleting it never touches a room's row.
CREATE TABLE final_terminal_bundle (
 id INT PRIMARY KEY CHECK(id = 1),
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
CREATE INDEX final_terminal_bundle_uploaded_by ON final_terminal_bundle(uploaded_by);
ALTER TABLE final_terminal_bundle ALTER COLUMN archive SET STORAGE EXTERNAL;
