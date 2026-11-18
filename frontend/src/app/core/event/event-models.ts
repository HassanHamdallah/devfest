// JSON shapes of /api/v1/event (EVENT-CONTRACT §3). Field names mirror the backend records exactly.

export type RoomCode = "AI" | "PROBLEM_SOLVING" | "LINUX" | "HARDWARE";
export type GroupCode = "A" | "B" | "C" | "D";
export type JudgeStatus = "PENDING" | "PASS" | "FAIL";
export type TeamRoomStatus = "NOT_STARTED" | "SUBMITTED" | "PASSED" | "FAILED";
/** A team's status in the final: Not qualified / Recovery / In final / Completed (LOCKED before the final opens). */
export type FinalStage = "LOCKED" | "NOT_QUALIFIED" | "RECOVERY" | "IN_FINAL" | "COMPLETED";
/** The screen the server says the team is on; the page only renders it. */
export type FinalScreen =
  | "LOCKED"
  | "NOT_QUALIFIED"
  | "RECOVERY"
  | "UNLOCK"
  | "MISSION"
  | "CASE"
  | "SUBMITTED";
/** The only two point deductions of the event: a hint (-2) and a cheating violation (-7.5). */
export type DeductionKind = "HINT" | "CHEATING";
export type EventRole = "ADMIN" | "MANAGER" | "MEMBER";
export type EventAccountRole = "MANAGER" | "MEMBER";
/** A manager's clue verdict on one submission; AUTOMATIC follows the room's clue key. */
export type ClueOverride = "ACCEPT" | "REJECT" | "AUTOMATIC";
/** A room's team activity (admin setting); a room without one keeps the plain answer form. */
/** PHONE_POINTS: the Hardware room, points out of 100 added by the manager and a phone number answer (rules §21). */
export type RoomActivity = "AI_QUIZ" | "LINUX_TERMINAL" | "CONTEST_RESULTS" | "PHONE_POINTS";

/** Room codes in position order (0..3) and group codes in index order (0..3). */
export const ROOM_CODES: readonly RoomCode[] = [
  "AI",
  "PROBLEM_SOLVING",
  "LINUX",
  "HARDWARE",
];
export const GROUP_CODES: readonly GroupCode[] = ["A", "B", "C", "D"];

/** Phase of a running round; TIME_UP is computed by the server. Rounds 0 and 5 have none. */
export type RoundPhase = "ASSIGNED" | "RUNNING" | "PAUSED" | "TIME_UP";

/** The round countdown as the server computed it at `serverNow`; the client only counts down locally. */
export interface RoundClock {
  currentRound: number;
  phase: RoundPhase | null;
  remainingSeconds: number | null;
  durationSeconds: number | null;
  serverNow: string;
}

// ---- Shared ----

export interface EventRevision {
  revision: number;
}

/** Single-use credential for GET /event/stream (EventSource cannot send the Authorization header). */
export interface StreamTicket {
  ticket: string;
  expiresInSeconds: number;
}

export interface EventHome {
  role: EventRole;
  roomId: number | null;
  teamName: string | null;
}

export interface ResultsView {
  published: boolean;
  rows: PublicRankingRow[];
}

export interface PublicRankingRow {
  /** Null for a disqualified team, which is listed last and never ranked. */
  rank: number | null;
  teamName: string;
  groupCode: GroupCode;
  /** The four room scores in room order (AI, Problem solving, Linux, Hardware). */
  roomScores: number[];
  finalScore: number;
  total: number;
  finalStatus: FinalStage;
  disqualified: boolean;
}

// ---- Member ----

export interface ScheduledRoom {
  roomId: number;
  code: RoomCode;
  name: string;
  open: boolean;
  /** The organizer sent the team to this room for this round instead of its rotation room. */
  exception: boolean;
  /** The room offers the in-browser terminal (its files come from GET /team/rooms/{id}/terminal-files). */
  terminalEnabled: boolean;
  /** The room's activity replaces the technical-output field (GET /team/rooms/{id}/activity); null = none. */
  activity: RoomActivity | null;
}

