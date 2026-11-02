-- Organizer-controlled team count and one signed-in device per team account.

ALTER TABLE event_state
  ADD COLUMN planned_team_count INT NOT NULL DEFAULT 16 CHECK(planned_team_count BETWEEN 1 AND 64);

-- Why a session ended, so the signed-out device can be told; NULL for sessions that simply expired.
ALTER TABLE auth_session
  ADD COLUMN revoked_reason VARCHAR(20) NULL
    CHECK(revoked_reason IN ('LOGOUT','REPLACED','ORGANIZER','DEACTIVATED','PASSWORD_RESET','REPLAY')),
  ADD COLUMN last_seen_at TIMESTAMPTZ NULL;
