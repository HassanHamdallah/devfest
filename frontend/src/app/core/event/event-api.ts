import { Injectable, inject } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { firstValueFrom } from "rxjs";
import { Api, BASE } from "../api";
import {
  AccountView,
  CheatingRequest,
  DeductionView,
  FinalActionResult,
  FinalBoardRow,
  FinalReport,
  FinalReportRow,
  FinalContentView,
  FinalTerminalBundleView,
  FinalTimerView,
  HintRequest,
  PassphraseRequest,
  ManualScoreView,
  PointsHistoryEntry,
  PointsView,
  RankingRow,
  UnlockRequest,
  AdminBoard,
  AdminContent,
  AiRoomActivity,
  ContestResultRequest,
  ContestResults,
  ContestTeamView,
  AiRoomResults,
  MissionEditorView,
  ClueOverrideRequest,
  ClueVerdictView,
  CreateAccountRequest,
  CreateTeamRequest,
  EventHome,
  EventRevision,
  EventStateView,
  GenerateTeamsRequest,
  GroupCode,
  JudgementRequest,
  JudgementView,
  Organization,
  OverrideOutcome,
  SentinelRunReceipt,
  SentinelSavedReceipt,
  Receipt,
  RecoverySolutions,
  TeamRecovery,
  ResultsView,
  RoomActivity,
  RoomBoard,
  RoomContentRequest,
  RoomArrivalView,
  RoomContentView,
  RoomManagerView,
  RoomOpenView,
  RotationPreviewView,
  SnapshotView,
  RoomSubmissionRequest,
  StreamTicket,
  TeamBoard,
  TeamCredential,
  TeamFinal,
  TeamView,
  TerminalFileContent,
  TerminalFileList,
  TerminalAnswerKeys,
  TerminalDashboard,
  TerminalFileRequest,
  TerminalFormRequest,
  TerminalFormView,
  TerminalScoreItem,
  TerminalSettingView,
  TerminalStartFiles,
  UpdateTeamRequest,
} from "./event-models";
import { PendingRequest } from "./pending-request";

const EVENT = "/event";

/** Paths of the idempotent team POSTs, relative to the API base (what PendingRequest.path stores). */
export const roomSubmissionPath = (roomId: number) =>
  `${EVENT}/team/rooms/${roomId}/submissions`;
/** One run of the Arduino level's hidden tests. */
export const RECOVERY_ARDUINO_PATH = `${EVENT}/team/final/recovery/arduino/run`;
/** One ML recovery stage's check (stages 1 to 6). */
export const recoveryMlPath = (stage: number) => `${EVENT}/team/final/recovery/ml/${stage}`;
export const UNLOCK_PATH = `${EVENT}/team/final/unlock`;
export const passphrasePath = (clue: number) => `${EVENT}/team/final/clues/${clue}/passphrase`;

/** One typed method per /api/v1/event endpoint (EVENT-CONTRACT §3). */
@Injectable({ providedIn: "root" })
export class EventApi {
  private readonly api = inject(Api);
  private readonly http = inject(HttpClient);

  // ---- Shared ----
  revision() {
    return this.get<EventRevision>("/revision");
  }
  streamTicket() {
    return this.send<StreamTicket>("POST", "/stream-ticket", {});
  }
  /** The live revision stream for EventSource; the ticket is the credential. */
  streamUrl(ticket: string): string {
    return `${BASE}${EVENT}/stream?ticket=${encodeURIComponent(ticket)}`;
  }
  home() {
    return this.get<EventHome>("/home");
  }
  results() {
    return this.get<ResultsView>("/results");
  }