export interface TeamBoard {
  teamName: string;
  groupCode: GroupCode;
  clock: RoundClock;
  scheduledRoom: ScheduledRoom | null;
  rooms: TeamRoomCard[];
  cluesCollected: number;
  finalStage: FinalStage;
  finalOpen: boolean;
  resultsPublished: boolean;
  revision: number;
  /** The organizer disqualified the team: it keeps its records but cannot submit. */
  disqualified: boolean;
  /** "Hints used: n of 2": the only deduction information a team ever sees. */
  hintsUsed: number;
  hintsAllowed: number;
  /** One entry per room, in room order. */
  evidence: RoomEvidence[];
}

/**
 * What the team may look back at for one room. Until the room is finished for the team only the code, name,
 * `finished` (false) and `clueCollected` are set. `clueValue` is the room's official clue, sent only for a
 * finished room whose clue the team collected.
 */
export interface RoomEvidence {
  roomCode: RoomCode;
  roomName: string;
  /** A room with an activity (AI mission) never sends the output, answer or result: only whether the clue was unlocked. */
  activity: RoomActivity | null;
  finished: boolean;
  technicalOutput: string | null;
  clueAnswer: string | null;
  clueCorrect: boolean | null;
  clueCollected: boolean;
  clueValue: string | null;
  /** The room's activity evaluated the team. */
  resultRecorded: boolean;
}

export interface TeamRoomCard {
  roomId: number;
  code: RoomCode;
  name: string;
  position: number;
  open: boolean;
  scheduledRound: number;
  scheduledNow: boolean;
  myStatus: TeamRoomStatus;
  clueCollected: boolean;
  lastReceipt: Receipt | null;
}

export interface Receipt {
  id: string;
  roomCode: RoomCode;
  receivedAt: string;
  /** The current result (it can change after a key fix or a manager's review). */
  clueCorrect: boolean;
  /** The current result differs from the result shown when the answer was submitted. */
  updatedAfterReview: boolean;
}

export interface RoomSubmissionRequest {
  technicalOutput: string;
  clueAnswer: string;
}

/** One of the four encrypted evidence files of the case; `report` is sent only once the team decrypted it. */
export interface FinalCaseClue {
  number: number;
  title: string;
  encFile: string;
  rsaFile: string;
  keyReference: string;
  solved: boolean;
  /** Seconds to wait before the next passphrase for this file (five wrong in a row). */
  waitSeconds: number;
  report: string | null;
}

export interface FinalKeyIndexEntry {
  reference: string;
  key: string;
}

export interface FinalSuspect {
  id: string;
  name: string;
  position: string;
  statement: string;
}

export interface FinalMission {
  title: string;
  intro: string;
  phase1: string;
  phase2: string;
}

/** The team's final, always built from the server's state; parts of other screens are null or empty. */
/** The Final Investigation Report: up to four pieces of evidence and an explanation; null where the team wrote none. */
export interface FinalReport {
  evidence1: string | null;
  evidence2: string | null;
  evidence3: string | null;
  evidence4: string | null;
  explanation: string | null;
}

/** One team in the organizers' list of reports (admin only: it carries the suspect's correctness). */
export interface FinalReportRow extends FinalReport {
  teamId: number;
  teamName: string;
  submitted: boolean;
  suspectId: string | null;
  suspectName: string | null;
  correct: boolean | null;
  submittedAt: string | null;
  /** The final's active seconds at the submission. */
  activeSeconds: number | null;
}

/** The final's two phases, decided by the server from the final timer's active seconds. */
export type FinalPhase = "DECRYPTION" | "INVESTIGATION";

export interface TeamFinal {
  status: FinalStage;
  screen: FinalScreen;
  serverNow: string;
  finalStartedAt: string | null;
  timerPhase: RoundPhase | null;
  remainingSeconds: number | null;
  /** On the mission and case screens: the phase, its length in minutes and the active seconds until it ends (null after). */
  phase: FinalPhase | null;
  phase1Minutes: number | null;
  phase1EndsInSeconds: number | null;
  cluesCollected: number;
  /** Seconds to wait before the next unlock try after five wrong answers in a row. */
  waitSeconds: number;
  unlock: { roomNames: string[] } | null;
  recovery: { question: string; attempts: number } | null;
  /** On the mission and case screens. */
  mission: FinalMission | null;
  /**
   * The case screen only: the four files and the key index; the suspects only in the investigation phase and the final's
   * terminal package only in the decryption phase.
   */
  clues: FinalCaseClue[];
  keyIndex: FinalKeyIndexEntry[];
  suspects: FinalSuspect[];
  /** Null while the organizer has not uploaded the final's terminal package. */
  terminal: TerminalBundleInfo | null;
  /** The team's suspect is recorded. */
  submitted: boolean;
  submission: {
    teamName: string;
    status: FinalStage;
    at: string;
    message: string;
    /** The team's own submitted answer and report (null when it submitted none, e.g. time up). */
    suspectId: string | null;
    suspectName: string | null;
    report: FinalReport | null;
  } | null;
  resultsPublished: boolean;
  /** The same per-room evidence as on the team board. */
  evidence: RoomEvidence[];
}

