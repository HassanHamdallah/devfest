-- The final's written justification (rules section 10): up to four pieces of evidence and an explanation, stored with the
-- one-time suspect submission in the same guarded UPDATE of team_final, so it is recorded once and never changes after.
-- All columns stay NULL for a team that submitted no report or never submitted a suspect. Organizers read it; it never
-- affects a score or the ranking.
ALTER TABLE team_final
 ADD COLUMN evidence1 TEXT CHECK(char_length(evidence1) <= 600),
 ADD COLUMN evidence2 TEXT CHECK(char_length(evidence2) <= 600),
 ADD COLUMN evidence3 TEXT CHECK(char_length(evidence3) <= 600),
 ADD COLUMN evidence4 TEXT CHECK(char_length(evidence4) <= 600),
 ADD COLUMN explanation TEXT CHECK(char_length(explanation) <= 600);
