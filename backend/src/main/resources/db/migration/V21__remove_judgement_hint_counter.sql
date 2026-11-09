-- The judgement's own hint counter was informational and nothing reads it: hints are now the -2 deductions of V20.
ALTER TABLE room_judgement DROP COLUMN hints;
ALTER TABLE room_judgement_history DROP COLUMN hints;