  // ---- Member ----
  teamBoard() {
    return this.get<TeamBoard>("/team/board");
  }
  submitRoomAnswer(roomId: number, body: RoomSubmissionRequest, key: string) {
    return this.postIdempotent<Receipt>(roomSubmissionPath(roomId), body, key);
  }
  teamSubmissions() {
    return this.get<Receipt[]>("/team/submissions");
  }
  teamSubmission(id: string) {
    return this.get<Receipt>(`/team/submissions/${encodeURIComponent(id)}`);
  }
  teamFinal() {
    return this.get<TeamFinal>("/team/final");
  }
  /** The team's recovery levels: the current stage's public data, the passed flags and the last test results. */
  teamRecovery() {
    return this.get<TeamRecovery>("/team/final/recovery");
  }
  startCase() {
    return this.send<FinalActionResult>("POST", "/team/final/start", {});
  }
  /** One time only; the answer never says whether the suspect was right. */
  submitSuspect(suspectId: string, report: Partial<Record<keyof FinalReport, string>> = {}) {
    return this.send<FinalActionResult>("POST", "/team/final/suspect", { suspectId, ...report });
  }
  /** 404 unless the team is assigned to the room this round and the round is not over (rules §18). */
  teamTerminalFiles(roomId: number) {
    return this.get<TerminalStartFiles>(`/team/rooms/${roomId}/terminal-files`);
  }
  /** 404 unless the room has the Linux terminal activity and is the team's room this round (rules §18.7). */
  teamTerminalForm(roomId: number) {
    return this.get<TerminalFormView>(`/team/rooms/${roomId}/terminal-form`);
  }
  saveTerminalForm(roomId: number, body: TerminalFormRequest, key: string) {
    return this.postIdempotent<TerminalFormView>(`${EVENT}/team/rooms/${roomId}/terminal-form/save`, body, key);
  }
  /** Irreversible: locks the form and records the final answer. */
  submitTerminalForm(roomId: number, body: TerminalFormRequest, key: string) {
    return this.postIdempotent<TerminalFormView>(`${EVENT}/team/rooms/${roomId}/terminal-form/submit`, body, key);
  }
  /** 404 unless the room runs the contest and is the team's room this round (rules §20.3). */
  teamContest(roomId: number) {
    return this.get<ContestTeamView>(`/team/rooms/${roomId}/contest`);
  }
  contestResults(roomId: number) {
    return this.get<ContestResults>(`/rooms/${roomId}/contest-results`);
  }
  saveContestLink(roomId: number, url: string) {
    return this.send<ContestResults>("PUT", `/rooms/${roomId}/contest-results/link`, { url });
  }
  clearContestLink(roomId: number) {
    return this.send<ContestResults>("DELETE", `/rooms/${roomId}/contest-results/link`);
  }
  saveContestResult(roomId: number, teamId: number, body: ContestResultRequest) {
    return this.send<ContestResults>("PUT", `/rooms/${roomId}/contest-results/teams/${teamId}`, body);
  }
  /** 409 when the team is not eligible or the room has no clue key; repeating it changes nothing. */
  revealContestClue(roomId: number, teamId: number) {
    return this.send<ContestResults>("POST", `/rooms/${roomId}/contest-results/teams/${teamId}/reveal`);
  }
  revealContestRound(roomId: number, round: number) {
    return this.send<ContestResults>("POST", `/rooms/${roomId}/contest-results/rounds/${round}/reveal`);
  }
  /** Downloads GET /rooms/{id}/contest-results.csv as problem-solving-results.csv. */
  async downloadContestResultsCsv(roomId: number): Promise<void> {
    const csv = await firstValueFrom(
      this.http.get(`${BASE}${EVENT}/rooms/${roomId}/contest-results.csv`, { responseType: "text" }),
    );
    saveFile(csv, "text/csv", "problem-solving-results.csv");
  }
  /** 404 unless the room runs the AI mission and is the team's room this round (rules §19). */
  teamActivity(roomId: number) {
    return this.get<AiRoomActivity>(`/team/rooms/${roomId}/activity`);
  }
  /** The key is the answer's: a retry with the same key and body gets the same receipt. */
  /** One experiment of stages 1, 3 and 10; repeating a configuration is free. */
  runSentinelStage(roomId: number, stage: number, config: string, key: string) {
    return this.postIdempotent<SentinelRunReceipt>(
      `${EVENT}/team/rooms/${roomId}/activity/stages/${stage}/runs`,
      { config },
      key,
    );
  }
  /** Locks the stage for good; the answer is judged on the server and the team only gets "Saved". */
  submitSentinelStage(roomId: number, stage: number, answer: object, key: string) {
    return this.postIdempotent<SentinelSavedReceipt>(
      `${EVENT}/team/rooms/${roomId}/activity/stages/${stage}/submit`,
      answer,
      key,
    );
  }
  /** Resends a stored attempt with its original key and body (submission reliability contract). */
  resendPending<T>(pending: PendingRequest<unknown>) {
    return this.postIdempotent<T>(pending.path, pending.body, pending.key);
  }

