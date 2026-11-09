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
  (rule 5). The final has one **Recovery Challenge** for everyone (rules 9 and 10).

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
   A clue won through the Recovery Challenge is never removed. The result shown when the answer was submitted
   stays on record.
6. **Manual override.** For each answer the manager can choose **Automatic** (follow the key, the default),
   **Accept** or **Reject**. An override wins over the key and stays in force through later key changes until
   it is set back to Automatic. Every change records who made it, when, and the old and new verdict, and is
   refused once the final opens ("Clue verdicts cannot change after the final opens."). If two people change
   the same verdict at once, the second change is refused ("This clue verdict was changed by someone else.
   Reload and try again.").
7. **What teams see.** The room's manager and the admin see the key, the automatic result and any override.
   A team sees its own result, Correct or Incorrect — never whether a person or the key decided it. A team
   receives a room's clue key **only** for a room whose clue that same team has collected **and** that is
   finished for the team (rule 17), or for any collected room once the final has opened. It never receives the
   key of a room whose clue it has not collected, another team's answers or keys, a recovery answer, the correct
   suspect, or override and automatic-check details. When a result changes after submission, the team's screen
   shows it at its next refresh with "Updated after review".

## 6. Judging

- For every team in their room, the manager records a status for the technical output: **Pending**,
  **Passed** or **Failed** and an optional note (up to 1000 bytes).
- Judging is separate from clue collection: Passed or Failed **never** blocks a team from the final.
- If two people judge the same team at the same time, the second save is refused with
  "Someone else changed this judgement. Reload and try again." Saving exactly what is already stored is
  accepted silently. Every real change is recorded in a judgement history.
- Hints are only the **Hint given (−2)** deductions of rule 8; the judgement has no counter of its own.

## 7. Collected clues

- A team **collects** a room's clue when one of its answers for that room is correct (by the key or by the
  manager's Accept). A later wrong answer never removes it; only a key change or an override can (rule 5).
- A clue can also be collected in the final through the Recovery Challenge (rule 9); such a clue is never
  removed.
- Teams see "*n* of 4 clues collected" on their home screen.

## 8. Scoring (100 points), hints and cheating

The event is worth **100 points**: four rooms of **15** each (60) plus the **final** (40). Scores are computed on every
read from what the rooms and the final recorded; no total is stored. Every score is rounded to 2 decimals.

1. **Room base score** (the room's own score on the 15-point scale):

   | Room source | Base |
   |---|---|
   | AI mission (rule 19) | its total out of 100 × 0.15 (99 gives 14.85) |
   | Linux terminal (rule 18.8) | its score out of 15 |
   | Contest (rule 20) | its score out of 15 |
   | Any other room (Hardware today) | the score the room's manager types in, 0 to 15 (at most 2 decimals; audited with its value) |

   A room is read by its **activity** (rule 1 of 18, 19, 20), not by its name. The typed score is a temporary input for a
   room that has no automatic score; a room that has one refuses it ("This room's score comes from its activity; it cannot
   be typed in."). Only the room's manager and the admin type it.
2. **Room score = MAX(0, base − 2 × active hints in that room − 7.5 if an active cheating violation exists for that
   room).** Hints and cheating are **point deductions only**; nothing is ever added to a team's time, and a deduction never
   changes a clue or whether a team qualifies.
3. **Where.** On the room board, the same team card the manager sees for the teams currently in the room, with two
   actions and the room's deduction list:
   - **Hint given (−2)**: one click, optional note. A team has **2 hints across all four rooms**; the third is refused
     (409 "This team has already used its 2 hints."). The card shows "Hints: n of 2".
   - **Cheating (−7.5)**: needs a confirmation and a **required reason**. One active violation per team and room; a repeat
     is refused (409 "This team already has an active cheating violation in this room.").
4. **Who.** Only the room's manager or the admin may record or cancel, for a team that is in that room now (its rotation
   room, or the room an admin exception sent it to this round; anything else is a 404). The admin has both actions for
   any team and room. Another room's manager gets 404 for the room.
5. **Cancelling** needs a reason, keeps the row in the log and restores the hint or the points. A cancelled violation may
   be recorded again.
6. **The log is append-only**: kind, room, team, reason, who, when, and who cancelled it, when and why. The database refuses
   deleting or editing a row; the only permitted change is one cancellation. The manager reads their room's rows, the
   admin all of them (`GET /rooms/{id}/deductions`, `GET /admin/deductions`).
7. **What a team sees**: only "Hints used: n of 2". Never a cheating record, a deduction, a reason or a score before the
   results are published (rule 12), and then only the scores, not how they were made.
8. **Retired.** The minute-based penalties (hint, minor violation, prohibited tool, cheating, answer sharing, recovery,
   wrong accusation), the admin's time adjustment, adjusted time and the penalty tie-break no longer exist anywhere in the
   rules, the screens or the ranking. No other deduction exists. Past audit entries are kept.

## 9. The final (qualification, recovery, timer)

- The admin opens the final **once**, for all teams at the same moment; it cannot be reopened (the admin's *Reset final*
  override, rule 16.7, is the only way back). It opens only when the content is complete: the validated crypto content
  file (rule 10) and a clue key for every room.
- **One server-side 40-minute timer** starts at that moment for everyone. The admin's emergency **pause / resume** and
  **added time** (1 second to 60 minutes, with an Idempotency-Key) apply to every team at once and are audited; the timer is
  never per team and never reset. Once the time has run out it can no longer change.
- **Status per team**, derived on the server from its collected clues:

  | Clues | Status | What happens |
  |---|---|---|
  | 4 | **In final** | the unlock screen opens immediately |
  | 3 | **Recovery** | the single unified Recovery Challenge starts at the same moment |
  | 0–2 | **Not qualified** | "You did not qualify for the final", no recovery |
  | — | **Completed** | the suspect was submitted, the time ran out or the final was closed |

- **Recovery Challenge**: one question for every team (not one per room); unlimited attempts, no penalty and no room
  points. A correct answer (normalized like clue answers, rule 5.4) collects the team's missing clue (source *Recovery*) and
  moves it to the case **with the remaining time**. Teams with 0–2 clues cannot use it.
- Every rule is enforced on the server: a disqualified team, a not-open final, a paused final and a team that did not
  qualify are all refused.

## 10. The crypto case

State always comes from the server; a refresh resumes where the team was. Screens, in order:

1. **Unlock**: four inputs labelled with the real room names (in room order). Each must match **that room's clue key**
   (rule 5.4) **and** the team must hold that room's clue. A wrong try says only "These room outputs do not open the case."
   (never which field); **5 wrong in a row = a 30-second wait**.
2. **Mission**: "Rami left four encrypted evidence layers. Solve RSA on paper, examine the clues, and verify the signed
   reports." Equipment: printed dossiers and four shuffled key cards; a plain calculator is allowed. **Start case**.
3. **Layers 1–4**: each shows only the ciphertext C and the modulus n and takes one integer. A correct integer opens that
   layer's evidence (E01…E04); layers open in order. A wrong one says only "That number does not open this layer." and never
   the value; **5 wrong in a row on a layer = a 30-second wait** (per layer). Every evaluated attempt is stored; an answer
   refused during a wait is not an attempt.
4. **Verdicts** (E03 and E04): YES or NO, **one time only**, never revealing correctness; a wrong verdict never blocks
   progress. The team sees S, e, n, H and the claimed signer.
5. **Notebook**: the evidence revealed so far stays reviewable, with no hint about the culprit.
6. **Suspects**: choose **one** of the six; confirmation; **one time only**; no free text.
7. **Submitted**: "Your final answer is recorded. The result stays confidential until judging." with team, status and
   time; never whether anything was right.

**Content** is one validated JSON file imported by the admin (`PUT /admin/final/content`, shipped as
`docs/final/crypto-content.json`) and read-only once the final opens. The server **derives** every plaintext
M = C^d mod n and every verdict (S^e mod n = H) and validates n = p × q and that each next C equals the previous M; all
problems are listed at once and nothing is saved when there is one. The file also holds the evidence texts, the six
suspects, the correct suspect, the Recovery Challenge (question and accepted answers; the shipped file keeps an obvious
placeholder, and the admin board warns while it is still there) and the "full points until" minutes (default 25). A member
never receives M, d, p, q, a verdict, the correct suspect, a score or another team's data.

**Final score (40)**: each correct layer 2.5 and each correct verdict 2.5 (crypto, at most 15); the correct suspect 10;
**time points (at most 15)** from t = the **active** minutes from the final's start to the suspect submission (pauses are
not counted), for everyone including recovery teams: t ≤ the full-until minutes → 15, otherwise MAX(0, 40 − t). Time points
need all four layers solved and a submitted suspect, otherwise 0.

**Time up**: input stops, unanswered parts score 0, and the team's final is finalised **exactly once**, inside the same
transaction as whatever first notices it (a team's write finds no time left, or the admin closes the final); there is no
scheduler, and the single event lock makes a submission racing the end resolve to one outcome.

## 11. Ranking

Only active teams are ranked. **Total** (rooms + final) **DESC**, then **final score DESC**, then the **earlier final
finish** (the suspect submission time; a team that never submitted finishes last); teams equal on all three **share a
rank** (1, 1, 3). Teams that did not qualify rank by their room scores like everyone else. **Disqualified** teams stay
outside the ranking: listed last, without a rank, outside the rank numbering. The admin sees every team's full
breakdown (`GET /admin/scores`, `GET /admin/scoreboard`, the admin board, the export); teams see the published ranking only
(rule 12).

**Where it is on screen.** The room manager's team card (and the admin's team panel, for any team and room) carries
"Hint given (−2)", "Cheating (−7.5)", "Hints: n of 2" and the room's deduction list with cancel; a room without an
automatic score also has a 0 to 15 score input. The admin's **Scores** page (`/event/admin/scores`) has the overall
scoreboard (four room lines with base and deductions, the final's parts, the total out of 100, the rank; CSV), the live
final table (`GET /admin/final/board`: status, stage, attempts, verdicts, suspect, active time, finish time, score parts)
and the full penalty log. The Content page imports the crypto content file; the Control page has the final timer's
pause, resume and added time.

## 12. Publication

- Results are private until the admin presses **Publish results**. Before that, teams see "Results are not published yet."
  and room managers never see the ranking.
- After publication, teams see the ranking with the four room scores, the final score and the total of every team
  (no deductions, cheating records or hints). The admin can unpublish at any time.

## 13. Content editing lock

- Before the final opens, the admin can edit all content (clue keys, terminal files, AI mission content, the crypto
  content file of rule 10). A room manager can edit only their own room's
  clue key, terminal files and server package (rule 18) and AI mission (rule 19). The AI mission is also
  locked as soon as a team has run or saved anything.
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
   "teams now" lists. Their submissions, clues and deductions are kept.

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
   removes a clue won in the Recovery Challenge unless the admin explicitly asks for it. Allowed before and during
   the final; the team sees its new final stage at the next refresh. Submissions and verdicts do not change.
5. **Disqualify / reinstate**: a disqualified team stays signed in and keeps its records, but cannot submit
   anything ("Your team has been disqualified. Contact the organizer.") and is listed last in the ranking, marked
   *Disqualified*, without a rank and outside the rank numbering. Reinstating restores normal play.
6. **Reset final**: all final progress is deleted (unlock and layer attempts, verdicts, suspect submissions, recovery
   attempts and recovery clues, added time); the final closes, its timer is cleared, it may be opened again, and content
   becomes editable again. Hints, cheating records and room scores are room-phase data and stay.
7. **Reset one team**: all of the team's progress is deleted; the team, its account and its group stay, and its
   device is signed out ("An organizer signed your team out.").
8. **Reset the whole event**: all progress of all teams is deleted, the event returns to *Not started* with all
   rooms closed and results unpublished; accounts, teams, groups, the planned team count, rooms, managers, clue keys
   and all content stay. Requires typing **RESET** and the admin's own password (a wrong password changes nothing).
9. **Snapshots**: every reset first saves a snapshot of all rows it deletes and of the event state, in the same
    transaction — if the snapshot fails, nothing changes. The admin can take a snapshot at any time, list all
    snapshots and download each as a JSON file. Snapshots are never deleted; restoring one is not supported.

## 17. Evidence: the team's finished rooms

Teams need their clues in the final, so after a room is over a team can look back at it.

1. **Room finished (for one team).** A room is finished for a team when the round in which the team was
   assigned to it is over: a later round is current, or it is that round and its timer shows Time's up. The
   assignment is the team's room for that round — the rotation, or an admin room exception if one existed for
   that round. Every room is finished once rooms are finished (round 5) or the final has opened. An AI mission room is
   also finished for a team as soon as it is evaluated (rule 19.6). Before that the team page works exactly as before
   (answer form, receipt, answer history).
2. **Evidence card.** For each finished room the team home shows an **Evidence** card with the team's latest
   technical output, its latest clue answer with the result (Correct / Incorrect), and the room clue:
   - when the team has **collected** the room's clue (from a correct answer, an organizer's manual clue or the
     recovery challenge): the **official clue** — the room's current clue key, not the team's own text;
   - otherwise "Clue not collected", and "You may recover it in the final." when it is the one missing clue of a
     team with 3 clues before the final is over.
   A room the team never answered shows "No answer submitted"; a manual clue for it is still shown.
3. **Who may receive a key.** Rule 5.7: only the team that collected the clue, only for a finished room (or any
   collected room once the final has opened). The key of a room whose clue the team has not collected is never
   sent.
4. **Hidden on screen.** Clues show as "••••••••" until the eye button is pressed ("Show clue" / "Hide clue"). A
   shown clue hides itself again after 15 seconds, when the tab is hidden and when the team moves to another page.
   **Copy** copies the clue without showing it. The technical output and the team's own answer are shown normally.
5. **Final.** The final page lists the four rooms in their colours under **Your evidence** with the same hidden
   clue display (collected clues only), in every final stage except "locked" and "closed". A clue won in the
   recovery challenge appears there at once.
6. **Delivery.** The evidence is part of the team home and final responses (no extra requests from the browser,
   a fixed number of database statements whatever the number of teams).
7. **Key changes.** If a room's key changes later (possible only until the final opens), the shown clue follows
   the current key.

## 18. In-browser terminal

Some rooms give teams a Linux terminal on the website. Linux runs inside the team's own browser; nothing runs on
the server, and the terminal has no internet. The room's answer form (technical output and clue answer) is unchanged:
the terminal only helps produce the technical output.

1. **Which rooms (setting).** Each room has a **Terminal enabled** setting that only the admin changes, on the
   Content page, at any time (it is recorded in the audit log). On a fresh setup it is on for the Linux room only.
   Nothing in the system is tied to the Linux room itself: any room with the setting on gets the terminal. The admin
   board warns "Room <name> has the terminal enabled but no terminal files." until the room has at least one file
   or a server package (item 6). The Linux terminal is also a room **activity** (`LINUX_TERMINAL`), set by the admin
   on the Content page next to the AI mission (rule 19); a room has one activity, and a fresh setup turns it on for the
   Linux room. A room with the activity shows the team form of item 7 instead of the answer form (technical output +
   clue answer); a plain submission to it is refused ("This room records its result from its activity; there is no
   answer form."). A room without an activity keeps the answer form of rule 4, with or without a terminal.
2. **Terminal files (content).** The room's manager (or the admin) keeps a list of text files on the room board under
   **Terminal files**:
   - **Path**: relative to the team's home directory, e.g. `logs/lock.log` (the team finds it at `~/logs/lock.log`).
     1–120 characters from `A–Z a–z 0–9 . _ - /`; no leading `/`, no `..`, no empty or `.` part.
   - **Content**: UTF-8 text (pasted, or read from a `.txt`/`.log` file in the manager's browser).
   - **Limits**: at most **20 files**, **2 MB per file**, **5 MB per room** in total; two files cannot share a path.
   - Files can be added only while the room's terminal is enabled, and like all content they are **read-only once
     the final opens** ("Event content is read-only after the final opens.").
   - Every add, change and delete is recorded in the audit log with the path and size, **never the content**.
3. **Who receives the files.** A team receives a room's terminal files only when **all** of these hold:
   - the room's terminal is enabled;
   - the team is not disqualified;
   - a room round (1–4) is current and its timer is **Assigned, Running or Paused** (not Time's up);
   - the room is the team's room for that round (its rotation room, or the room an admin room exception sent it to).

   In every other case — another group's team, another round, Time's up, rounds 0 and 5, a disqualified team, or a
   terminal that is off — the request is answered as if the room did not exist ("Terminal files not found."). The
   room's manager and the admin can always load their room's files (**Test terminal** on the room board). The
   emulator and the Linux image contain no event data and may be downloaded by anyone signed in.
4. **On the team's screen.** On a laptop (900 px wide or more) the terminal sits left of the answer form; on a phone
   the form stays as before with "The terminal works best on a laptop" and a button to open the terminal anyway.
   **Start terminal** downloads the emulator (while the round is Assigned the page already downloads it in the
   background), boots Linux and copies the files into the home directory. **Restart terminal** starts again from the
   room's files (asks first). **Use selection as technical output** copies the selected terminal text into the
   Technical output field; the team still reviews and submits as usual. Refreshing or leaving the page ends the
   terminal (the browser asks first while it runs).
5. **Time's up.** When the round's timer shows Time's up (or the next round starts), the terminal shows "Time is up"
   and accepts no more input. If the admin adds time, **Restart terminal** works again. Changed files reach a team the
   next time its terminal starts.
6. **Server package.** Instead of text files, the room's manager (or the admin) may upload **one ZIP** on the room
   board under **Server package**. Its files are installed from the root of the team's Linux (`srv/rami/logs/a.log`
   becomes `/srv/rami/logs/a.log`) with the owners, groups and modes of an optional `devfest-manifest.csv`
   (`path,owner,group,mode[,mtime]`; defaults `root:root`, `0644` for files, `0755` for folders; the optional UTC
   `mtime` such as `2026-03-14T09:26:00Z` sets the modification time of files and folders, blank keeps the ZIP entry's;
   the manifest itself is not installed, anywhere). Format, limits and an example: [LINUX-ROOM.md](LINUX-ROOM.md).
   - **Validation.** Every problem is reported at once and nothing is stored while there is one: upload at most 25 MB,
     at most 5000 entries, at most 20 MB unpacked (counted from the bytes really inflated, never from the sizes the
     ZIP claims); no `..`, absolute or backslash path and no NUL; nothing under `bin`, `sbin`, `lib`, `usr/bin`,
     `usr/sbin`, `dev`, `proc`, `sys`, `mnt` and not `etc/passwd` or `etc/group`; valid owner and group names; valid
     modes; valid dates (UTC, years 1970 to 2099); no manifest row that matches nothing.
   - **Storage.** The server builds one gzip-compressed ustar archive (numeric ids; owners other than `root` get user
     ids from 2000 and groups group ids from 2000, in name order) and keeps it in a table of its own with its SHA-256,
     file count, unpacked size, owners, uploader and time. The archive is never part of a board or list response.
     Uploading, replacing and removing are audited with the hash and counts only, need the room's terminal to be
     enabled and follow the content edit lock (rule 13: read-only once the final opens).
   - **Delivery.** A team's terminal downloads the archive from `GET /team/rooms/{id}/terminal-bundle` under exactly
     the gates of item 3 (every other case answers 404 "Terminal files not found."); the room's manager and the admin
     may always download it (**Test terminal**). The response carries `ETag: "<sha256>"` and `Cache-Control: private,
     no-cache`; `If-None-Match` is checked after the gates and answers `304` without loading the archive.
   - **In the terminal.** Before the shell is handed over, and out of the team's sight, the page places the archive on
     the 9p share and runs `gzip -dc /mnt/<bundle> | tar -xf - -C /` (this BusyBox `tar` has no `-z`), appends the
     owners to `/etc/passwd` and `/etc/group`, installs the **extra tools** (below) into `/usr/local`, sets the room's
     **terminal clock** if there is one, removes the setup files and changes to `~`. A folder is written after
     everything below it, so extracting files never changes a folder's date. A failure shows a clear
     error ("The room's server could not be set up in this terminal. …") instead of a half-built shell. The banner is
     "Rami's server is mounted. There is no internet in this terminal."
   - **Extra tools.** A room with a package also gets `file`, `base64`, `bzip2`, `bunzip2`, `bzcat`, `zip` and **GNU
     find** (and xargs): official Alpine Linux x86 (musl) packages verified against Alpine's signed index and pinned by
     SHA-256 (`infrastructure/build-terminal-tools.py`, sources and licenses in LINUX-ROOM.md section 10), shipped as one
     static file of the site (`/terminal/devfest-tools.tgz`, under 2 MB, not part of the package and not counted in its
     20 MB), unpacked into `/usr/local` whose `bin` leads the shell's `PATH`. A download or installation failure shows a
     clear error and no shell. A room with a package uses an 80 MB guest (others keep 64 MB); rooms without a package are
     unchanged.
   - **Terminal clock.** An optional UTC date-time on the package card (1970 to 2099), editable without uploading the
     package again, saved with it (replacing the package keeps it, removing the package removes it), audited, and under
     the content lock (rule 13). When set, the setup runs `date -s`, so `find -mtime` gives the same result on any event
     day; the clock then runs normally and a restart starts again from it. Empty = real time. Only the room's manager and
     the admin set it (anyone else gets a 404); teams receive it only in the gated answer that describes their package.
   - While a package exists, teams get the package and **not** the text files; a room without a package keeps the
     text-file behaviour of item 2 to 5 unchanged.
7. **Team form (rooms with the `LINUX_TERMINAL` activity).** On the same page as the terminal the team sees five
   fields: **PATH**, **USERNAME**, **FILE_SIZE**, **PATTERN** and **Final clue** (each at most 500 bytes, blanks
   allowed). There is no separate encrypted message: the target file holds one encrypted clue, and the team enters its
   decryption as the Final clue (V17 removed the former message field; the crypto final of rule 10 is a different thing).
   - **Save** stores the current values, as often as the team likes (each request carries an Idempotency-Key: a retry
     of the same request is answered again, a different request with the same key is refused with 409, and a stale
     retry never overwrites a newer save). **Final submit** asks first ("This cannot be undone"), carries the current
     values and locks the form for good. The state is always the server's: a refresh or a terminal restart shows the
     same values and the same status.
   - **When it is accepted.** Only from the team assigned to the room this round (every other team, round or room: 404
     "Form not found."), while the round's timer is running and the room is open, the team is not disqualified and the
     form is not submitted. Otherwise the same refusals as a room submission: "The timer has not started yet.", "The
     round is paused.", "Time is up for this round.", "This room is closed.", "Your team has been disqualified. Contact
     the organizer.", and "Your team has already submitted its final answer.". The team's own form stays readable
     (locked) after Time's up.
   - **What the team sees.** Only its own values and the status **Not saved yet / Saved / Submitted**: never whether
     an answer is right, a score or a key. The room has no receipt for the team, and its evidence card says
     "Submitted"; the room's clue shows in the evidence like any collected clue.
   - **The final answer.** On Final submit, or at Time's up for a team that has something saved and did not submit
     (done inside the round's end — End round, removing the time left, or the next round — in the same transaction,
     with no scheduler; a disqualified team is skipped), the server records the answer through the ordinary
     room-submission path, exactly once even if the two race: **technical output** = a server-made summary of the five
     values, **clue answer** = the Final clue. The room is then finished for the team, boards and evidence keep working,
     the manager judges the technical output as for any room, and the Final clue gets the automatic clue check of
     rule 5 against the room's clue key: a correct one collects the room's clue as a ROOM clue (and a later key change
     re-checks it like any answer), a blank or wrong one collects nothing. Save never bumps the event revision; Final
     submit and the round's end do, broadcast after commit.
   - **Reset and snapshots.** The forms and their request keys are team progress: resets delete them (after the
     snapshot, which includes them).
8. **Organizer dashboard and scoring (rooms with the `LINUX_TERMINAL` activity).** A separate page,
   `/event/room/{id}/dashboard`, linked from the room board, for the room's manager and the admin only: a member, another
   room's manager, an unknown room and a room without the activity all get the same 404 ("Dashboard not found."),
   because the page carries the answer keys.
   - **Answer keys**, set on that page: PATH, USERNAME, FILE_SIZE and PATTERN. Each accepts several values, one per
     line (at most 20 values of 500 bytes each). The Final clue has no key here: it is checked against the room's clue
     key. **An empty key means the item cannot be awarded automatically** and the admin board warns ("Room <name> has no answer key for …"), without
     blocking anything. Keys are content: read-only once the final opens (rule 13). They never reach a team; saving
     them is audited without their text.
   - **Comparison.** Every value is trimmed. PATH also ignores one trailing slash; PATTERN also ignores one pair of
     surrounding quotes; FILE_SIZE ignores case; USERNAME is case-sensitive. The Final clue uses the room's ordinary clue key and the
     normalizer of rule 5.4. A blank answer never matches.
   - **Points.** 3 for each correct PATH, USERNAME, FILE_SIZE and PATTERN, and 3 when the **Clue** (the Final clue, by the
     same clue check that collects the room's clue) is correct: five items, **15 points** at most. Scores are **computed on every read** from the stored
     forms, the current keys and the overrides; no total is stored, so a changed key or clue key changes the table at
     once. The event score uses them as the Linux room's base (rule 8.1).
   - **The table** shows every active team (all groups, in every round) with its status (**Not started / Saved /
     Submitted**), its values with a tick or cross per item, the total out of 15 and the submit time, sorted by total, and
     refreshes every 5 seconds while open. A form that is only saved is scored too (it is submitted at Time's up).
   - **Overrides.** On each of the five items the organizer chooses **Automatic**, **Award** or **Deny**; an overridden
     item is marked "Overridden", and going back to Automatic clears it. Every change is audited (room, team, item and
     decision, never an answer). Overrides work at any time, also after the final opens. Overriding the fifth item (Clue) changes
     its points only: it **never grants or revokes the room's clue**, which stays with the Final clue's check and the
     manager's ordinary clue verdict (rule 5.6).
   - **Export and Test terminal.** **Export CSV** gives the table (team names and values go through the formula-safe
     cell helper). **Test terminal** starts the terminal with the room's current package.
   - Reset and snapshots: overrides are team progress (deleted by resets, saved in the snapshot first); answer keys are
     content and stay.

## 19. AI mission room: SENTINEL (stages, pass score, clue)

A room can run an **activity**: the **AI mission** replaces the answer form (the Linux terminal activity, rule 18.1,
does not). Its story is *SENTINEL*, an AI that Rami, a journalist, was investigating the night he vanished and that
someone sabotaged; the team repairs its three systems. The mission has **10 stages** in three missions, worth **100
points** in all. A team whose total reaches the room's **pass score** gets the room's clue; the system records the
result itself, so the team never types a clue answer there.

1. **Which rooms (setting, D1).** Each room has an **AI mission activity** setting that only the admin changes, on the
   Content page, at any time (audited; it is stored as the activity `AI_QUIZ`). On a fresh setup it is on for the AI
   room only; nothing in the system is tied to the AI room itself. For such a room the team page shows only the
   activity: no technical output and no clue answer field (a room submission sent to it anyway is refused: "This room
   records its result from its activity; there is no answer form.").
2. **Content (D2).** The room's manager (or the admin) imports the whole mission from **one JSON file** (format in the
   README; the shipped file is `docs/ai-room/sentinel-content.json`) and exports it the same way. The file holds the
   story (the opening text and the "restored" and "unstable" endings), one intro per mission, and for every stage a
   title, a **Field note**, the instructions, its worth in points and its data. The stages, their order and their
   kinds are fixed:

   | Mission | # | Stage | Worth | The team | Scored |
   |---|---|---|---|---|---|
   | PREDICT | 1 | Outlier Hunt | 10 | runs IQR or Z-score with a threshold, any number of times, then submits one | the points of that configuration |
   | | 2 | Feature Check | 10 | marks each feature of the Exit gate prediction as use or drop | worth × correct ÷ features |
   | | 3 | Tune & Deploy | 10 | runs up to 4 different model settings (train and validation accuracy), deploys any one | the points of the deployed setting |
   | SEE | 4 | Nearest Vehicle | 10 | answers KNN at k=1 and k=5 and which k is more reliable | class at k=1, class at k=5, more reliable k |
   | | 5 | Camera Accuracy | 10 | counts TP, FP, FN, TN, gives precision and recall (±0.01) and the metric that avoids missed vans | each part |
   | | 6 | Group the Frames | 10 | steps K-Means (Assign, Update) and gives the van cluster's size and final centroid (±0.01) | size, centroid |
   | NOOR | 7 | Route Planner | 10 | expands A* from Library to Gate 3, gives the order, the route and its cost | order, route with cost |
   | | 8 | Threat Filter | 10 | computes the Threat and Normal values of a message (within 1%) and the class | threat value, normal value, class |
   | | 9 | Rule Engine | 10 | fires rules by forward chaining and gives the final conclusion | the conclusion |
   | | 10 | RAG Fix | 10 | asks NOOR with up to 3 different chunk size and top-k settings, locks one | the points of the locked setting |

   Answers that can be computed are **derived by the server** from the stage's data (nearest neighbours, confusion
   counts, K-Means, A* with its tie-break rule shown on screen, Naive Bayes, forward chaining), so an author cannot
   mistype them; so are the readings an Outlier Hunt run flags (each axis on its own, flagged if outside on x or on y;
   IQR with Tukey's hinges and fences Q1 − k·IQR and Q3 + k·IQR, Z-score with the population standard deviation; the
   Field note and the Field Guide state this); only the choices that are judgments (configuration tables, which features to use) are written in the
   file. A file is read whole: the page shows the server's headline ("The file has N problems.") and **every problem
   under it at once**, one line each with its stage and field ("Stage 2 · maxPoints: must be a number."), with a
   **Try again** button; nothing is saved when there is any, the stages must add up to exactly 100 points, each scoring table to its stage's worth, and the
   fixture's traps must hold (the nearest vehicle is an outlier so k=1 is wrong; greedy search by estimate alone finds a costlier route than A* (Route Planner: A* expands Library, Lab, Dorm, Admin, Garage, Gate 3, cost 15, with no two frontier places ever sharing an f value; greedy pays 47 through the Cafeteria and Gym);
   estimates never exceed the real cost; the K-Means answer is a clean value; exactly one offered conclusion follows
   from the rules; the endings contain no number). The file holds no clue: the clue is the room's clue key.
   **Locks**: an import (which replaces everything) is refused once any team has run or saved anything in the room;
   like all content, the mission is read-only after the final opens. Audits name the room and the number of stages,
   never the content.
3. **Pass score (setting).** Each AI mission room has a pass score from 0 to 100 points (default 60), set by the
   room's manager or the admin on the mission page (or in the import file). It is **locked as soon as the first team
   of the room is evaluated** ("A team in this room has already been evaluated; the pass score can no longer
   change."), so every team is judged against the same number.
4. **Timing (D3).** The mission uses the room's normal round timer (the admin sets the round to 40 minutes for it);
   there is one countdown and pauses are respected. All ten stages are open at once, in any order, from the moment
   the timer starts. Nothing is accepted before the timer starts, while it is paused ("The round is paused."; the
   screen stays, the inputs are frozen), at Time's up, after the team's evaluation, outside the team's assigned
   round, by another room's team, by a disqualified team, or while the room is closed. Added time extends the round.
   Another team, or a room that does not run the mission, gets "Activity not found." (404), as for an unknown room.
   Refreshing or signing in again resumes from the server: the saved stages and every experiment already run come back.
5. **Experiments, submitting and the lock (D4).** Stages 1, 3 and 10 have experiments: the team picks a
   configuration and the server returns **that run's result only** (the flagged readings, the accuracies, the
   retrieved chunks and NOOR's reply), never the table of all of them. Repeating a configuration is free; stage 3
   allows 4 different configurations and stage 10 allows 3 ("You have used all the experiments of this stage."),
   enforced by the server under the team lock. Stages 4 to 9 are worked in the browser from the data the server
   sends. Each stage is **submitted once** after a confirmation and then **locks** and shows only "Saved". Saving
   uses an Idempotency-Key: a retry with the same key and the same answer returns the same receipt; any other answer
   later is refused ("This stage is already saved."). A malformed answer is refused (400) and locks nothing. An
   unsubmitted stage scores 0.
6. **Scores and evaluation (D5, D6).** The score of a stage is computed by the server when it is submitted and stored.
   The room total is the sum of the stages (100 when every answer is right). Each team is **evaluated exactly once**
   per room:
   - when it submits its **last stage**, in the same request; or
   - at the **round's end** for every team assigned to the room that has not finished (not disqualified teams): when
     the admin presses **End round** (also after the timer ran out by itself), removes the time left, or moves to
     the **next round** after the timer was started. There is no scheduler: if the timer runs out by itself, the
     unfinished teams see no result until one of these actions.

   A total **equal to or above** the pass score passes (60 passes at a pass score of 60). The result goes through the
   ordinary room-submission path: a submission with the server's progress line as its technical output ("AI mission ·
   stages 7/10 submitted", organizers only), the fixed answer "AI mission result" and a **CORRECT** (pass) or
   **INCORRECT** verdict; a CORRECT one collects the room's clue as a ROOM clue. Both triggers run under the event
   lock and the evaluation table allows one row per team and room, so a race between the last stage and the round's
   end records one result and the clue once. The room then counts as **finished** for the team (its evidence shows at
   once), and every screen refreshes (the revision is bumped and broadcast after the commit). The room's manager can
   still **override** that verdict like any other (Accept / Reject); a later clue key change never re-checks it, and
   the clue shown follows the current key.
7. **What the team sees (D8).** Never a point, a total, the pass score, whether anything is right, the scoring tables
   or a derived answer, in any state: the stage data, the result of each run it made, and "Saved" for a submitted
   stage. Before the timer starts only the opening text is shown. Once evaluated:
   - **passed** (it holds the room's clue): "SENTINEL RESTORED. All systems are back online. Recovered record:" and
     the clue in the usual hidden clue display (eye toggle, Copy; rule 17.4), on the activity and in the room's
     evidence;
   - **not passed**: "SENTINEL UNSTABLE. Some systems could not be restored. Your team did not reach the required
     score." (the endings come from the file and never carry a number).

   The mission result is not one of the team's receipts or answer history. A room with no mission content shows
   "The mission is not ready yet." instead of the stages.
8. **Not ready = blocking (admin).** A room that runs the AI mission without a clue key, or without mission content,
   is a **blocking alert** on the admin board ("Blocks the timer"). While one exists, **Start timer** asks "The AI
   mission is not ready. Start anyway?" and the server refuses the start unless the admin confirms **Start anyway**
   (audited as "round:N anyway").
9. **The SENTINEL page and results (D9).** The room's manager and the admin open **SENTINEL** from the room board
   (`/event/room/{id}/mission`; everyone else, members and other rooms' managers included, gets a 404 or 403). It holds
   the mission file (import with a confirmation that names the file and its stage count, export, the ten stages with
   their worth and the total of 100), the **pass score**, the **Print Field Guide** link and the **live results**.
   - **Import errors** are shown as a list, one line per problem with its place in the file; nothing is changed.
     Import and pass score show why they are locked (rule 19.2, 19.3).
   - **Field Guide** (`/event/room/{id}/mission/guide`): one A4 page of the ideas behind the stages (Z-score, IQR
     fences, leakage, overfitting, KNN, confusion metrics, K-Means, A* with f = g + h, Naive Bayes, forward chaining,
     RAG). It is the same for every room, holds no data, answer or score, and prints without the application chrome.
   - **Live results** refresh every 5 seconds while the page is open (the server answers with the same 6 SQL
     statements whatever the number of teams): the pass score (and whether it is locked); per team the points of each
     saved stage, the total out of 100, its **status** (In progress / Passed / Not passed), whether its **clue is
     granted** now (an override can differ from the status), the number of saved stages, when it last saved and when it
     was evaluated (**Finished**). Teams that ran or saved anything are listed. Ranking inside the room: total
     (highest first), then the earlier last saved stage; disqualified teams are listed last without a rank.
     **Export CSV** has the same columns (team names are made formula-safe).
   - **Manual verdict.** Each team's result is its submission on the room board, where the manager's Accept / Reject /
     Automatic (rule 5) works as for any room; the results then show the clue as granted or not while the status
     keeps what the score said.

   The event score uses the total (rule 8.1): the base is that total × 0.15.
10. **Resets.** Resetting a team or the event deletes its experiments, stage submissions and evaluation with the rest
    of its progress (they are in the snapshot); the mission and the pass score stay.
11. **Room score.** The read-only service methods (`SentinelScoreService.roomScore` for one team, `roomTotals` for a
    room) return a team's total (the sum of its saved stages, maximum 100). The event score uses it: the AI room's base
    is that total × 0.15 (rule 8).


## 20. Problem Solving room (Codeforces contest, results, clue release)

The Problem Solving room's contest runs on **Codeforces**; the site never calls Codeforces. It holds only the contest
link, the results an organizer types in from the Codeforces standings, and the release of the room's clue. Teams have
no answer form in this room.

1. **Which rooms (setting).** Each room has a **Contest results activity** setting that only the admin changes, on the
   Content page, at any time (audited; the same single "activity" choice as rules 18.1 and 19.1, so choosing it
   replaces the AI mission or the Linux terminal). On a fresh setup it is on for the Problem solving room only; nothing in
   the system is tied to that room's code. A room submission sent to such a room is refused: "This room records its
   result from its activity; there is no answer form."
2. **Contest link (setting).** The room's manager or the admin sets one **https** address (http, `javascript:`, a
   relative link, spaces or a user name and password in it are refused; at most 500 bytes) and may remove it. It is
   content: read-only after the final opens ("Event content is read-only after the final opens."). Saving or removing
   it is audited with the host only (a link may carry an invitation token) and changes the event revision, so team
   screens refresh. The admin board warns **while the link or the room's clue key is missing** (non-blocking).
3. **What a team sees.** Only the team assigned to the room in the current round (the rotation, or the admin's room
   exception for that round), never a disqualified team, and only while the timer is **RUNNING or PAUSED**, sees the
   link: before the timer starts it sees "The contest link appears here when the timer starts."; after Time's up the
   link is gone. Next to the link it reads "Your results are being reviewed by the room organizer." After the clue is
   released it reads "Clue unlocked" and shows the room's clue in the usual masked clue card (the team's evidence for
   the room says the same). A team that is not assigned to the room this round gets a 404 for the contest. **No member
   response ever contains a solved count, points, penalty, place or eligibility, and the clue is in no member
   response before its release.** A team that never gets a release simply has no clue from this room; the
   Recovery Challenge (rule 9) applies.
4. **Results (organizer page).** `/event/room/{id}/contest`, linked from the room board, for the room's manager and the
   admin; everyone else, members and other rooms' managers included, gets a 404. Teams are grouped by the **round** in
   which their group visits the room. For each active, not disqualified team the organizer ticks the problems A to E it
   solved and types one **penalty** in whole minutes (0 or more, at most 100000; 3.5 is refused), copied from the
   Codeforces standings (the contest's own tie-break; it never adds time to a team and is not a deduction of rule 8), and saves **per team** (audited, allowed at any time). Saving does **not** change the event
   revision. Inactive and disqualified teams are not listed and take no place.
5. **Scoring (computed on every read, never stored).**
   - **Problem points:** A = 2, B = 3, C = 3, D = 2, E = 2 (at most 12).
   - **Placement** inside one round, among the teams with a saved result: more problems solved first, then the lower
     penalty. Equal solved count and penalty share a place and the next place skips them (1, 1, 3, 4).
   - **Bonus by place:** 1st = 3, 2nd = 2, 3rd = 1, otherwise 0. Two teams tied first therefore get 3 and 3, the next
     team 1 and the one after it 0. A team that solved nothing gets no bonus whatever its place.
   - **Total** out of 15 (12 + 3). **Eligible** for the clue with at least **3 solved** problems.
   - The page shows solved, points, bonus, total, place, eligible and released per team, sorted by place (teams
     without a saved result last), refreshes every 5 seconds while open and has **Export CSV** (team names are made
     formula-safe). The read-only service methods (`ContestScoreService.roomScore`, `roomTotals`) return a team's
     room score (points, maximum 15), which the event score uses as the room's base (rule 8.1).
6. **Clue release.** **Reveal clue** (one team) and **Reveal to all eligible** (one round) are done by the room's
   manager or the admin.
   - Refused with a 409 and a clear message when the team is not eligible ("This team solved 2 of the problems; at
     least 3 are needed for the clue.", or no saved results), when the team is disqualified, or when the room has no
     clue key ("Set the clue key for this room before revealing its clue."). A round with nobody eligible waiting is
     not an error; nothing happens.
   - A release is **idempotent and race-safe**: one transaction under the usual lock order (event state, teams in id
     order, room); a repeat, or a single release racing a whole-round release, finds the release and changes nothing
     (no second submission, clue, audit row or revision bump). The table `contest_release` has one row per team and
     room, is append-only, and the primary key backs this up.
   - It records the team's result through the **ordinary room-submission path** as a **CORRECT** submission whose
     technical output is a summary the server makes ("Contest results · solved 3/5 (A, B, C) · problem points 8/12 ·
     penalty 50 min · place 2 · bonus 2 · total 10/15", visible to organizers only), which adds the manager's clue as
     a **ROOM** clue and finishes the room for that team. The submission carries the team's own round (1 to 4), also
     when released later. One revision bump per call that released anything, broadcast after the commit. Audited
     ("EVENT_CONTEST_CLUE_RELEASED").
   - The manager's ordinary clue verdict (Accept / Reject / Automatic, rule 5) still works on that submission and is
     the admin's way to handle exceptions; a key change never re-checks a release (it is not a clue answer).
   - **Editing results after a release never changes a clue.** If the edited results no longer reach 3 solved, the row
     shows "Released, but no longer eligible" and the clue stays.
7. **Resets and snapshots.** Resetting a team or the event deletes its results and release (after a snapshot that
   holds them) with the rest of its progress, like the collected clue; the contest link stays (content).