/** The answer to an unlock, passphrase, start or suspect step (never carries the value or a correctness). */
export interface FinalActionResult {
  accepted: boolean;
  message: string;
  waitSeconds: number;
  status: FinalStage;
  screen: FinalScreen | null;
}

export interface UnlockRequest {
  codes: string[];
}

export interface PassphraseRequest {
  passphrase: string;
}

export interface RecoveryRequest {
  answer: string;
}

export interface RecoveryResult {
  attemptId: string;
  correct: boolean;
  stage: FinalStage;
  receivedAt: string;
}

// ---- Manager ----

export interface RoomBoardRoom {
  id: number;
  code: RoomCode;
  name: string;
  position: number;
  open: boolean;
  clueKey: string;
  version: number;
  contentEditable: boolean;
  /** False while the clue key is empty; the room cannot open. */
  clueKeySet: boolean;
  /** The room offers the in-browser terminal; its manager prepares the terminal files. */
  terminalEnabled: boolean;
  /** The room's team activity; for AI_QUIZ its manager prepares the questions and repairs. */
  activity: RoomActivity | null;
  /** The room has no automatic score: its manager types a 0-15 score for each team. */
  manualScoring: boolean;
  /** The room's phone number answer (manager and admin only); empty outside a PHONE_POINTS room. */
  phoneNumber: string;
  phoneNumberSet: boolean;
}

export interface RoomCounters {
  inRoomNow: number;
  pending: number;
  passed: number;
  failed: number;
}

export interface RoomBoard {
  room: RoomBoardRoom;
  clock: RoundClock;
  scheduledGroup: GroupCode | null;
  counters: RoomCounters;
  teams: RoomTeamRow[];
  revision: number;
  /** Null in rounds 0 and 5. */
  arrival: RoomArrivalView | null;
  teamsExpectedNow: string[];
}

export interface LatestSubmission {
  id: string;
  round: number;
  technicalOutput: string;
  clueAnswer: string;
  /** Result at submission time (history). */
  clueCorrect: boolean;
  receivedAt: string;
  /** Result against the current clue key. */
  autoCorrect: boolean;
  override: ClueOverride;
  /** What the team sees: the override if any, else autoCorrect. */
  effectiveCorrect: boolean;
  verdictVersion: number;
  overrideByName: string | null;
  overrideAt: string | null;
}

export interface ClueOverrideRequest {
  override: ClueOverride;
  version: number;
}

export interface ClueVerdictView {
  submissionId: string;
  teamId: number;
  autoCorrect: boolean;
  override: ClueOverride;
  effectiveCorrect: boolean;
  version: number;
  overrideByName: string | null;
  overrideAt: string | null;
}

export interface RoomTeamRow {
  teamId: number;
  teamName: string;
  groupCode: GroupCode;
  disqualified: boolean;
  /** This round's room exception (the team was sent to that room), or null. */
  exceptionRoom: RoomCode | null;
  scheduledNow: boolean;
  scheduledRound: number;
  latestSubmission: LatestSubmission | null;
  submissionCount: number;
  judgement: JudgementView;
  /** This team's hint and cheating rows in this room (active and cancelled, newest first). */
  deductions: DeductionView[];
  roomHints: number;
  /** Active hints across all four rooms, of `hintsAllowed`. */
  hintsUsed: number;
  hintsAllowed: number;
  cheating: boolean;
  /** The typed 0-15 score of a room without an automatic score; null while none is entered. */
  manualScore: number | null;
  /** A PHONE_POINTS room's ledger: the total out of 100, its x 0.15 score and the change "Undo last" reverses. */
  pointsTotal: number | null;
  pointsScore: number | null;
  undoDelta: number | null;
}

