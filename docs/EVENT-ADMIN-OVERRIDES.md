# Admin overrides: fixing real-world problems during the event

The **Overrides** page (`/event/admin/overrides`, admin only) holds manual fixes for problems the rules do not
foresee. Each row of the Teams page has a **…** menu that opens the same section with that team preselected.

Every override:

- is available to the **admin only** (room managers never see these actions);
- needs a **reason** (3–500 characters), which is stored in the audit log with the action, the target and who did
  it (never passwords, answers or clue keys);
- shows a **confirmation** that states the exact consequence before anything changes;
- takes effect immediately and refreshes every open screen within about a second (live push);
- runs in one database transaction: it either happens completely or not at all.

The board's **Teams with overrides** list shows disqualified teams, room exceptions, manual clues and time
adjustments; **Recent overrides** lists the last 20 overrides with their reasons.

## When to use what

| Override | Use it when | What changes | What stays |
|---|---|---|---|
| **Swap teams** | Two teams sit in the wrong groups (e.g. they swapped tables). | The two teams exchange groups; their remaining rounds follow the new groups. The preview shows the rooms each team already did, the rooms of its remaining rounds, and warnings ("will miss …", "will visit … twice"). | Group sizes; all submissions, clues, penalties. |
| **Move team** | One team must change groups (e.g. a group is short). | The team's group. After round 1 the request must confirm the impact shown in the preview. Refused if the target group is full (ceil(N / 4)) — swap instead. | Everything the team did. |
| **Room exception** | A room broke, or a team arrived late and must use another room **this round**. | For the current round only, the team is expected in, and may submit only to, the chosen room (team home: "This round your team goes to the … room."; manager boards list it there). Warns if the room is closed, has no key, or the team already holds its clue. Can be cleared. | The rotation from the next round on. |
| **Manual clue** | A clue was earned but the system missed it (or must be taken back). | **Grant** adds a clue marked *Manual* (admins only see the mark; the team just sees it collected). **Revoke** removes a room or manual clue; a clue won in the recovery challenge only when "also remove recovery clue" is ticked. The team's final stage follows at its next refresh. | Submissions and clue verdicts. |
| **Time adjustment** | Compensation for downtime (or a correction). | A "Time adjustment" row of −60…+60 minutes that counts in the adjusted final time but **not** as a penalty in the "fewer penalties" tie-break. Voidable like a penalty. | Penalty counts. |
| **Disqualify / reinstate** | A team broke the rules. | A disqualified team stays signed in and keeps its records, but cannot submit room answers, recovery answers or accusations ("Your team has been disqualified. Contact the organizer.") and is listed last in the ranking as *Disqualified*, without a rank. Reinstating restores everything. | All records. |
| **Reset final** | The final was opened too early or by mistake. | Deletes recovery attempts, accusations, finish times, recovery clues and the automatic recovery / wrong-accusation penalties; closes the final, which can be opened again; content becomes editable again. | Everything from the room rounds. |
| **Reset one team** | A team's progress must start over (e.g. it played with the wrong account). | Deletes all that team did (submissions and verdicts, judgements and history, clues, penalties, final attempts, finish time, room exceptions) and signs its device out. | The team, its account and its group. |
| **Reset whole event** | A rehearsal on the event database, or a fresh start. | Deletes all progress of every team plus arrivals and time adjustments; round back to *Not started*, timer cleared, final never opened, rooms closed, results unpublished. Needs the word **RESET** and the admin's own password. | Accounts, teams, groups, planned team count, rooms, managers, clue keys, recovery challenges, suspects, message, correct suspect. |

## Snapshots

Every reset first saves a **snapshot** in the same transaction: a copy of every row it is about to delete plus the
event state before the change. If the snapshot cannot be saved, nothing is deleted. **Take snapshot now** saves a
full copy of all progress on demand (kind *Manual*). Snapshots are never deleted by the application.

Restoring a snapshot is **not** supported by the application. To inspect one:

1. On the Overrides page, **Download** the snapshot. The file `event-snapshot-<id>.json` looks like:

   ```json
   {
     "id": 7, "kind": "RESET_TEAM", "createdAt": "…", "createdBy": "Development administrator",
     "reason": "Played with the wrong account", "teamId": 3,
     "data": {
       "event_state": [ { "current_round": 2, "round_phase": "RUNNING", … } ],
       "room_submission": [ { "id": "…", "team_id": 3, "room_id": 1, "clue_answer": "…", "received_at": "…" } ],
       "submission_clue_verdict": [ … ], "collected_clue": [ … ], "penalty": [ … ], …
     }
   }
   ```

   Each key of `data` is a table name; each value holds that table's rows exactly as stored (column names as in the
   database). Kinds: `RESET_FINAL`, `RESET_TEAM`, `RESET_EVENT`, `MANUAL`.
2. Read it in any JSON viewer, or query it in the database, e.g.
   `SELECT jsonb_array_length(data -> 'room_submission') FROM event_snapshot WHERE id = 7;` or
   `SELECT x ->> 'clue_answer' FROM event_snapshot, jsonb_array_elements(data -> 'room_submission') x WHERE id = 7;`.

Snapshots contain team progress (including submitted answers) but no accounts, passwords, clue keys or recovery
answers.

## How resets delete append-only records

Submissions, verdict and judgement histories, recovery attempts and accusations are append-only: database triggers
refuse every UPDATE and DELETE. A reset deletes them only inside its own transaction after running
`SELECT set_config('devfest.allow_reset', 'on', true)`; the setting ends with that transaction. No other code
path sets it, and UPDATE stays forbidden even then.
