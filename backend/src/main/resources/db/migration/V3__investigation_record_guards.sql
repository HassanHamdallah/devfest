CREATE OR REPLACE FUNCTION validate_investigation_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='room_manager' THEN
  IF NOT EXISTS(SELECT 1 FROM app_user WHERE id=NEW.user_id AND role='MANAGER' AND active) THEN
   RAISE EXCEPTION 'An assigned manager must be an active MANAGER' USING ERRCODE='23514';
  END IF;
 ELSE
  -- Archiving or renaming a team must remain possible after account deactivation.
  IF TG_OP='UPDATE' AND NEW.representative_id IS NOT DISTINCT FROM OLD.representative_id THEN RETURN NEW; END IF;
  IF NEW.representative_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM app_user WHERE id=NEW.representative_id AND role='MEMBER' AND active) THEN
   RAISE EXCEPTION 'A representative must be an active MEMBER' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION preserve_investigation_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Accepted investigation submissions are immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER immutable_investigation_submission BEFORE UPDATE OR DELETE ON investigation_submission FOR EACH ROW EXECUTE FUNCTION preserve_investigation_submission();