/** POST /rooms/{id}/points/teams/{teamId} and its undo. */
export interface PointsView {
  teamId: number;
  roomId: number;
  total: number;
  score: number;
  undoDelta: number | null;
}

export interface PointsHistoryEntry {
  id: number;
  undo: boolean;
  delta: number;
  totalAfter: number;
  enteredBy: string;
  enteredAt: string;
  undone: boolean;
}

export interface JudgementView {
  teamId: number;
  roomId: number;
  status: JudgeStatus;
  note: string;
  version: number;
  reviewerName: string | null;
  updatedAt: string | null;
}

export interface DeductionView {
  id: number;
  teamId: number;
  teamName: string;
  roomId: number;
  roomCode: RoomCode;
  kind: DeductionKind;
  /** The optional hint note or the required cheating reason. */
  reason: string;
  createdByName: string;
  createdAt: string;
  cancelled: boolean;
  cancelledByName: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
}

export interface JudgementRequest {
  status: JudgeStatus;
  note: string;
  version: number;
}

export interface HintRequest {
  teamId: number;
  note: string;
}

export interface CheatingRequest {
  teamId: number;
  reason: string;
  confirmed: boolean;
}

export interface ManualScoreView {
  teamId: number;
  roomId: number;
  score: number;
}

export interface RoomContentRequest {
  clueKey: string;
  version: number;
}

export interface RoomContentView {
  roomId: number;
  code: RoomCode;
  name: string;
  clueKey: string;
  version: number;
  /** Answers re-checked by this save because the clue key changed (0 otherwise). */
  recheckedSubmissions: number;
  /** The admin's per-room terminal setting. */
  terminalEnabled: boolean;
  /** The admin's per-room activity setting. */
  activity: RoomActivity | null;
}

// ---- AI room activity (rules §19) ----

// ---- AI mission room: SENTINEL (rules §19), team side ----

export type SentinelStageType =
  | "OUTLIER_HUNT"
  | "FEATURE_CHECK"
  | "CHOICE"
  | "INPUT"
  | "TUNE_DEPLOY"
  | "NEAREST_VEHICLE"
  | "CAMERA_ACCURACY"
  | "GROUP_FRAMES"
  | "ROUTE_PLANNER"
  | "THREAT_FILTER"
  | "RULE_ENGINE"
  | "RAG_FIX";

/** One experiment the team ran, with the result the server returned for it (never the whole table). */
export interface SentinelRun {
  config: string;
  result: Record<string, unknown>;
}

/**
 * One stage. Once it is saved only `number`, `type`, `title` and `saved` are set. `data` is what the screen draws;
 * it never holds points, correct flags or answers. `runsLeft` is null when the stage has no limit or no experiments.
 */
export interface SentinelStage {
  number: number;
  type: SentinelStageType;
  title: string;
  fieldNote: string | null;
  brief: string | null;
  saved: boolean;
  data: Record<string, unknown> | null;
  runs: SentinelRun[];
  runsLeft: number | null;
}

export interface SentinelMission {
  id: string;
  title: string;
  intro: string;
  stages: SentinelStage[];
}

/** The ending: the clue only when the team holds the room's clue. The message never contains a number. */
export interface SentinelResult {
  clueUnlocked: boolean;
  clue: string | null;
  message: string;
}

/** GET /team/rooms/{id}/activity: everything the team page shows, from the server only. */
export interface AiRoomActivity {
  roomId: number;
  roomName: string;
  phase: RoundPhase;
  serverNow: string;
  /** False while the room has no mission content. */
  ready: boolean;
  intro: string;
  stageCount: number;
  savedCount: number;
  /** Only while the round runs or is paused and the team has no result. */
  missions: SentinelMission[];
  result: SentinelResult | null;
}

export interface SentinelSavedReceipt {
  stage: number;
  message: string;
  savedAt: string;
}

export interface SentinelRunReceipt {
  stage: number;
  config: string;
  result: Record<string, unknown>;
  runsLeft: number | null;
}

// ---- AI mission room: organizer side (rules §19) ----

export interface MissionStageSummary {
  number: number;
  type: SentinelStageType;
  mission: string;
  title: string;
  maxPoints: number;
}

