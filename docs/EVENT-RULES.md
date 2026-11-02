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
- Each room holds one **clue key** (the answer teams must find) and one **recovery challenge**
  (a question plus its answer, used only in the final; see rule 9).

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

## 3. Rounds

- The event round is one of: **Not started** (0), **Round 1 of 4** … **Round 4 of 4**, or
  **Rooms finished** (5).
- Starting a round (1–4) starts its countdown on the server. The round length defaults to 25 minutes and
  can be set from 1 to 180 minutes. The countdown is information only: **nothing closes automatically**
  when it reaches "Time is up". The admin closes rooms and advances rounds.
- "Next round" asks the server for one specific round number, so pressing it twice by accident cannot
  skip a round, and repeating the current round does not restart its timer.

## 4. Room submissions

A team may submit to a room only when **all** of these are true, checked in this order:

1. A round (1–4) is running — otherwise "Rooms are not running right now."
2. The room is the team's scheduled room this round — otherwise "This is not your team's room in the current round."
3. The room is open — otherwise "This room is closed."

- A submission contains the **technical output** (up to 4000 bytes) and the **clue answer** (up to 500 bytes).
- Every submission is kept, and a team may resubmit as often as it likes while the conditions hold
  (up to 20 attempts per minute per team).
- Each accepted submission returns a **receipt** with its id and server time. If the network fails, the
  team's screen shows "Retry"; retrying sends the identical request and returns the **same** receipt, so
  nothing is ever counted twice.

## 5. Clue checking

The system compares the team's clue answer with the room's clue key after normalizing both:
leading and trailing spaces are removed, every run of spaces/tabs/newlines becomes one space, and
letters become upper case. **Punctuation is kept.** An empty clue key never matches.

| Clue key | Team answer | Result |
|---|---|---|
| `WHITE VAN` | `  white   van ` | Correct |
| `WHITE VAN` | `White Van` | Correct |
| `WHITE VAN` | `WHITE-VAN` | Incorrect (punctuation differs) |
| `WHITE VAN` | `WHITEVAN` | Incorrect (space missing) |
| `+970-59-881` | `+970-59-881` | Correct |
| `+970-59-881` | `97059881` | Incorrect |

The result is decided when the submission arrives and is never recalculated, even if the clue key is
edited later.

## 6. Judging

- For every team in their room, the manager records a status: **Pending**, **Passed** or **Failed**,
  a hint count (0–50) and an optional note (up to 1000 bytes).
- Judging is separate from clue collection: Passed or Failed **never** blocks a team from the final.
- If two people judge the same team at the same time, the second save is refused with
  "Someone else changed this judgement. Reload and try again." Saving exactly what is already stored is
  accepted silently. Every real change is recorded in a judgement history.
- The hint count is informational. Minutes are added only by **penalty entries** (rule 8). The room board
  warns the manager when the hint count and the number of active Hint penalties differ.

## 7. Collected clues

- A team **collects** a room's clue the first time it submits the correct clue for that room.
- A later wrong answer never removes a collected clue.
- A clue can also be collected in the final through the recovery challenge (rule 9).
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
   first one out at its next request (at most about 10 seconds later), and it shows: "Your team signed in on
   another device. Only one device per team can be signed in." The latest sign-in always wins.
2. Admin and room manager accounts may stay signed in on several devices.
3. The teams page shows, per team, whether its device is **signed in** (seen in the last 2 minutes) and when it
   was **last seen**. **Sign out team** signs the team's device out; it shows "An organizer signed your team
   out." and the team can sign in again.
4. A submission that was still waiting to be sent when a device was signed out stays saved on that device and
   can be retried after signing in again on it.
