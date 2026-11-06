-- Linux room terminal clock (rules 18.6): an optional UTC date-time the terminal sets with date -s before the shell is
-- handed over, so that find -mtime and similar give the same result on any event day. Empty = real time. It belongs to
-- the room's server package (one row per room), is editable without uploading the package again and survives a
-- replacement of the package; removing the package removes it.
ALTER TABLE room_terminal_bundle
  ADD COLUMN terminal_clock TIMESTAMPTZ NULL
  CHECK(terminal_clock IS NULL OR (terminal_clock >= TIMESTAMPTZ '1970-01-01 00:00:00+00' AND terminal_clock < TIMESTAMPTZ '2100-01-01 00:00:00+00'));