/** GET /rooms/{id}/mission: what the room's imported mission holds and whether it can still change. */
export interface MissionEditorView {
  roomId: number;
  roomName: string;
  activity: RoomActivity | null;
  passScore: number;
  /** A team of the room was evaluated: the pass score can no longer change. */
  passScoreLocked: boolean;
  /** The final has not opened (content is read-only afterwards). */
  editable: boolean;
  /** A team ran or saved something: the mission can no longer be replaced. */
  workStarted: boolean;
  hasContent: boolean;
  stages: MissionStageSummary[];
  totalPoints: number;
}

export type AiRoomTeamStatus = "IN_PROGRESS" | "PASSED" | "NOT_PASSED";

export interface AiRoomTeamResult {
  rank: number | null;
  teamId: number;
  teamName: string;
  groupCode: GroupCode;
  disqualified: boolean;
  /** One per stage column; null when not saved. */
  stagePoints: (number | null)[];
  total: number;
  stagesSaved: number;
  lastSavedAt: string | null;
  /** The pass score the team was evaluated against (the room's current one while in progress). */
  passScore: number;
  status: AiRoomTeamStatus;
  evaluatedAt: string | null;
  /** An evaluated team holds the room's clue now (an override can make it differ from the status). */
  clueGranted: boolean;
}

/** GET /rooms/{id}/mission/results. */
export interface AiRoomResults {
  roomId: number;
  roomName: string;
  generatedAt: string;
  maxPoints: number;
  passScore: number;
  passScoreLocked: boolean;
  stages: { number: number; title: string; maxPoints: number | null }[];
  teams: AiRoomTeamResult[];
}

export interface TerminalBundleInfo {
  sha256: string;
  archiveBytes: number;
  etcPasswd: string;
  etcGroup: string;
  /** The terminal clock (a UTC instant) the setup sets with date -s; null = real time. */
  clock: string | null;
}

/** The manager's summary of a room's server package (never the archive itself). */
export interface TerminalBundleView {
  sha256: string;
  fileCount: number;
  unpackedBytes: number;
  archiveBytes: number;
  owners: string[];
  uploadedBy: string;
  uploadedAt: string;
  /** The terminal clock (a UTC instant); null = real time. */
  clock: string | null;
}

/**
 * What a terminal starts with. A room with a server package sends `bundle` and no text files; other rooms send
 * text files with paths relative to the home directory (e.g. logs/lock.log) and `bundle: null`.
 */
export interface TerminalStartFiles {
  files: { path: string; content: string }[];
  bundle: TerminalBundleInfo | null;
}

export interface TerminalFileEntry {
  id: number;
  path: string;
  sizeBytes: number;
  updatedBy: string;
  updatedAt: string;
}

/** The manager's list of a room's terminal files (no content) with the limits. */
export interface TerminalFileList {
  roomId: number;
  terminalEnabled: boolean;
  /** False once the final has opened. */
  editable: boolean;
  files: TerminalFileEntry[];
  totalBytes: number;
  maxFiles: number;
  maxFileBytes: number;
  maxTotalBytes: number;
  /** The room's server package; null when it has none. */
  bundle: TerminalBundleView | null;
  maxBundleUploadBytes: number;
}

export interface TerminalFileContent {
  id: number;
  path: string;
  content: string;
  sizeBytes: number;
}

export interface TerminalFileRequest {
  path: string;
  content: string;
}

/** The six values of a Linux room form (Save and Final submit carry all of them; blanks are fine). */
export interface TerminalFormRequest {
  path: string;
  username: string;
  fileSize: string;
  pattern: string;
  finalClue: string;
}

/** All a team learns about its form: nothing saved yet, Saved, or Submitted. Never an outcome, score or key. */
export type TerminalFormStatus = "EMPTY" | "SAVED" | "SUBMITTED";

export interface TerminalFormView extends TerminalFormRequest {
  roomId: number;
  status: TerminalFormStatus;
  savedAt: string | null;
  submittedAt: string | null;
}

// ---- Linux room organizer dashboard (rules §18.8) ----

export type TerminalScoreItem = "PATH" | "USERNAME" | "FILE_SIZE" | "PATTERN" | "CLUE";
export type TerminalDashboardStatus = "NOT_STARTED" | "SAVED" | "SUBMITTED";