  // ---- Manager (ADMIN may use any room) ----
  roomBoard(roomId: number) {
    return this.get<RoomBoard>(`/rooms/${roomId}/board`);
  }
  saveJudgement(roomId: number, teamId: number, body: JudgementRequest) {
    return this.send<JudgementView>(
      "PUT",
      `/rooms/${roomId}/judgements/${teamId}`,
      body,
    );
  }
  /** "Hint given (-2)": one click, optional note (the room's manager, or the admin for any room). */
  giveHint(roomId: number, body: HintRequest) {
    return this.send<DeductionView>("POST", `/rooms/${roomId}/hints`, body);
  }
  /** "Cheating (-7.5)": needs the confirmation and a reason. */
  recordCheating(roomId: number, body: CheatingRequest) {
    return this.send<DeductionView>("POST", `/rooms/${roomId}/cheating`, body);
  }
  /** Cancelling keeps the row in the log and restores the hint or the points. */
  cancelDeduction(deductionId: number, reason: string) {
    return this.send<DeductionView>("POST", `/deductions/${deductionId}/cancel`, { reason });
  }
  roomDeductions(roomId: number) {
    return this.get<DeductionView[]>(`/rooms/${roomId}/deductions`);
  }
  /** The typed 0-15 score of a team in a room without an automatic score. */
  saveManualScore(roomId: number, teamId: number, score: number) {
    return this.send<ManualScoreView>("PUT", `/rooms/${roomId}/manual-scores/${teamId}`, { score });
  }
  /** Adds (positive) or subtracts (negative) whole points in a PHONE_POINTS room; the key makes a repeat harmless. */
  addPoints(roomId: number, teamId: number, delta: number, key: string) {
    return this.postIdempotent<PointsView>(`${EVENT}/rooms/${roomId}/points/teams/${teamId}`, { delta }, key);
  }
  undoPoints(roomId: number, teamId: number, key: string) {
    return this.postIdempotent<PointsView>(`${EVENT}/rooms/${roomId}/points/teams/${teamId}/undo`, {}, key);
  }
  pointsHistory(roomId: number, teamId: number) {
    return this.get<PointsHistoryEntry[]>(`/rooms/${roomId}/points/teams/${teamId}/history`);
  }
  /** The room's phone number answer; empty clears it. Never audited with its value. */
  savePhoneNumber(roomId: number, phoneNumber: string) {
    return this.send<void>("PUT", `/rooms/${roomId}/phone-number`, { phoneNumber });
  }
  saveRoomContent(roomId: number, body: RoomContentRequest) {
    return this.send<RoomContentView>("PUT", `/rooms/${roomId}/content`, body);
  }
  setArrival(roomId: number, ready: boolean) {
    return this.send<RoomArrivalView>("PUT", `/rooms/${roomId}/arrival`, {
      ready,
    });
  }
  setClueOverride(
    roomId: number,
    submissionId: string,
    body: ClueOverrideRequest,
  ) {
    return this.send<ClueVerdictView>(
      "PUT",
      `/rooms/${roomId}/submissions/${encodeURIComponent(submissionId)}/clue-override`,
      body,
    );
  }

