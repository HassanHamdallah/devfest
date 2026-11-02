# DevFest investigation event: rules for organizers

These are the rules the event software enforces. Organizers, room managers and the admin should
read them before the event. Where a rule says "the system", it is enforced on the server; nothing on a
team's screen can override it. All times come from the server clock.

## 1. Rooms

There are exactly four rooms, always in this order:

| Position | Room | Code |
|---|---|---|
| 1 | AI | `AI` |
| 2 | Problem solving | `PROBLEM_SOLVING` |
| 3 | Linux | `LINUX` |
| 4 | Hardware | `HARDWARE` |

- Each room has at most one manager, and a manager can manage only one room.
- Each room is either **open** or **closed**. Only the admin opens and closes rooms.
- Each room holds one **clue key** (the answer teams must find), set by the room's manager at runtime
  (rule 5), and one **recovery challenge** (a question plus its answer, used only in the final; see rule 9).

## 2. Groups and rotation

- Every team belongs to one group: A, B, C or D. How many teams take part is the organizer's choice; the
  group limit follows from it (rule 14). With the default 16 planned teams a group holds at most **4 active
  teams** (a fifth is refused with "Group A already has 4 active teams.").
- Each round, every group is scheduled in exactly one room, and the groups rotate so that every group
  visits every room once:

| Group | Round 1 | Round 2 | Round 3 | Round 4 |
|---|---|---|---|---|
| A | AI | Problem solving | Linux | Hardware |
| B | Problem solving | Linux | Hardware | AI |
| C | Linux | Hardware | AI | Problem solving |
| D | Hardware | AI | Problem solving | Linux |

## 3. Rounds and the round timer

The event round is one of: **Not started** (0), **Round 1 of 4** … **Round 4 of 4**, or **Rooms finished** (5).

1. **Phases.** Every running round (1–4) is in one phase: **Assigned** (teams are told which room to go to;
   the timer has not started), **Running** (the timer counts down), **Paused** (the timer is frozen) or
   **Time's up** (the timer was started and no time remains, or the admin ended the round). Rounds 0 and 5
   have no phase.
2. **Next round never starts the timer.** "Next round" (or "Set round") moves to the new round in phase
   Assigned, with the default round length (25 minutes unless changed; 1–180 minutes). If the current round is
   still running or paused with time left, the screen asks for confirmation first. "Next round" asks for one
   specific round number, so a double click cannot skip a round.
3. **The admin's timer controls**, each recorded in the audit log: **Start timer** (Assigned → Running),
   **Pause**, **Resume**, **Add time** (+1 min, +5 min, or 1–60 minutes; in Time's up the round continues with
   the added time — running if it was running, paused if it was paused), **Remove time** (1–60 minutes; never
   below the time already used — removing more ends the round), and **End round now** (with confirmation).
   Changing the default round length affects only rounds that begin later. Pressing Pause on a paused timer
   (or Start/Resume on a running one) changes nothing.
4. **Time's up stops answers.** In Time's up the server refuses new room answers: "Time is up for this
   round." Adding time accepts them again. Retrying an answer that was accepted before time ran out still
   returns its original receipt.
5. **Answers only while running.** Room answers are accepted only while the timer runs (and rule 4 holds):
   in Assigned "The timer has not started yet.", in Paused "The round is paused."
6. **Arrival check.** While the round is Assigned, each room manager sees the teams expected in the room and
   presses **All teams are here** (and can undo it until the timer starts). The admin sees each room as
   **Ready ✓** (with the manager and the time) or **Waiting**. Start timer works even if rooms are waiting, but
   asks first ("2 rooms are not ready. Start anyway?"). Every new round starts with all rooms waiting.
7. **One clock: the server's.** The server computes the remaining time from stored values and its own clock;
   screens only display it, counting down locally from the server's value and correcting at every refresh. The
   ticking itself never changes the event's revision; only admin and manager actions do.
8. **Rooms stay manual.** Opening and closing rooms is separate from the timer, exactly as before. Start timer
   warns — without blocking — when a room is closed or has no clue key.

## 4. Room submissions

A team may submit to a room only when **all** of these are true, checked in this order:

1. A round (1–4) is running — otherwise "Rooms are not running right now."
   Its timer is running (rule 3) — otherwise "The timer has not started yet.", "The round is paused." or
   "Time is up for this round."
2. The room is the team's scheduled room this round — otherwise "This is not your team's room in the current round."
3. The room is open — otherwise "This room is closed."
4. The room has a clue key — otherwise "This room is not ready yet. Ask the organizer." (A room without a key
   cannot be opened, so this is only a safety net.)