export interface TerminalItemScore {
  item: TerminalScoreItem;
  label: string;
  /** What the keys say, without any override. */
  auto: boolean;
  /** null = automatic, true = awarded by an organizer, false = denied by an organizer. */
  override: boolean | null;
  awarded: boolean;
  points: number;
}

export interface TerminalDashboardTeam {
  teamId: number;
  teamName: string;
  group: GroupCode;
  disqualified: boolean;
  status: TerminalDashboardStatus;
  path: string;
  username: string;
  fileSize: string;
  pattern: string;
  finalClue: string;
  clueCorrect: boolean;
  items: TerminalItemScore[];
  total: number;
  savedAt: string | null;
  submittedAt: string | null;
  cause: "SUBMIT" | "TIME_UP" | null;
}

/** The answer keys: each holds one value per line. */
export interface TerminalAnswerKeys {
  path: string;
  username: string;
  fileSize: string;
  pattern: string;
}

/** GET /rooms/{id}/terminal-dashboard: for the room's manager and the ADMIN only (it carries the keys). */
export interface TerminalDashboard {
  roomId: number;
  roomName: string;
  keysEditable: boolean;
  keys: TerminalAnswerKeys;
  emptyKeys: string[];
  pointsPerItem: number;
  maxPoints: number;
  teams: TerminalDashboardTeam[];
  generatedAt: string;
}

// ---- Problem Solving room: the contest (rules §20) ----

export type ContestProblem = "A" | "B" | "C" | "D" | "E" | "F";
export const CONTEST_PROBLEMS: readonly ContestProblem[] = ["A", "B", "C", "D", "E", "F"];

/** The team's standing in the Codeforces standings, picked by the organizer (ties allowed): bonus 3, 2, 1, 0. */
export type ContestStanding = "FIRST" | "SECOND" | "THIRD" | "OTHER";
export const CONTEST_STANDINGS: readonly { value: ContestStanding; label: string }[] = [
  { value: "FIRST", label: "1st" },
  { value: "SECOND", label: "2nd" },
  { value: "THIRD", label: "3rd" },
  { value: "OTHER", label: "Other" },
];

/** GET /team/rooms/{id}/contest: all a team learns. No count, point, standing, bonus or eligibility is ever sent. */
export interface ContestTeamView {
  roomId: number;
  /** The contest link while the team's timer runs or is paused; null otherwise. */
  contestUrl: string | null;
  released: boolean;
}

export interface ContestTeamRow {
  teamId: number;
  teamName: string;
  group: GroupCode;
  round: number;
  saved: boolean;
  solved: ContestProblem[];
  /** Null while the team has no saved result (a result saved before standings existed reads as OTHER). */
  standing: ContestStanding | null;
  solvedCount: number;
  problemPoints: number;
  bonus: number;
  total: number;
  eligible: boolean;
  released: boolean;
  releasedAt: string | null;
  /** The clue was released and the results were edited below the line since; the clue stays. */
  releasedNotEligible: boolean;
  savedAt: string | null;
}

export interface ContestRound {
  round: number;
  group: GroupCode;
  teamCount: number;
  savedCount: number;
  eligibleCount: number;
  releasedCount: number;
  releasableCount: number;
  teams: ContestTeamRow[];
}

/** GET /rooms/{id}/contest-results: for the room's manager and the ADMIN only. */
export interface ContestResults {
  roomId: number;
  roomName: string;
  contestUrl: string | null;
  linkEditable: boolean;
  clueKeySet: boolean;
  maxProblemPoints: number;
  maxTotal: number;
  eligibleSolved: number;
  problems: { problem: ContestProblem; points: number }[];
  rounds: ContestRound[];
  generatedAt: string;
}

export interface ContestResultRequest {
  problems: ContestProblem[];
  standing: ContestStanding;
}

export interface TerminalSettingView {
  roomId: number;
  terminalEnabled: boolean;
}

// ---- Admin ----

/** The admin's event state; the RoundClock fields describe the current round's timer. */
export interface EventStateView extends RoundClock {
  /** When the timer was first started in this round; null while ASSIGNED. */
  roundStartedAt: string | null;
  /** Default length of rounds that begin later. */
  roundMinutes: number;
  finalOpen: boolean;
  finalStartedAt: string | null;
  finalClosedAt: string | null;
  resultsPublished: boolean;
  revision: number;
}

