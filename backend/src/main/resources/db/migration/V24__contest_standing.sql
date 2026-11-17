-- Problem Solving room scoring (rules §20.4): six problems A to F, and a standing the organizer picks from the
-- Codeforces standings instead of a typed penalty. Existing rows keep their data: problem F is unsolved for them, and a
-- missing standing (NULL) is scored as OTHER until the organizer saves the team again.
ALTER TABLE contest_result ADD COLUMN solved_f BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE contest_result ADD COLUMN standing VARCHAR(6) CHECK(standing IN ('FIRST','SECOND','THIRD','OTHER'));
-- The penalty is no longer typed or used; the column stays (never dropped), and new rows take its default.
ALTER TABLE contest_result ALTER COLUMN penalty_minutes SET DEFAULT 0;