  terminalFiles(roomId: number) {
    return this.get<TerminalFileList>(`/rooms/${roomId}/terminal-files`);
  }
  /** Every file with its content, for "Test terminal". */
  terminalStartFiles(roomId: number) {
    return this.get<TerminalStartFiles>(`/rooms/${roomId}/terminal-files/content`);
  }
  terminalFile(roomId: number, fileId: number) {
    return this.get<TerminalFileContent>(`/rooms/${roomId}/terminal-files/${fileId}`);
  }
  createTerminalFile(roomId: number, body: TerminalFileRequest) {
    return this.send<TerminalFileList>("POST", `/rooms/${roomId}/terminal-files`, body);
  }
  updateTerminalFile(roomId: number, fileId: number, body: TerminalFileRequest) {
    return this.send<TerminalFileList>("PUT", `/rooms/${roomId}/terminal-files/${fileId}`, body);
  }
  deleteTerminalFile(roomId: number, fileId: number) {
    return this.send<TerminalFileList>("DELETE", `/rooms/${roomId}/terminal-files/${fileId}`);
  }
  /** Uploads (or replaces) the room's server package; a rejected ZIP answers 400 with every problem in `problems`. */
  uploadTerminalBundle(roomId: number, zip: File) {
    const form = new FormData();
    form.append("file", zip, zip.name);
    return this.send<TerminalFileList>("PUT", `/rooms/${roomId}/terminal-bundle`, form);
  }
  terminalDashboard(roomId: number) {
    return this.get<TerminalDashboard>(`/rooms/${roomId}/terminal-dashboard`);
  }
  saveTerminalKeys(roomId: number, keys: TerminalAnswerKeys) {
    return this.send<TerminalDashboard>("PUT", `/rooms/${roomId}/terminal-dashboard/keys`, keys);
  }
  /** true awards the item's points, false denies them, null goes back to automatic. */
  setTerminalOverride(roomId: number, teamId: number, item: TerminalScoreItem, awarded: boolean | null) {
    return this.send<TerminalDashboard>("PUT", `/rooms/${roomId}/terminal-dashboard/teams/${teamId}/items/${item}`, {
      awarded,
    });
  }
  /** Downloads GET /rooms/{id}/terminal-dashboard.csv as linux-room-scores.csv. */
  async downloadTerminalDashboardCsv(roomId: number): Promise<void> {
    const csv = await firstValueFrom(
      this.http.get(`${BASE}${EVENT}/rooms/${roomId}/terminal-dashboard.csv`, { responseType: "text" }),
    );
    saveFile(csv, "text/csv", "linux-room-scores.csv");
  }
  /** Sets (an ISO UTC instant such as 2026-03-14T09:26:00Z) or clears (null) the package's terminal clock. */
  setTerminalClock(roomId: number, clock: string | null) {
    return this.send<TerminalFileList>("PUT", `/rooms/${roomId}/terminal-bundle/clock`, { clock });
  }
  deleteTerminalBundle(roomId: number) {
    return this.send<TerminalFileList>("DELETE", `/rooms/${roomId}/terminal-bundle`);
  }
  /**
   * The room's package as gzip bytes. The server answers with an ETag and "no-cache", so the browser revalidates
   * and a restarted terminal gets a 304 instead of the bytes.
   */
  /** The final's own terminal package as gzip bytes (only while the team's final shows the case); ETag like a room's. */
  finalTerminalBundle(): Promise<ArrayBuffer> {
    return firstValueFrom(this.http.get(`${BASE}${EVENT}/team/final/terminal-bundle`, { responseType: "arraybuffer" }));
  }
  terminalBundle(roomId: number, scope: "team" | "manager"): Promise<ArrayBuffer> {
    const path = scope === "team" ? `/team/rooms/${roomId}/terminal-bundle` : `/rooms/${roomId}/terminal-bundle`;
    return firstValueFrom(this.http.get(`${BASE}${EVENT}${path}`, { responseType: "arraybuffer" }));
  }