export interface AdminRoomCard {
  id: number;
  code: RoomCode;
  name: string;
  position: number;
  open: boolean;
  managerId: number | null;
  managerName: string | null;
  groupNow: GroupCode | null;
  teamsNow: string[];
  passed: number;
  failed: number;
  pending: number;
  totalTeams: number;
  hasClueKey: boolean;
  /** Null in rounds 0 and 5. */
  arrival: RoomArrivalView | null;
}

export interface RotationRow {
  groupCode: GroupCode;
  /** Index 0..3 = rounds 1..4. */
  rooms: RoomCode[];
}

/** One room of a team's score: the base, the deductions and what is left (rounded to 2 decimals). */
export interface RoomScoreLine {
  roomId: number;
  code: RoomCode;
  name: string;
  /** AI, LINUX, CONTEST or MANUAL: how the base was obtained. */
  source: string;
  base: number;
  hints: number;
  cheating: boolean;
  hintDeduction: number;
  cheatingDeduction: number;
  score: number;
}

export interface FinalScoreLine {
  status: FinalStage;
  /** Evidence files decrypted, 0 to 4. */
  cluesSolved: number;
  /** Their points: 3.75 each. */
  clues: number;
  suspectSubmitted: boolean;
  suspectCorrect: boolean;
  suspect: number;
  activeSeconds: number | null;
  time: number;
  total: number;
  finishedAt: string | null;
}

/** A team's full score out of 100: four rooms of 15 plus the final's 40. Organizers only. */
export interface TeamScoreBreakdown {
  teamId: number;
  teamName: string;
  groupCode: GroupCode;
  disqualified: boolean;
  /** Clues held, and the rooms they come from in room order. */
  clues: number;
  clueRooms: RoomCode[];
  rooms: RoomScoreLine[];
  roomsTotal: number;
  finalScore: FinalScoreLine;
  total: number;
}

export interface RankingRow {
  /** Null for a disqualified team, which stays outside the ranking. */
  rank: number | null;
  scores: TeamScoreBreakdown;
}

/** One team on the admin's live final table. */
export interface FinalBoardRow {
  teamId: number;
  teamName: string;
  groupCode: GroupCode;
  disqualified: boolean;
  status: FinalStage;
  stage: string;
  /** The final's phase while the team is on the case; null otherwise. */
  phase: FinalPhase | null;
  clues: number;
  /** Evidence files decrypted, 0 to 4. */
  cluesSolved: number;
  unlockAttempts: number;
  unlockWrong: number;
  passphraseAttempts: number;
  passphraseWrong: number;
  recoveryAttempts: number;
  suspect: string | null;
  suspectCorrect: boolean | null;
  activeSeconds: number | null;
  finishedAt: string | null;
  finishedBy: string | null;
  cluePoints: number;
  suspectPoints: number;
  timePoints: number;
  finalScore: number;
}

export interface FinalTimerView {
  open: boolean;
  phase: RoundPhase | null;
  remainingSeconds: number | null;
  durationSeconds: number | null;
  serverNow: string;
  /** The phase every team is in now, the decryption phase's length and the active seconds until the investigation opens. */
  finalPhase: FinalPhase | null;
  phase1Minutes: number;
  phase1EndsInSeconds: number | null;
}

/** The admin's summary of the final's own terminal package (kept apart from every room's package). */
export interface FinalTerminalBundleView {
  uploaded: boolean;
  readOnly: boolean;
  sha256: string | null;
  fileCount: number | null;
  unpackedBytes: number | null;
  archiveBytes: number | null;
  owners: string[];
  uploadedBy: string | null;
  uploadedAt: string | null;
}

export interface FinalContentView {
  imported: boolean;
  readOnly: boolean;
  importedAt: string | null;
  fullUntilMinutes: number | null;
  recoveryIsPlaceholder: boolean;
  content: string | null;
}

export interface AdminAlert {
  code: string;
  message: string;
  link: string;
  /** The round's timer starts only with "Start anyway" while it exists. */
  blocking: boolean;
}

/** "All teams are here" for a room in the current round. */
export interface RoomArrivalView {
  ready: boolean;
  by: string | null;
  at: string | null;
}

