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
  | "WRONG_ACCUSATION";
export type EventRole = "ADMIN" | "MANAGER" | "MEMBER";
export type EventAccountRole = "MANAGER" | "MEMBER";

/** Room codes in position order (0..3) and group codes in index order (0..3). */
export const ROOM_CODES: readonly RoomCode[] = [
  "AI",
  "PROBLEM_SOLVING",
  "LINUX",
  "HARDWARE",
];
export const GROUP_CODES: readonly GroupCode[] = ["A", "B", "C", "D"];

export interface RoundClock {
  currentRound: number;
  roundStartedAt: string | null;
  roundMinutes: number;
  serverNow: string;
}

// ---- Shared ----

export interface EventRevision {
  revision: number;
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
}

// ---- Member ----

export interface ScheduledRoom {
  roomId: number;
  code: RoomCode;
  name: string;
  open: boolean;
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
  clueCorrect: boolean;
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
}

export interface LatestSubmission {
  id: string;
  round: number;
  technicalOutput: string;
  clueAnswer: string;
  clueCorrect: boolean;
  receivedAt: string;
}

export interface RoomTeamRow {
  teamId: number;
  teamName: string;
  groupCode: GroupCode;
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
}

// ---- Admin ----

export interface EventStateView {
  currentRound: number;
  roundStartedAt: string | null;
  roundMinutes: number;
  serverNow: string;
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
  penaltyMinutes: number;
  penaltyEntries: number;
  finished: boolean;
  finishedAt: string | null;
  adjustedSeconds: number | null;
}

export interface AdminAlert {
  code: string;
  message: string;
  link: string;
}

export interface AdminBoard {
  state: EventStateView;
  rooms: AdminRoomCard[];
  rotation: RotationRow[];
  ranking: RankingRow[];
  alerts: AdminAlert[];
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
