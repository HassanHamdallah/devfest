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
  CreateAccountRequest,
  CreateTeamRequest,
  EventHome,
  EventRevision,
  EventStateView,
  GenerateTeamsRequest,
  JudgementRequest,
  JudgementView,
  Organization,
  PenaltyRequest,
  PenaltyView,
  Receipt,
  RecoveryRequest,
  RecoveryResult,
  ResultsView,
  RoomBoard,
  RoomContentRequest,
  RoomContentView,
  RoomManagerView,
  RoomOpenView,
  RoomSubmissionRequest,
  SuspectRequest,
  SuspectView,
  TeamBoard,
  TeamCredential,
  TeamFinal,
  TeamView,
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

  // ---- Admin: control ----
  adminBoard() {
    return this.get<AdminBoard>("/admin/board");
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