export interface AdminBoard {
  state: EventStateView;
  rooms: AdminRoomCard[];
  rotation: RotationRow[];
  ranking: RankingRow[];
  alerts: AdminAlert[];
  /** Closed rooms and rooms without a clue key; shown before starting the timer, never blocking. */
  startWarnings: string[];
  /** AI mission rooms that cannot run (no clue key, no mission content): the timer starts only with "Start anyway". */
  startBlockers: string[];
  teamFlags: TeamFlagsView[];
  /** The last 20 admin overrides with their reasons. */
  recentOverrides: OverrideAuditView[];
}

// ---- Admin overrides ----

export interface TeamFlagsView {
  teamId: number;
  teamName: string;
  disqualified: boolean;
  exceptionRoom: RoomCode | null;
  manualClues: number;
}

export interface OverrideAuditView {
  at: string;
  by: string | null;
  action: string;
  target: string;
  reason: string | null;
}

/** One team in a swap or move preview. */
export interface RotationPreviewView {
  teamId: number;
  teamName: string;
  fromGroup: GroupCode;
  toGroup: GroupCode;
  visited: RoomCode[];
  remaining: { round: number; room: RoomCode }[];
  warnings: string[];
}

export interface OverrideOutcome {
  message: string;
  warnings: string[];
  snapshotId: number | null;
}

export interface SnapshotView {
  id: number;
  createdAt: string;
  createdBy: string;
  kind: "RESET_FINAL" | "RESET_TEAM" | "RESET_EVENT" | "MANUAL";
  reason: string;
  teamId: number | null;
  teamName: string | null;
}

export interface RoomOpenView {
  roomId: number;
  code: RoomCode;
  open: boolean;
}

export interface RoundRequest {
  round: number;
}

export interface SettingsRequest {
  roundMinutes: number;
}

export interface PublicationRequest {
  published: boolean;
}

export interface OrganizationRoom {
  id: number;
  code: RoomCode;
  name: string;
  managerId: number | null;
  managerName: string | null;
}

export interface GroupTeamCount {
  groupCode: GroupCode;
  activeTeams: number;
}

export interface Organization {
  accounts: AccountView[];
  teams: TeamView[];
  rooms: OrganizationRoom[];
  groups: GroupTeamCount[];
  plannedTeamCount: number;
  /** ceil(plannedTeamCount / 4): the hard cap of active teams per group. */
  maxPerGroup: number;
  /** True once round 1 has started; a team's group can no longer change. */
  groupsLocked: boolean;
  /** Group balance warnings (TEAM_COUNT, GROUP_OVER_CAPACITY, GROUP_UNBALANCED). */
  warnings: AdminAlert[];
}

export interface AccountView {
  id: number;
  email: string;
  name: string;
  role: EventAccountRole;
  active: boolean;
  teamId: number | null;
  roomId: number | null;
}

export interface TeamView {
  id: number;
  name: string;
  groupCode: GroupCode;
  accountId: number;
  accountEmail: string;
  active: boolean;
  /** An open session of the team account was seen in the last 2 minutes. */
  signedIn: boolean;
  lastSeenAt: string | null;
  disqualified: boolean;
  /** The current round's room exception, or null. */
  exceptionRoom: RoomCode | null;
}

export interface CreateAccountRequest {
  email: string;
  name: string;
  password: string;
  role: EventAccountRole;
}

export interface AccountActiveRequest {
  active: boolean;
}

export interface AccountPasswordRequest {
  password: string;
}

export interface CreateTeamRequest {
  name: string;
  groupCode: GroupCode;
  accountId: number;
}

export interface UpdateTeamRequest {
  name: string;
  groupCode: GroupCode;
  accountId: number;
  active: boolean;
}

export interface PlannedTeamsRequest {
  plannedCount: number;
}

export interface GenerateTeamsRequest {
  count: number;
  prefix: string;
}

/** One generated team sign-in; the password is returned once and never again. */
export interface TeamCredential {
  teamName: string;
  group: GroupCode;
  email: string;
  password: string;
}

export interface RoomManagerRequest {
  managerId: number | null;
}

/** Response of PUT /admin/rooms/{roomId}/manager. */
export type RoomManagerView = OrganizationRoom;

/** The admin's content page: every room's content and settings. */
export interface AdminContent {
  rooms: RoomContentView[];
  readOnly: boolean;
}
