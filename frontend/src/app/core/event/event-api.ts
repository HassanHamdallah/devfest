import { Injectable, inject } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { firstValueFrom } from "rxjs";
import { Api, BASE } from "../api";
import {
  AccountView,
  AccusationRequest,
  AccusationResult,
  AdminBoard,
  AdminContent,
  AdminContentRequest,
  AiRoomActivity,
  AiRoomResults,
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
  PenaltyRequest,
  PenaltyView,
  QuizDocument,
  QuizEditorView,
  QuizQuestionRequest,
  QuizRepairRequest,
  QuizSavedReceipt,
  Receipt,
  RecoveryRequest,
  RecoveryResult,
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
  SuspectRequest,
  SuspectView,
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
export const RECOVERY_PATH = `${EVENT}/team/final/recovery`;
export const ACCUSATION_PATH = `${EVENT}/team/final/accusations`;

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
  submitRecovery(body: RecoveryRequest, key: string) {
    return this.postIdempotent<RecoveryResult>(RECOVERY_PATH, body, key);
  }
  submitAccusation(body: AccusationRequest, key: string) {
    return this.postIdempotent<AccusationResult>(ACCUSATION_PATH, body, key);
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
  /** 404 unless the room has the AI quiz and is the team's room this round (rules §19). */
  teamActivity(roomId: number) {
    return this.get<AiRoomActivity>(`/team/rooms/${roomId}/activity`);
  }
  /** The key is the answer's: a retry with the same key and body gets the same receipt. */
  saveQuizAnswer(roomId: number, questionId: number, optionId: number, key: string) {
    return this.postIdempotent<QuizSavedReceipt>(
      `${EVENT}/team/rooms/${roomId}/activity/answers`,
      { questionId, optionId },
      key,
    );
  }
  submitQuizRepair(roomId: number, repairId: number, snippetIds: number[], key: string) {
    return this.postIdempotent<QuizSavedReceipt>(
      `${EVENT}/team/rooms/${roomId}/activity/repairs/${repairId}`,
      { snippetIds },
      key,
    );
  }
  finishQuizMcq(roomId: number) {
    return this.send<AiRoomActivity>("POST", `/team/rooms/${roomId}/activity/finish-mcq`, {});
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
  addPenalty(roomId: number, body: PenaltyRequest) {
    return this.send<PenaltyView>("POST", `/rooms/${roomId}/penalties`, body);
  }
  voidPenalty(penaltyId: number, reason: string) {
    return this.send<PenaltyView>("POST", `/penalties/${penaltyId}/void`, {
      reason,
    });
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
  deleteTerminalBundle(roomId: number) {
    return this.send<TerminalFileList>("DELETE", `/rooms/${roomId}/terminal-bundle`);
  }
  /**
   * The room's package as gzip bytes. The server answers with an ETag and "no-cache", so the browser revalidates
   * and a restarted terminal gets a 304 instead of the bytes.
   */
  terminalBundle(roomId: number, scope: "team" | "manager"): Promise<ArrayBuffer> {
    const path = scope === "team" ? `/team/rooms/${roomId}/terminal-bundle` : `/rooms/${roomId}/terminal-bundle`;
    return firstValueFrom(this.http.get(`${BASE}${EVENT}${path}`, { responseType: "arraybuffer" }));
  }

  quizEditor(roomId: number) {
    return this.get<QuizEditorView>(`/rooms/${roomId}/quiz`);
  }
  createQuizQuestion(roomId: number, body: QuizQuestionRequest) {
    return this.send<QuizEditorView>("POST", `/rooms/${roomId}/quiz/questions`, body);
  }
  updateQuizQuestion(roomId: number, questionId: number, body: QuizQuestionRequest) {
    return this.send<QuizEditorView>("PUT", `/rooms/${roomId}/quiz/questions/${questionId}`, body);
  }
  deleteQuizQuestion(roomId: number, questionId: number) {
    return this.send<QuizEditorView>("DELETE", `/rooms/${roomId}/quiz/questions/${questionId}`);
  }
  createQuizRepair(roomId: number, body: QuizRepairRequest) {
    return this.send<QuizEditorView>("POST", `/rooms/${roomId}/quiz/repairs`, body);
  }
  updateQuizRepair(roomId: number, repairId: number, body: QuizRepairRequest) {
    return this.send<QuizEditorView>("PUT", `/rooms/${roomId}/quiz/repairs/${repairId}`, body);
  }
  deleteQuizRepair(roomId: number, repairId: number) {
    return this.send<QuizEditorView>("DELETE", `/rooms/${roomId}/quiz/repairs/${repairId}`);
  }
  /** `passScore` null keeps the room's pass score (it is locked once a team was evaluated). */
  saveQuizSettings(roomId: number, mcqMinutes: number, passScore: number | null) {
    return this.send<QuizEditorView>("PUT", `/rooms/${roomId}/quiz/settings`, { mcqMinutes, passScore });
  }
  exportQuiz(roomId: number) {
    return this.get<QuizDocument>(`/rooms/${roomId}/quiz/export`);
  }
  /** Replaces every question and repair of the room (refused once a team answered). */
  importQuiz(roomId: number, document: unknown) {
    return this.send<QuizEditorView>("POST", `/rooms/${roomId}/quiz/import`, document);
  }
  quizResults(roomId: number) {
    return this.get<AiRoomResults>(`/rooms/${roomId}/quiz/results`);
  }

  /** Downloads GET /rooms/{id}/quiz/results.csv as ai-room-results.csv. */
  async downloadQuizResultsCsv(roomId: number): Promise<void> {
    const csv = await firstValueFrom(
      this.http.get(`${BASE}${EVENT}/rooms/${roomId}/quiz/results.csv`, { responseType: "text" }),
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
  saveAdminContent(body: AdminContentRequest) {
    return this.send<AdminContent>("PUT", "/admin/content", body);
  }
  createSuspect(body: SuspectRequest) {
    return this.send<SuspectView>("POST", "/admin/suspects", body);
  }
  updateSuspect(suspectId: number, body: SuspectRequest) {
    return this.send<SuspectView>("PUT", `/admin/suspects/${suspectId}`, body);
  }
  deleteSuspect(suspectId: number) {
    return this.send<AdminContent>("DELETE", `/admin/suspects/${suspectId}`);
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
  timeAdjustment(teamId: number, minutes: number, reason: string) {
    return this.send<OverrideOutcome>("POST", "/admin/overrides/time-adjustment", { teamId, minutes, reason });
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