  missionEditor(roomId: number) {
    return this.get<MissionEditorView>(`/rooms/${roomId}/mission`);
  }
  /** The pass score is locked once a team of the room was evaluated (409). */
  saveMissionSettings(roomId: number, passScore: number) {
    return this.send<MissionEditorView>("PUT", `/rooms/${roomId}/mission/settings`, { passScore });
  }
  /** The whole mission file, including its scoring tables (organizers only). */
  exportMission(roomId: number) {
    return this.get<object>(`/rooms/${roomId}/mission/export`);
  }
  /**
   * Replaces the room's mission with the file's text, sent exactly as read. A refused file answers 400 with every
   * problem in `problems`; 409 once a team ran or saved anything.
   */
  importMission(roomId: number, fileText: string) {
    return this.api.request<MissionEditorView>("POST", `${EVENT}/rooms/${roomId}/mission/import`, fileText, {
      "Content-Type": "application/json",
    });
  }
  missionResults(roomId: number) {
    return this.get<AiRoomResults>(`/rooms/${roomId}/mission/results`);
  }

  /** Downloads GET /rooms/{id}/mission/results.csv as ai-room-results.csv. */
  async downloadMissionResultsCsv(roomId: number): Promise<void> {
    const csv = await firstValueFrom(
      this.http.get(`${BASE}${EVENT}/rooms/${roomId}/mission/results.csv`, { responseType: "text" }),
    );
    saveFile(csv, "text/csv", "ai-room-results.csv");
  }

  // ---- Admin: control ----
  adminBoard() {
    return this.get<AdminBoard>("/admin/board");
  }
  /** `anyway`: start although the board lists blocking alerts ("Start anyway"). */
  startTimer(anyway = false) {
    return this.send<EventStateView>("POST", "/admin/round/timer/start", anyway ? { anyway } : {});
  }
  pauseTimer() {
    return this.send<EventStateView>("POST", "/admin/round/timer/pause", {});
  }
  resumeTimer() {
    return this.send<EventStateView>("POST", "/admin/round/timer/resume", {});
  }
  /** Not idempotent by nature: the key makes a repeated request (same key, same round) a no-op. */
  adjustTimer(deltaSeconds: number, idempotencyKey: string) {
    return this.api.request<EventStateView>(
      "POST",
      `${EVENT}/admin/round/timer/adjust`,
      { deltaSeconds },
      { "Idempotency-Key": idempotencyKey },
    );
  }
  endRound() {
    return this.send<EventStateView>("POST", "/admin/round/timer/end", {});
  }
  openRoom(roomId: number) {
    return this.send<RoomOpenView>("POST", `/admin/rooms/${roomId}/open`, {});
  }
  closeRoom(roomId: number) {
    return this.send<RoomOpenView>("POST", `/admin/rooms/${roomId}/close`, {});
  }
  setRound(round: number) {
    return this.send<EventStateView>("POST", "/admin/round", { round });
  }
  saveSettings(roundMinutes: number) {
    return this.send<EventStateView>("PUT", "/admin/settings", {
      roundMinutes,
    });
  }
  openFinal() {
    return this.send<EventStateView>("POST", "/admin/final/open", {});
  }
  closeFinal() {
    return this.send<EventStateView>("POST", "/admin/final/close", {});
  }
  setPublication(published: boolean) {
    return this.send<EventStateView>("PUT", "/admin/publication", {
      published,
    });
  }