- A submission contains the **technical output** (up to 4000 bytes) and the **clue answer** (up to 500 bytes).
- Every submission is kept, and a team may resubmit as often as it likes while the conditions hold
  (up to 20 attempts per minute per team).
- Each accepted submission returns a **receipt** with its id and server time. If the network fails, the
  team's screen shows "Retry"; retrying sends the identical request and returns the **same** receipt, so
  nothing is ever counted twice.

## 5. Clue keys and clue checking

1. **Technical output is the manager's decision.** The system never checks technical output. The room's
   manager reads it and records Pass or Fail (rule 6); the manager board labels this "Technical output —
   your decision".
2. **Managers own the clue key.** The clue key of a room is set only at runtime, by the room's manager (or
   the admin), on the room board. No key exists in the application or its default data; the practice setup
   leaves every key empty.
3. **No key, no room.** A room cannot be opened while its key is empty ("Set the clue key for this room
   before opening it."), and the key of an open room cannot be cleared ("Close the room before removing its
   clue key."). The admin control screen lists every room without a key.
4. **Checking.** The system compares the team's clue answer with the key after normalizing both: leading and
   trailing spaces are removed, every run of spaces/tabs/newlines becomes one space, and letters become upper
   case. **Punctuation is kept.** An empty key never matches.

   | Clue key | Team answer | Result |
   |---|---|---|
   | `BLUE DOOR` | `  blue   door ` | Correct |
   | `BLUE DOOR` | `Blue Door` | Correct |
   | `BLUE DOOR` | `BLUE-DOOR` | Incorrect (punctuation differs) |
   | `BLUE DOOR` | `BLUEDOOR` | Incorrect (space missing) |
   | `+44-20-123` | `+44-20-123` | Correct |
   | `+44-20-123` | `4420123` | Incorrect |

5. **Changing the key re-checks answers.** When a manager saves a key that accepts different answers (until
   the final opens, rule 13), every answer already submitted to that room is checked again against the new
   key, at once. The board says how many ("Saved. Re-checked 3 answers."). Each team then collects the room's
   clue if **any** of its answers there is now correct, and loses a clue that came from that room if none is.
   A clue won through the recovery challenge is never removed. The result shown when the answer was submitted
   stays on record.
6. **Manual override.** For each answer the manager can choose **Automatic** (follow the key, the default),
   **Accept** or **Reject**. An override wins over the key and stays in force through later key changes until
   it is set back to Automatic. Every change records who made it, when, and the old and new verdict, and is
   refused once the final opens ("Clue verdicts cannot change after the final opens."). If two people change
   the same verdict at once, the second change is refused ("This clue verdict was changed by someone else.
   Reload and try again.").
7. **What teams see.** The room's manager and the admin see the key, the automatic result and any override.
   A team sees only its own result, Correct or Incorrect — never the key and never whether a person or the
   key decided it. When a result changes after submission, the team's screen shows it at its next refresh
   with "Updated after review".

## 6. Judging

- For every team in their room, the manager records a status for the technical output: **Pending**,
  **Passed** or **Failed**, a hint count (0–50) and an optional note (up to 1000 bytes).
- Judging is separate from clue collection: Passed or Failed **never** blocks a team from the final.
- If two people judge the same team at the same time, the second save is refused with
  "Someone else changed this judgement. Reload and try again." Saving exactly what is already stored is
  accepted silently. Every real change is recorded in a judgement history.
- The hint count is informational. Minutes are added only by **penalty entries** (rule 8). The room board
  warns the manager when the hint count and the number of active Hint penalties differ.

## 7. Collected clues

- A team **collects** a room's clue when one of its answers for that room is correct (by the key or by the
  manager's Accept). A later wrong answer never removes it; only a key change or an override can (rule 5).
- A clue can also be collected in the final through the recovery challenge (rule 9); such a clue is never
  removed.
- Teams see "*n* of 4 clues collected" on their home screen.

## 8. Penalties

| Kind | Minutes | Who adds it |
|---|---|---|
| Hint | 2 | Room manager or admin |
| Minor violation | 2 | Room manager or admin |
| Prohibited tool | 5 | Room manager or admin |
| Cheating | 5 | Room manager or admin |
| Answer sharing | 5 | Room manager or admin |
| Recovery | 5 | Automatic, once per team at most (rule 9) |
| Wrong accusation | 3 | Automatic, each wrong accusation (rule 10) |

- Penalties are **never deleted**. A mistaken penalty is **voided** once, with a reason; the void records who
  and when. Voided penalties count nowhere.
- Recovery and Wrong accusation penalties cannot be added by hand.
- A manager can void only penalties in their own room. Wrong accusation penalties belong to no room and
  can be voided only by the admin.

## 9. The final

- The admin opens the final **once**, for all teams at the same moment. Its start time is the server time
  of that first opening. After it is closed it cannot be reopened.
- The final can only be opened when the content is complete: the encrypted message, 6 suspects, the
  correct suspect, and a clue key and recovery challenge for every room.
- What each team sees depends on its collected clues:

| Situation | Team sees |
|---|---|
| Final not opened yet | "The final opens for all teams at the same time." |
| 4 clues | The encrypted message, the suspects and the accusation form |
| 3 clues | The **recovery challenge** of the one missing room (+5 minutes if solved) |
| 0–2 clues | "Better luck next time" |
| Case solved | "Case solved" with finish time, penalties and adjusted time |
| Final closed | "The final is closed." |

- Recovery: unlimited attempts, no penalty for a wrong answer, answers are normalized as in rule 5.
  A correct answer collects the missing clue, adds the one-time **+5 minute Recovery penalty**, and shows the
  encrypted message.

## 10. Accusation

- With the encrypted message on screen, the team picks one of the listed suspects and submits the decrypted
  message and its evidence.
- A **wrong** suspect adds **+3 minutes** ("Incorrect accusation — +3 minutes. Try again.") and the team may try
  again. The system never reveals the correct suspect.
- The **correct** suspect finishes the team: its finish time is the server time the accusation arrived.
  No further accusations are accepted.

## 11. Ranking

Only active teams are ranked.

1. Finished teams come first, ordered by **adjusted time**, lowest first:
   adjusted time = (finish time − final start time, in whole seconds) + 60 × (active penalty minutes).
2. Equal adjusted time → fewer active penalty **entries** first.
3. Still equal → earlier finish time first.
4. Teams equal on all three share a rank (for example 1, 1, 3).
5. Unfinished teams follow without a rank, ordered by collected clues (most first), then team name.

Worked example (final opened at 10:00:00):

| Team | Finished | Elapsed | Active penalties | Adjusted | Entries | Rank |
|---|---|---|---|---|---|---|
| Team 05 | 10:15:00 | 900 s | none | 900 s | 0 | 1 |
| Team 02 | 10:10:00 | 600 s | Recovery 5 | 600 + 300 = 900 s | 1 | 2 |
| Team 09 | 10:13:00 | 780 s | Hint 2, Minor violation 2 (a voided Hint is ignored) | 780 + 240 = 1020 s | 2 | 3 |
| Team 01 | 10:12:30 | 750 s | Hint 2, Recovery 5, Wrong accusation 3 | 750 + 600 = 1350 s | 3 | 4 |
| Team 14 | not finished | — | — | — | — | — (4 clues) |
| Team 07 | not finished | — | — | — | — | — (3 clues) |

Team 05 and Team 02 tie at 900 s; Team 05 ranks higher because it has fewer penalty entries.

## 12. Publication

- Results are private until the admin presses **Publish results**. Before that, teams see
  "Results are not published yet." and room managers never see the ranking.
- After publication, teams see a "View ranking" link. The admin can unpublish at any time.

## 13. Content editing lock

- Before the final opens, the admin can edit all content (clue keys, recovery challenges, encrypted message,
  suspects, correct suspect). A room manager can edit only their own room's clue key and recovery challenge.
- **Once the final has opened, all content is read-only** ("Event content is read-only after the final opens."),
  so every team is judged against the same content.

## 14. Team count and group capacity

1. **Planned team count.** The admin sets the planned team count N (1–64) on **Teams & accounts**. The four
   groups and the room rotation (rule 2) do not change; only the number of teams per group does.
2. **Group capacity.** A group may hold at most **ceil(N / 4)** active teams (N = 20 → 5 per group;
   N = 10 → 3 per group). This cap is enforced when a team is created, moved to another group or reactivated
   ("Group A already has 5 active teams."). Lowering N is refused while a group would exceed the new cap.
3. **Planned count limit.** Creating teams (one at a time or generated in bulk) or reactivating one is refused
   when the active teams would exceed N ("This would exceed the planned team count (N).").
4. **Balance warnings.** The admin control screen and the teams page warn — without blocking anything — when
   the number of active teams differs from N, when a group is over its cap, or when group sizes differ by more
   than one.
5. **Generated teams.** "Generate teams" creates teams "<prefix> 01", "<prefix> 02", … with member accounts
   `<prefix>01@event.local`, … and random 16-character passwords, skipping names and emails that already
   exist. Each new team joins the currently smallest group (ties: A before D). All teams are created or none.
   The passwords are shown **once**; they are not stored in plain text and cannot be shown again — use
   **Reset password** to issue a new one.
6. **Groups locked after round 1.** While the event has not started (round 0) teams can be created, moved
   between groups, deactivated and reactivated freely. From round 1 on, creating and deactivating teams is
   still allowed, but **moving a team to another group is refused** ("Groups are locked after round 1
   starts."), because it would break the rotation.
7. **Deactivated teams** are signed out, cannot sign in, and are left out of the ranking and the rooms'
   "teams now" lists. Their submissions, clues and penalties are kept.

## 15. One device per team

1. A team account can be signed in on **one device at a time**. Signing in on a second device signs the
   first one out within about a second (at most about 3 seconds if its live connection is down), and it shows: "Your team signed in on
   another device. Only one device per team can be signed in." The latest sign-in always wins.
2. Admin and room manager accounts may stay signed in on several devices.
3. The teams page shows, per team, whether its device is **signed in** (seen in the last 2 minutes) and when it
   was **last seen**. **Sign out team** signs the team's device out; it shows "An organizer signed your team
   out." and the team can sign in again.
4. A submission that was still waiting to be sent when a device was signed out stays saved on that device and
   can be retried after signing in again on it.

## 16. Admin overrides

Manual fixes for real-world problems; details and examples in
[EVENT-ADMIN-OVERRIDES.md](EVENT-ADMIN-OVERRIDES.md). Every override is **admin only**, needs a **reason**
(3–500 characters) recorded in the audit log, asks for confirmation stating the exact consequence, and applies at
once in a single transaction.

1. **Swap two teams** (any round): the two active teams exchange groups. Before confirming, the admin sees a
   preview of the rooms each team already visited, the rooms of its remaining rounds after the swap, and warnings
   "will miss <room>" / "will visit <room> twice". Warnings do not block. Group sizes do not change.
2. **Move a team to another group**: the same preview and warnings; the group limit ceil(N / 4) still applies (a
   full group is refused — swap instead). After round 1 starts, the move needs the admin's explicit confirmation of
   the impact; without it the server refuses ("Groups are locked after round 1 starts. …").
3. **Room exception** (current round only): team X is sent to room Y for this round. Its home screen says "This
   round your team goes to the Y room.", the room boards list it in room Y, and it may submit only to room Y. From
   the next round the rotation applies again. The admin is warned if room Y is closed, has no clue key, or the team
   already collected room Y's clue. Exceptions can be cleared.
4. **Manual clue**: grant (marked *Manual* for admins only) or revoke the clue of room R for team T. Revoking never
   removes a clue won in the recovery challenge unless the admin explicitly asks for it. Allowed before and during
   the final; the team sees its new final stage at the next refresh. Submissions and verdicts do not change.
5. **Time adjustment**: −60 to +60 minutes (never 0) on a team's adjusted final time, shown as "Time adjustment".
   It counts in adjusted time but not as a penalty in the "fewer penalties" tie-break, and can be voided.
6. **Disqualify / reinstate**: a disqualified team stays signed in and keeps its records, but cannot submit
   anything ("Your team has been disqualified. Contact the organizer.") and is listed last in the ranking, marked
   *Disqualified*, without a rank and outside the rank numbering. Reinstating restores normal play.
7. **Reset final**: all final progress is deleted (recovery attempts, accusations, finish times, recovery clues,
   automatic recovery and wrong-accusation penalties); the final closes and may be opened again, and content becomes
   editable again. Room-phase data stays.
8. **Reset one team**: all of the team's progress is deleted; the team, its account and its group stay, and its
   device is signed out ("An organizer signed your team out.").
9. **Reset the whole event**: all progress of all teams is deleted, the event returns to *Not started* with all
   rooms closed and results unpublished; accounts, teams, groups, the planned team count, rooms, managers, clue keys
   and all content stay. Requires typing **RESET** and the admin's own password (a wrong password changes nothing).
10. **Snapshots**: every reset first saves a snapshot of all rows it deletes and of the event state, in the same
    transaction — if the snapshot fails, nothing changes. The admin can take a snapshot at any time, list all
    snapshots and download each as a JSON file. Snapshots are never deleted; restoring one is not supported.
