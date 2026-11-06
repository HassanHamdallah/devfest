// JSON shapes of /api/v1/event (EVENT-CONTRACT §3). Field names mirror the backend records exactly.

export type RoomCode = "AI" | "PROBLEM_SOLVING" | "LINUX" | "HARDWARE";
export type GroupCode = "A" | "B" | "C" | "D";
export type JudgeStatus = "PENDING" | "PASS" | "FAIL";
export type TeamRoomStatus = "NOT_STARTED" | "SUBMITTED" | "PASSED" | "FAILED";
export type FinalStage =
  | "LOCKED"
  | "NOT_ENOUGH_CLUES"
  | "RECOVERY"
  | "MESSAGE"
  | "FINISHED"
  | "CLOSED";
export type PenaltyKind =
  | "HINT"
  | "MINOR_VIOLATION"
  | "PROHIBITED_TOOL"
  | "CHEATING"
  | "ANSWER_SHARING"
  | "RECOVERY"
  | "WRONG_ACCUSATION"
  | "TIME_ADJUSTMENT";
export type EventRole = "ADMIN" | "MANAGER" | "MEMBER";
export type EventAccountRole = "MANAGER" | "MEMBER";
/** A manager's clue verdict on one submission; AUTOMATIC follows the room's clue key. */
export type ClueOverride = "ACCEPT" | "REJECT" | "AUTOMATIC";
/** A room's team activity (admin setting); a room without one keeps the plain answer form. */
export type RoomActivity = "AI_QUIZ" | "LINUX_TERMINAL";

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
  rank: number | null;
  teamName: string;
  groupCode: GroupCode;
  clues: number;
  penaltyMinutes: number;
  finished: boolean;
  adjustedSeconds: number | null;
  /** Listed last, never ranked. */
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
  /** A room with an activity (AI quiz) never sends the output, answer or result: only whether the clue was unlocked. */
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

export interface TeamRecovery {
  roomId: number;
  roomCode: RoomCode;
  roomName: string;
  prompt: string;
  attempts: number;
}

export interface FinalSuspect {
  id: number;
  name: string;
  description: string;
}

export interface TeamFinish {
  finishedAt: string;
  adjustedSeconds: number;
  receiptId: string;
  suspectName: string;
}

