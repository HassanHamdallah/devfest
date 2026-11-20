# Final recovery: organizer guide

For the organizers only. It holds every answer of the recovery levels: do not show it, print it for a team or leave it open
on a shared screen. The same answers are on the admin page **Recovery solutions** (Final reports, top right); both are built
from `docs/final/last-shipment-content.json`, so they match what the server checks. Rules: `docs/EVENT-RULES.md` section 9.

## 1. Who plays what

K is the number of room keys a team holds **when you open the final**. It is recorded once; a key granted or taken back
later does not change a team's path.

| K (keys held) | Recovery path | Then |
|---|---|---|
| 4 | none | the unlock screen opens at once |
| 3 | **ML** | unlock |
| 2, 1 or 0 | **ML**, then **Arduino** | unlock |

- Levels and their stages are played in order; no stage can be skipped. Checks are unlimited and cost nothing; a wrong check
  only gets the stage's neutral message.
- Finishing the last required level gives the team **every room key it did not hold** and starts its case clock.
  Recovery never restores room points.
- The team's unlock screen shows the keys it recovered (its own only).

## 2. Timings

The final has **one 40:00 timer** for everyone (pause, resume and added time apply to all teams; recovery has no timer of
its own). Each team has its own case clock:

- **S** = the final's active time when the team passed its last recovery level (0:00 for K = 4).
- **Phase 1 (decryption / crypto window)**: from S until the team decrypts its **fourth** file (all four passphrases
  accepted), or until the final ends if it never does. **No clock closes it**: passphrases, the terminal and the commands
  stay available to the end. `phase1Minutes` (15 by default) is only an **estimate** the teams see ("Estimated time: 15
  min"). The window starts at S whether or not the team has opened the case yet.
- **Phase 2 (investigation)**: from the moment the fourth file is accepted until the final ends. The suspect and the
  report can only be sent here. The team's screen moves to Phase 2 at once ("All four files decrypted: the investigation
  is open.") and Phase 1 stays reachable on its other tab.
- A team that has not decrypted all four files by 40:00 never reaches Phase 2 and cannot send a suspect.

Examples (40:00 timer):

| Team | S | Phase 1 (crypto window) | Phase 2 | Suspect possible? |
|---|---|---|---|---|
| K = 4, fourth file at 6:00 | 0:00 | 0:00 – 6:00 | 6:00 – 40:00 | yes |
| K = 4, fourth file at 8:00 | 0:00 | 0:00 – 8:00 | 8:00 – 40:00 | yes |
| K = 4, fourth file at 30:00 | 0:00 | 0:00 – 30:00 | 30:00 – 40:00 | yes, 10 minutes |
| K = 3, ML done at 10:00, fourth file at 18:00 | 10:00 | 10:00 – 18:00 | 18:00 – 40:00 | yes |
| K = 0, both levels done at 30:00, fourth file at 39:00 | 30:00 | 30:00 – 39:00 | 39:00 – 40:00 | yes, 1 minute |
| any team with three files at 40:00 | any | until the end | never opens | **no**: 11.25 points at most |

A late recovery no longer closes anything: a team that finishes its recovery at 30:00 can still decrypt and investigate
until 40:00. It simply has less time.

**Scoring**: time points count the active minutes t from the final's start to the suspect submission (pauses excluded), for
every team: t ≤ 25 ("full until") → 15, otherwise 40 − t. **A wrong suspect gets half of the time points** the correct one
would have got at that moment:

| Submitted at | Correct suspect | Wrong suspect |
|---|---|---|
| 10:00 | 15.00 | 7.50 |
| 20:00 | 15.00 | 7.50 |
| 25:00 | 15.00 | 7.50 |
| 25:30 | 14.50 | 7.25 |
| 30:00 | 10.00 | 5.00 |
| 38:00 | 2.00 | 1.00 |
| 40:00 | 0.00 | 0.00 |

Clue points (3.75 each) and the suspect's 10 points are unchanged: a perfect final is 40, a wrong suspect at 20:00 is
15 + 0 + 7.50 = 22.50.

How long a level takes is not measured with real participants yet. The prototype's own estimate for the **ML level is 15
to 25 minutes**; there is no estimate for the **Arduino level**, which is a longer program with six interacting problems.
A team with 0 to 2 keys needs both levels before its case clock starts, so many of them will not reach the investigation
phase in 40 minutes. Rehearse the levels with a few people before the event and decide in advance whether to add time
(the admin final timer: added time of 1 second to 60 minutes per request, applied to everyone).

Rate limits, per team: one **ML check every 2 seconds** and one **Arduino run every 3 seconds**. The buttons count the wait
down; a run refused for being too soon is not stored.

## 3. Watching the recovery

Admin, **Scores**, tab **Final**, one row per team:

| Column | Meaning |
|---|---|
| Keys held | K, as recorded when the final opened |
| Recovery path | None, ML, or ML + Arduino |
| Recovery level | the stage the team is on (for example "ML · stage 3 of 6", "Arduino"), "Done", or "—" when it needs none |
| Recovery finished | S: the final's active clock when the last level was passed |
| Crypto window | from S (0:00 without recovery) until the moment the fourth file was decrypted (for example 12:30–19:10), or "12:30–end" while the team has not decrypted all four; empty until the recovery is finished |
| Recovery checks | how many ML checks and Arduino runs the team made |

The same data is `GET /api/v1/event/admin/final/board`; `GET /api/v1/event/admin/final/recovery` lists each team's level,
stage, checks and finish; `GET /api/v1/event/admin/final/recovery-solutions` is the solutions page's data.

## 3b. Which key opens which file

Every team gets the four private keys `key_A.pem` to `key_D.pem`; each evidence file opens with exactly one. The card shows
the file's key reference, and the Key Index (in this order) turns the reference into the key letter. The same table is on
the admin page **Recovery solutions** ("Which key opens which file").

| Clue | Encrypted file | Key reference | Key |
|---|---|---|---|
| 1 | ORION_SHIPMENT_NOTICE.enc | NV-4K7 | **C** |
| 2 | SECURITY_ACCESS_REPORT.enc | NV-9T2 | **A** |
| 3 | WAREHOUSE_MOVEMENT_REPORT.enc | NV-1M8 | **D** |
| 4 | STAFF_ACTIVITY_AUDIT.enc | NV-6R3 | **B** |

Key Index: NV-9T2 → A, NV-6R3 → B, NV-4K7 → C, NV-1M8 → D. The `.enc` files and the passphrases did not change: only the
four `clueN_key.rsa` files were re-encrypted for the new public keys (`docs/final/last-shipment/fix-rsa-line-endings.sh`,
then `build-final-package.sh`; upload the new `final-package.zip` as the final's terminal package). A wrong key gives an
OpenSSL error and no `secret_N.txt`.

The team's Phase 1 screen has **no ready-made commands**: only the Command reference (N, X and REPORT_NAME to be replaced
by the team) and the terminal, so a team that picks the wrong key sees the error itself.

## 4. ML Forensic Audit: the answers

Six stages, in this order. The wrong-answer messages are neutral and never say what was wrong.

| Stage | Answer | Why |
|---|---|---|
| 1 Data Leakage | Keep `motion_score`, `access_attempts`, `signal_strength`, `device_type`; **remove `investigation_result`** | it is only known after the investigation, so it cannot exist at prediction time; it is what makes the accuracy 96% |
| 2 Pipeline Repair | Load Dataset, Split Train/Test, **Fit Scaler on Training Data**, Transform Train/Test, Train Model, Evaluate | a scaler fitted on the full dataset has seen the test rows; the step is shown as "Fit Scaler on Full Dataset" until it is after the split |
| 3 Data Cleaning | Missing values: **Impute using training median**; Outliers: **Clip / flag unreasonable values** | zero is not a believable reading, dropping throws data away; -210 is not a possible signal strength |
| 4 Model Selection | **Logistic Regression** (precision 0.79, recall 0.89) | needs recall 0.88 or more **and** precision 0.75 or more: KNN has recall 0.71, the decision tree precision 0.68 |
| 5 Threshold Tuning | any threshold from **0.42 to 0.53** (12 steps of 0.01) | recall at least 0.90, precision at least 0.75 and at most 2 false negatives, together |
| 6 Evidence Extraction | the word **CLUE** (any case, spaces around it ignored) | sort the records by time: #07 21:13 NODE-**C**, #14 21:17 NODE-**L**, #22 21:24 NODE-**U**, #31 21:31 NODE-**E** |

Stage 5 metrics, shown to the team for every slider step and computed by the server from t (rounded to 2 decimals):
recall r = clamp(1.05 − 0.28 t, 0.60, 0.97), precision p = clamp(0.56 + 0.46 t, 0.55, 0.92), false negatives
= round((1 − r) × 20), F1 = 2pr / (p + r). At 0.41 precision is 0.7486 (below 0.75) and at 0.54 recall is 0.8988 (below 0.90),
so those two just miss.

Passing stage 6 shows the token **ML-8842** (cosmetic).

## 5. Arduino Debugging: the answers

The team gets the 89-line program `security_controller.ino` (line numbers as shown in the editor) and one choice at the end of
`loop()`. Every line is editable, but **any change to a line outside the bug lines fails every test** with "A line outside the
faulty logic was changed." Whitespace is ignored everywhere. One global line was added to the designer's program,
`bool entered = false;` (line 14), for the right option.

| Bug | Line(s) | Wrong | Fix | Tests it fails |
|---|---|---|---|---|
| B1 | 40 | `authorized = true;` (after 5 seconds) | `authorized = false;` | T2 |
| B2 | 45 | `if (doorOpen && authorized == true) {` | `if (doorOpen && !authorized) {` (also `authorized == false`, or the two sides swapped) | T3, T4 |
| B3 | 50-52 | `if (!doorOpen) { alarmActive = false; }` switches the alarm off | delete or comment out all three lines (each `//` or empty) | T8 |
| B4 | 56 | `if (motionDetected) {` | `if (motionDetected && doorOpen) {` (or swapped) | T5 |
| B5 | 75 | `authorized = true;` in `resetSystem()` | `authorized = false;` | T6 |
| B6 | after 88 | the missing behaviour | choose the right option (below) | T7 |

T1 "Authorization starts correctly" needs no change. The eight tests are PASS or FAIL only; the level passes when all eight
pass. The token is **RCV-7314** (cosmetic).

### The missing behaviour

A team sees four options, lettered A to D **in its own order** (a different order for each team), so the letter a team
names is not the option id below.

| Option | Code | |
|---|---|---|
| A | `if (!doorOpen && authorized) { authorized = false; }` | **wrong** |
| **B** | `if (doorOpen && authorized) { entered = true; }` and `if (!doorOpen && entered) { authorized = false; entered = false; }` | **right** |
| C | `authorized = false;` | wrong |
| D | `if (doorOpen) { authorized = false; }` | wrong |

**Why A is wrong.** It ends the window as soon as the door is closed while the team is authorized. That is already true
right after the button is pressed, before the door was ever opened, so the 5-second window is cleared at once and a valid
entry can never happen. B fixes this with `entered`: it only ends the authorization after the door was opened during the
window and has closed again. C clears authorization on every pass through `loop()`, so the window never lasts. D clears it the
moment the door opens, so the next pass raises the alarm for a valid entry.

## 6. During the event

- The recovery levels have no hints and no point deductions: a wrong check costs nothing.
- Never show a team the solutions page or this file. The page is admin only (members get 403) and is not in any team menu.
- If a team asks why its check was refused: "Wait N seconds" is the rate limit; "Finish the earlier recovery stages first" means
  it sent a later stage; "This stage is already passed" means a repeat.
- A paused final refuses recovery checks like every other final action ("The final is paused.").
