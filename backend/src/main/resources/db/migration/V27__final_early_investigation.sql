-- The active second of the final's clock at which a team's fourth passphrase was accepted. It opens the team's
-- investigation phase early (the earlier of that moment and S + phase1Minutes x 60). Set once, never changed.
ALTER TABLE team_final ADD COLUMN all_decrypted_at_seconds INT CHECK(all_decrypted_at_seconds >= 0);
