-- Extend the existing application; preserve all coding-contest records.
ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK(role IN ('ADMIN','PARTICIPANT','MANAGER','MEMBER'));

CREATE TABLE investigation_settings (
 id INT PRIMARY KEY CHECK(id=1), results_published BOOLEAN NOT NULL DEFAULT FALSE,
 rules_confirmed BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO investigation_settings(id) VALUES(1);
CREATE TABLE room (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE,
 archived BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE room_manager (
 room_id BIGINT NOT NULL REFERENCES room(id), user_id BIGINT NOT NULL REFERENCES app_user(id),
 PRIMARY KEY(room_id,user_id)
);
CREATE TABLE team (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE,
 room_id BIGINT NOT NULL REFERENCES room(id),
 representative_id BIGINT UNIQUE REFERENCES app_user(id), archived BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE(id,room_id)
);
CREATE TABLE investigation_challenge (
 id BIGSERIAL PRIMARY KEY, room_id BIGINT NOT NULL REFERENCES room(id),
 title VARCHAR(160) NOT NULL, instructions TEXT NOT NULL CHECK(octet_length(instructions)<=65536),
 evidence_links TEXT NOT NULL, answer_format VARCHAR(1000) NOT NULL, rubric TEXT NOT NULL CHECK(octet_length(rubric)<=65536),
 max_score INT NOT NULL CHECK(max_score BETWEEN 1 AND 10000),
 kind VARCHAR(10) NOT NULL CHECK(kind IN ('ANSWER','FINAL')),
 state VARCHAR(10) NOT NULL CHECK(state IN ('DRAFT','OPEN','CLOSED')),
 opens_at TIMESTAMPTZ NOT NULL, closes_at TIMESTAMPTZ NOT NULL CHECK(closes_at>opens_at),
 version BIGINT NOT NULL DEFAULT 1, UNIQUE(id,room_id), UNIQUE(id,room_id,kind)
);
CREATE UNIQUE INDEX one_final_per_room ON investigation_challenge(room_id) WHERE kind='FINAL';
CREATE TABLE investigation_draft (
 team_id BIGINT NOT NULL, challenge_id BIGINT NOT NULL, room_id BIGINT NOT NULL,
 conclusion TEXT NOT NULL CHECK(octet_length(conclusion)<=65536), reasoning TEXT NOT NULL CHECK(octet_length(reasoning)<=65536),
 evidence TEXT NOT NULL CHECK(octet_length(evidence)<=65536), version BIGINT NOT NULL DEFAULT 1,
 updated_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(team_id,challenge_id), FOREIGN KEY(team_id,room_id) REFERENCES team(id,room_id),
 FOREIGN KEY(challenge_id,room_id) REFERENCES investigation_challenge(id,room_id)
);
CREATE TABLE investigation_submission (
 id UUID PRIMARY KEY, team_id BIGINT NOT NULL, challenge_id BIGINT NOT NULL, room_id BIGINT NOT NULL,
 author_id BIGINT NOT NULL REFERENCES app_user(id), kind VARCHAR(10) NOT NULL,
 conclusion TEXT NOT NULL CHECK(octet_length(conclusion)<=65536), reasoning TEXT NOT NULL CHECK(octet_length(reasoning)<=65536),
 evidence TEXT NOT NULL CHECK(octet_length(evidence)<=65536),
 challenge_snapshot TEXT NOT NULL, max_score INT NOT NULL CHECK(max_score BETWEEN 1 AND 10000),
 received_at TIMESTAMPTZ NOT NULL, sequence BIGSERIAL UNIQUE,
 idempotency_key UUID NOT NULL, request_hash VARCHAR(64) NOT NULL,
 UNIQUE(team_id,idempotency_key),
 FOREIGN KEY(team_id,room_id) REFERENCES team(id,room_id),
 FOREIGN KEY(challenge_id,room_id,kind) REFERENCES investigation_challenge(id,room_id,kind)
);
CREATE UNIQUE INDEX one_final_report ON investigation_submission(team_id,challenge_id) WHERE kind='FINAL';
CREATE INDEX investigation_history ON investigation_submission(team_id,challenge_id,sequence DESC);
CREATE TABLE investigation_score (
 submission_id UUID PRIMARY KEY REFERENCES investigation_submission(id),
 points INT NOT NULL CHECK(points>=0), feedback VARCHAR(4000) NOT NULL,
 reviewer_id BIGINT NOT NULL REFERENCES app_user(id), updated_at TIMESTAMPTZ NOT NULL,
 version BIGINT NOT NULL DEFAULT 1
);
CREATE TABLE investigation_score_history (
 id BIGSERIAL PRIMARY KEY, submission_id UUID NOT NULL REFERENCES investigation_submission(id),
 old_points INT, points INT NOT NULL CHECK(points>=0), feedback VARCHAR(4000) NOT NULL,
 reviewer_id BIGINT NOT NULL REFERENCES app_user(id), changed_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL,
 UNIQUE(submission_id,version)
);
-- Cross-table invariants also hold for administrative SQL imports.
CREATE FUNCTION validate_investigation_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='room_manager' THEN
  IF NOT EXISTS(SELECT 1 FROM app_user WHERE id=NEW.user_id AND role='MANAGER' AND active) THEN
   RAISE EXCEPTION 'An assigned manager must be an active MANAGER' USING ERRCODE='23514';
  END IF;
 ELSE
  IF NEW.representative_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM app_user WHERE id=NEW.representative_id AND role='MEMBER' AND active) THEN
   RAISE EXCEPTION 'A representative must be an active MEMBER' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER valid_manager BEFORE INSERT OR UPDATE ON room_manager FOR EACH ROW EXECUTE FUNCTION validate_investigation_assignment();
CREATE TRIGGER valid_representative BEFORE INSERT OR UPDATE OF representative_id ON team FOR EACH ROW EXECUTE FUNCTION validate_investigation_assignment();
CREATE FUNCTION validate_investigation_score() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.points > (SELECT max_score FROM investigation_submission WHERE id=NEW.submission_id) THEN
  RAISE EXCEPTION 'Score exceeds submission maximum' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER valid_score BEFORE INSERT OR UPDATE ON investigation_score FOR EACH ROW EXECUTE FUNCTION validate_investigation_score();