export interface TeamFinal {
  stage: FinalStage;
  finalStartedAt: string | null;
  serverNow: string;
  cluesCollected: number;
  recovery: TeamRecovery | null;
  encryptedMessage: string | null;
  suspects: FinalSuspect[];
  accusationAttempts: number;
  wrongAccusations: number;
  penaltyMinutes: number;
  finish: TeamFinish | null;
  resultsPublished: boolean;
  /** The same per-room evidence as on the team board. */
  evidence: RoomEvidence[];
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

export interface AccusationRequest {
  suspectId: number;
  decryptedMessage: string;
  evidence: string;
}

export interface AccusationResult {
  receiptId: string;
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
  recoveryPrompt: string;
  recoveryAnswer: string;
  version: number;
  contentEditable: boolean;
  /** False while the clue key is empty; the room cannot open. */
  clueKeySet: boolean;
  /** The room offers the in-browser terminal; its manager prepares the terminal files. */
  terminalEnabled: boolean;
  /** The room's team activity; for AI_QUIZ its manager prepares the questions and repairs. */
  activity: RoomActivity | null;
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
  penalties: PenaltyView[];
  activePenaltyMinutes: number;
  hintPenaltyMismatch: boolean;
}

export interface JudgementView {
  teamId: number;
  roomId: number;
  status: JudgeStatus;
  hints: number;
  note: string;
  version: number;
  reviewerName: string | null;
  updatedAt: string | null;
}

export interface PenaltyView {
  id: number;
  teamId: number;
  roomId: number | null;
  kind: PenaltyKind;
  minutes: number;
  note: string;
  createdByName: string;
  createdAt: string;
  voided: boolean;
  voidedByName: string | null;
  voidedAt: string | null;
  voidReason: string | null;
}

export interface JudgementRequest {
  status: JudgeStatus;
  hints: number;
  note: string;
  version: number;
}

export interface PenaltyRequest {
  teamId: number;
  kind: PenaltyKind;
  note: string;
}

export interface VoidPenaltyRequest {
  reason: string;
}

export interface RoomContentRequest {
  clueKey: string;
  recoveryPrompt: string;
  recoveryAnswer: string;
  version: number;
}

export interface RoomContentView {
  roomId: number;
  code: RoomCode;
  name: string;
  clueKey: string;
  recoveryPrompt: string;
  recoveryAnswer: string;
  version: number;
  /** Answers re-checked by this save because the clue key changed (0 otherwise). */
  recheckedSubmissions: number;
  /** The admin's per-room terminal setting. */
  terminalEnabled: boolean;
  /** The admin's per-room activity setting. */
  activity: RoomActivity | null;
}

// ---- AI room activity (rules §19) ----

export type QuizSection = "EASY" | "MEDIUM" | "ADVANCED" | "LANDMARK" | "RECENT";
export type QuizStage = "NOT_STARTED" | "MCQ" | "REPAIRS" | "TIME_UP";

/** A saved answer or repair: never whether it was correct. */
export interface QuizSavedReceipt {
  itemId: number;
  message: string;
  savedAt: string;
}

export interface ActivitySection {
  section: QuizSection;
  label: string;
  paceSeconds: number;
  questions: number;
}

export interface ActivityQuestion {
  id: number;
  number: number;
  section: QuizSection;
  prompt: string;
  options: { id: number; text: string }[];
  /** The team's saved answer, or null. */
  selectedOptionId: number | null;
}

export interface ActivityRepair {
  id: number;
  number: number;
  title: string;
  /** Null once the repairs are closed. */
  instructions: string | null;
  /** In the team's order, or as submitted; empty once the repairs are closed. */
  snippets: { id: number; code: string }[];
  submitted: boolean;
}

/**
 * The team's outcome once it was evaluated: the room's clue when it holds it, otherwise nothing (never a score or
 * the pass score).
 */
export interface ActivityResult {
  clueUnlocked: boolean;
  clue: string | null;
}

/** GET /team/rooms/{id}/activity: everything the team page shows, from the server only. */
export interface AiRoomActivity {
  roomId: number;
  roomName: string;
  stage: QuizStage;
  phase: RoundPhase;
  serverNow: string;
  mcqMinutes: number;
  /** MCQ time left at serverNow (counts down only while the round runs). */
  mcqRemainingSeconds: number;
  mcqFinished: boolean;
  questionCount: number;
  answeredCount: number;
  repairCount: number;
  submittedCount: number;
  sections: ActivitySection[];
  /** Only while the MCQ part is open. */
  questions: ActivityQuestion[];
  /** Once the repairs are open. */
  repairs: ActivityRepair[];
  /** Null until the team is evaluated (its last repair, or the round's end); then only this matters. */
  result: ActivityResult | null;
}

/** One question as written in the editor and in an import file; `correctOption` is 0-based. */
export interface QuizQuestionRequest {
  section: QuizSection;
  prompt: string;
  options: string[];
  correctOption: number;
  points: number;
  explanation: string;
}

/** One repair; `snippets` in their correct order. */
export interface QuizRepairRequest {
  title: string;
  instructions: string;
  points: number;
  snippets: string[];
}

/** The import/export file. */
export interface QuizDocument {
  format: number;
  mcqMinutes: number | null;
  passScore: number | null;
  questions: QuizQuestionRequest[];
  repairs: QuizRepairRequest[];
}

export interface QuizEditorQuestion extends QuizQuestionRequest {
  id: number;
  /** A team answered it: it can no longer change. */
  locked: boolean;
}

export interface QuizEditorRepair extends QuizRepairRequest {
  id: number;
  number: number;
  locked: boolean;
}

/** GET /rooms/{id}/quiz: the manager's view, with correct answers and explanations. */
export interface QuizEditorView {
  roomId: number;
  roomName: string;
  activity: RoomActivity | null;
  mcqMinutes: number;
  /** The total (out of the quiz's points) a team needs for the room's clue. */
  passScore: number;
  /** A team was evaluated: the pass score can no longer change. */
  passScoreLocked: boolean;
  editable: boolean;
  /** A team answered something; an import can no longer replace the content. */
  answered: boolean;
  questions: QuizEditorQuestion[];
  repairs: QuizEditorRepair[];
  sections: { section: QuizSection; label: string; questions: number; planned: number; points: number }[];
  mcqPoints: number;
  repairPoints: number;
  totalPoints: number;
  warnings: string[];
}

export interface AiRoomTeamResult {
  rank: number | null;
  teamId: number;
  teamName: string;
  groupCode: GroupCode;
  disqualified: boolean;
  mcqBySection: Record<QuizSection, number>;
  mcqTotal: number;
  /** One per repair column; null when not submitted. */
  repairScores: (number | null)[];
  repairTotal: number;
  total: number;
  answered: number;
  repairsSubmitted: number;
  mcqFinishedAt: string | null;
  lastAnswerAt: string | null;
  /** The pass score the team was evaluated against (the room's current one while in progress). */
  passScore: number;
  status: AiRoomTeamStatus;
  evaluatedAt: string | null;
  /** An evaluated team holds the room's clue now (an override can make it differ from the status). */
  clueGranted: boolean;
}

export type AiRoomTeamStatus = "IN_PROGRESS" | "PASSED" | "NOT_PASSED";

/** GET /rooms/{id}/quiz/results. */
export interface AiRoomResults {
  roomId: number;
  roomName: string;
  generatedAt: string;
  maxPoints: number;
  passScore: number;
  passScoreLocked: boolean;
  repairs: { id: number; number: number; title: string; points: number }[];
  teams: AiRoomTeamResult[];
  questions: {
    id: number;
    section: QuizSection;
    prompt: string;
    answered: number;
    correct: number;
    percentCorrect: number;
  }[];
}

// ---- In-browser terminal ----

/**
 * What a terminal needs to know about its room's server package before downloading it: the digest (also the
 * download's ETag), the size on the wire and the lines to append to /etc/passwd and /etc/group.
 */
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
  encryptedMessage: string;
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

export type TerminalScoreItem = "PATH" | "USERNAME" | "FILE_SIZE" | "PATTERN" | "MESSAGE_CLUE";
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
  encryptedMessage: string;
  finalClue: string;
  messageCorrect: boolean;
  clueCorrect: boolean;
  items: TerminalItemScore[];
  total: number;
  savedAt: string | null;
  submittedAt: string | null;
  cause: "SUBMIT" | "TIME_UP" | null;
}

/** The answer keys: the first four hold one value per line. */
export interface TerminalAnswerKeys {
  path: string;
  username: string;
  fileSize: string;
  pattern: string;
  encryptedMessage: string;
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

export interface RankingRow {
  rank: number | null;
  teamId: number;
  teamName: string;
  groupCode: GroupCode;
  clues: number;
  /** Rooms whose clue the team holds, in room order (admin only). */
  clueRooms: RoomCode[];
  penaltyMinutes: number;
  penaltyEntries: number;
  finished: boolean;
  finishedAt: string | null;
  adjustedSeconds: number | null;
  disqualified: boolean;
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
  /** AI quiz rooms that cannot run (no clue key, no questions): the timer starts only with "Start anyway". */
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
  timeAdjustmentMinutes: number;
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

export interface SuspectView {
  id: number;
  name: string;
  description: string;
  position: number;
}

export interface AdminContent {
  encryptedMessage: string;
  correctSuspectId: number | null;
  version: number;
  suspects: SuspectView[];
  rooms: RoomContentView[];
  readOnly: boolean;
}

export interface AdminContentRequest {
  encryptedMessage: string;
  correctSuspectId: number | null;
  version: number;
}

export interface SuspectRequest {
  name: string;
  description: string;
}