  // ---- Admin: setup ----
  organization() {
    return this.get<Organization>("/admin/organization");
  }
  createAccount(body: CreateAccountRequest) {
    return this.send<AccountView>("POST", "/admin/accounts", body);
  }
  setAccountActive(accountId: number, active: boolean) {
    return this.send<AccountView>(
      "PUT",
      `/admin/accounts/${accountId}/active`,
      {
        active,
      },
    );
  }
  resetAccountPassword(accountId: number, password: string) {
    return this.send<AccountView>(
      "POST",
      `/admin/accounts/${accountId}/password`,
      { password },
    );
  }
  createTeam(body: CreateTeamRequest) {
    return this.send<TeamView>("POST", "/admin/teams", body);
  }
  updateTeam(teamId: number, body: UpdateTeamRequest) {
    return this.send<TeamView>("PUT", `/admin/teams/${teamId}`, body);
  }
  setPlannedTeamCount(plannedCount: number) {
    return this.send<Organization>("PUT", "/admin/teams/planned-count", {
      plannedCount,
    });
  }
  /** The response holds one-time passwords; callers keep it only while the dialog is open. */
  generateTeams(body: GenerateTeamsRequest) {
    return this.send<TeamCredential[]>("POST", "/admin/teams/generate", body);
  }
  signOutTeam(teamId: number) {
    return this.send<TeamView>("POST", `/admin/teams/${teamId}/sign-out`);
  }
  assignRoomManager(roomId: number, managerId: number | null) {
    return this.send<RoomManagerView>("PUT", `/admin/rooms/${roomId}/manager`, {
      managerId,
    });
  }
  adminContent() {
    return this.get<AdminContent>("/admin/content");
  }
  setTerminalEnabled(roomId: number, enabled: boolean) {
    return this.send<TerminalSettingView>("PUT", `/admin/rooms/${roomId}/terminal`, { enabled });
  }
  setRoomActivity(roomId: number, activity: RoomActivity | null) {
    return this.send<{ roomId: number; activity: RoomActivity | null }>("PUT", `/admin/rooms/${roomId}/activity`, {
      activity,
    });
  }
  finalContent() {
    return this.get<FinalContentView>("/admin/final/content");
  }
  /** The whole final content file (format 2) as JSON text; refused whole with every problem listed. */
  importFinalContent(json: string) {
    return this.api.request<FinalContentView>("PUT", EVENT + "/admin/final/content", json, { "Content-Type": "application/json" });
  }
  /** The final's own terminal package (admin only): summary, upload (multipart ZIP), removal. */
  finalTerminalPackage() {
    return this.get<FinalTerminalBundleView>("/admin/final/terminal-bundle");
  }
  uploadFinalTerminalPackage(zip: File) {
    const form = new FormData();
    form.append("file", zip, zip.name);
    return this.send<FinalTerminalBundleView>("PUT", "/admin/final/terminal-bundle", form);
  }
  deleteFinalTerminalPackage() {
    return this.send<FinalTerminalBundleView>("DELETE", "/admin/final/terminal-bundle");
  }
  finalTimer() {
    return this.get<FinalTimerView>("/admin/final/timer");
  }
  pauseFinal() {
    return this.send<FinalTimerView>("POST", "/admin/final/timer/pause", {});
  }
  resumeFinal() {
    return this.send<FinalTimerView>("POST", "/admin/final/timer/resume", {});
  }
  addFinalTime(seconds: number, key: string) {
    return this.api.request<FinalTimerView>("POST", EVENT + "/admin/final/timer/add", { seconds }, { "Idempotency-Key": key });
  }
  scoreboard() {
    return this.get<RankingRow[]>("/admin/scoreboard");
  }
  finalBoard() {
    return this.get<FinalBoardRow[]>("/admin/final/board");
  }
  /** Every recovery answer, the Arduino bug list and the right option (organizers only). */
  recoverySolutions() {
    return this.get<RecoverySolutions>("/admin/final/recovery-solutions");
  }
  /** The Final Investigation Reports of every team (organizers only). */
  finalReports() {
    return this.get<FinalReportRow[]>("/admin/final/reports");
  }
  adminDeductions() {
    return this.get<DeductionView[]>("/admin/deductions");
  }

