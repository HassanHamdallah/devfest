-- Hardware room (rules §21): a points ledger out of 100 (appended step by step by the room's manager) and a phone number
-- answer that releases the room's existing key automatically. The room's score is its total x 0.15.

-- The fourth room activity (one per room). Default for a fresh setup only: the Hardware room, when it has none yet.
ALTER TABLE event_room DROP CONSTRAINT event_room_activity_check;
ALTER TABLE event_room ADD CONSTRAINT event_room_activity_check
 CHECK(activity IN ('AI_QUIZ','LINUX_TERMINAL','CONTEST_RESULTS','PHONE_POINTS'));
UPDATE event_room SET activity = 'PHONE_POINTS' WHERE code = 'HARDWARE' AND activity IS NULL;

-- The room's phone number answer (room content, set by its manager or the admin, never sent to members). Empty = not set.
ALTER TABLE event_room ADD COLUMN phone_number VARCHAR(40) NOT NULL DEFAULT '' CHECK(char_length(phone_number) <= 40);

-- The points ledger: one row per addition, subtraction or undo, never changed. total_after is the team's total once the
-- row applies (always 0..100); an UNDO row carries the opposite delta of the row it reverses. Team progress: resets
-- delete it.
CREATE TABLE room_points_entry (
 id BIGSERIAL PRIMARY KEY,
 team_id BIGINT NOT NULL REFERENCES event_team(id),
 room_id BIGINT NOT NULL REFERENCES event_room(id),
 kind VARCHAR(4) NOT NULL CHECK(kind IN ('ADD','UNDO')),
 delta INT NOT NULL CHECK(delta BETWEEN -100 AND 100 AND delta <> 0),
 total_after INT NOT NULL CHECK(total_after BETWEEN 0 AND 100),
 reverses_id BIGINT REFERENCES room_points_entry(id),
 idempotency_key UUID NOT NULL UNIQUE,
 entered_by BIGINT NOT NULL REFERENCES app_user(id),
 entered_at TIMESTAMPTZ NOT NULL,
 CHECK((kind = 'UNDO') = (reverses_id IS NOT NULL))
);
CREATE INDEX room_points_entry_team_room ON room_points_entry(team_id, room_id, id);
CREATE INDEX room_points_entry_room ON room_points_entry(room_id);
CREATE INDEX room_points_entry_entered_by ON room_points_entry(entered_by);
CREATE UNIQUE INDEX room_points_entry_one_undo ON room_points_entry(reverses_id) WHERE reverses_id IS NOT NULL;

-- CONVERSION: a typed manual score s (0..15) of a room that now runs the points ledger becomes one entry of
-- round(s / 0.15) points, so nothing is lost. The old table stays, unused.
INSERT INTO room_points_entry(team_id, room_id, kind, delta, total_after, idempotency_key, entered_by, entered_at)
SELECT m.team_id, m.room_id, 'ADD', round(m.score / 0.15)::int, round(m.score / 0.15)::int, gen_random_uuid(), m.entered_by, m.entered_at
FROM room_manual_score m
JOIN event_room r ON r.id = m.room_id AND r.activity = 'PHONE_POINTS'
WHERE round(m.score / 0.15)::int > 0;
