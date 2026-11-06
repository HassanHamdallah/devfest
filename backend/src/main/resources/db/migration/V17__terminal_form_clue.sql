-- Linux room (rules §18.7, §18.8): there is no separate encrypted message. The target file holds one encrypted clue,
-- which the team enters as the Final clue and which is checked against the room's clue key as before. The form has
-- five answers, the fifth scored item is "Clue" (3 points when the final clue is correct), the total stays 15.
-- The event's final and its encrypted message (event_content) are not touched.

-- The teams' stored message answers and the organizers' message keys go away with the field.
ALTER TABLE terminal_form DROP COLUMN message_value;
ALTER TABLE terminal_answer_key DROP COLUMN message_key;

-- The fifth item is renamed. An organizer's award or denial of the old combined item carries over to the new item
-- (it decided the same fifth line of the score); it still never grants or revokes the room's clue.
ALTER TABLE terminal_form_override DROP CONSTRAINT terminal_form_override_item_check;
UPDATE terminal_form_override SET item = 'CLUE' WHERE item = 'MESSAGE_CLUE';
ALTER TABLE terminal_form_override
  ADD CONSTRAINT terminal_form_override_item_check CHECK(item IN ('PATH','USERNAME','FILE_SIZE','PATTERN','CLUE'));