  // ---- Admin: overrides ----
  swapPreview(teamA: number, teamB: number) {
    return this.send<RotationPreviewView[]>("POST", "/admin/overrides/swap-preview", { teamA, teamB });
  }
  swapTeams(teamA: number, teamB: number, reason: string) {
    return this.send<OverrideOutcome>("POST", "/admin/overrides/swap", { teamA, teamB, reason });
  }
  movePreview(teamId: number, group: GroupCode) {
    return this.send<RotationPreviewView[]>("POST", "/admin/overrides/move-preview", { teamId, group });
  }
  moveTeam(teamId: number, group: GroupCode, reason: string, confirmImpact: boolean) {
    return this.send<OverrideOutcome>("PUT", `/admin/teams/${teamId}/group`, { group, reason, confirmImpact });
  }
  roomExceptionPreview(teamId: number, roomId: number) {
    return this.send<string[]>("POST", "/admin/overrides/room-exception/preview", { teamId, roomId });
  }
  setRoomException(teamId: number, roomId: number, reason: string) {
    return this.send<OverrideOutcome>("PUT", "/admin/overrides/room-exception", { teamId, roomId, reason });
  }
  clearRoomException(teamId: number, reason: string) {
    return this.send<OverrideOutcome>("DELETE", `/admin/overrides/room-exception/${teamId}`, { reason });
  }
  manualClue(teamId: number, roomId: number, action: "GRANT" | "REVOKE", alsoRemoveRecovery: boolean, reason: string) {
    return this.send<OverrideOutcome>("POST", "/admin/overrides/clue", {
      teamId,
      roomId,
      action,
      alsoRemoveRecovery,
      reason,
    });
  }
  setDisqualified(teamId: number, disqualified: boolean, reason: string) {
    const action = disqualified ? "disqualify" : "reinstate";
    return this.send<OverrideOutcome>("POST", `/admin/teams/${teamId}/${action}`, { reason });
  }
  resetFinal(reason: string) {
    return this.send<OverrideOutcome>("POST", "/admin/reset/final", { reason });
  }
  resetTeam(teamId: number, reason: string) {
    return this.send<OverrideOutcome>("POST", `/admin/reset/team/${teamId}`, { reason });
  }
  /** The password is checked by the server and never stored by the page. */
  resetEvent(reason: string, confirmText: string, password: string) {
    return this.send<OverrideOutcome>("POST", "/admin/reset/event", { reason, confirmText, password });
  }
  takeSnapshot(reason: string) {
    return this.send<OverrideOutcome>("POST", "/admin/snapshots", { reason });
  }
  snapshots() {
    return this.get<SnapshotView[]>("/admin/snapshots");
  }

  /** Saves GET /admin/snapshots/{id}/download as event-snapshot-<id>.json. */
  async downloadSnapshot(snapshotId: number): Promise<void> {
    const json = await firstValueFrom(
      this.http.get(`${BASE}${EVENT}/admin/snapshots/${snapshotId}/download`, { responseType: "text" }),
    );
    const url = URL.createObjectURL(new Blob([json], { type: "application/json" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `event-snapshot-${snapshotId}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  /** Downloads GET /admin/export.csv as a file named devfest-event-results.csv. */
  async downloadExportCsv(): Promise<void> {
    const csv = await firstValueFrom(
      this.http.get(`${BASE}${EVENT}/admin/export.csv`, {
        responseType: "text",
      }),
    );
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "devfest-event-results.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  private get<T>(path: string): Promise<T> {
    return this.api.request<T>("GET", EVENT + path);
  }

  private send<T>(method: string, path: string, body?: unknown): Promise<T> {
    return this.api.request<T>(method, EVENT + path, body);
  }

  private postIdempotent<T>(path: string, body: unknown, key: string) {
    return this.api.request<T>("POST", path, body, { "Idempotency-Key": key });
  }
}

/** Offers `content` to the browser as a downloaded file. */
export function saveFile(content: string, type: string, fileName: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}
