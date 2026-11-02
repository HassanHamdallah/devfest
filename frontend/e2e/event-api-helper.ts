import { APIRequestContext, Page, request } from "@playwright/test";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

// Test-only shortcuts for steps the browser flow does not need to exercise through the UI.
export const API_BASE = "http://127.0.0.1:8082/api/v1";

export type RoomCode = "AI" | "PROBLEM_SOLVING" | "LINUX" | "HARDWARE";

/** Practice clue keys. They exist only in test code; managers set every key at runtime. */
export const PRACTICE_KEYS: Record<RoomCode, string> = {
  AI: "WHITE VAN",
  PROBLEM_SOLVING: "RED CLAY",
  LINUX: "WEST TOWER",
  HARDWARE: "+970-59-881",
};

/**
 * Restarts the rehearsal backend on freshly reset devfest_test data (same accounts and passwords), so every
 * test starts from an untouched event and the suite can repeat (--repeat-each). The UI server keeps running.
 */
export function resetRehearsal() {
  execFileSync(
    "pwsh",
    ["-NoProfile", "-File", path.resolve("..", "infrastructure", "start-event-rehearsal.ps1"), "-ResetBackend"],
    { stdio: "inherit" },
  );
}

export interface RehearsalAccounts {
  adminEmail: string;
  adminPassword: string;
  seedPassword: string;
  managers: Record<RoomCode, string>;
  members: string[];
}

interface AdminRoomCard {
  id: number;
  code: RoomCode;
  open: boolean;
}

interface TeamBoardSummary {
  cluesCollected: number;
  rooms: { code: RoomCode; clueCollected: boolean }[];
}

interface Receipt {
  id: string;
  clueCorrect: boolean;
}

export function readRehearsalAccounts(): RehearsalAccounts {
  const file = path.resolve("..", ".local", "event-rehearsal.json");
  // PowerShell may write a byte-order mark, which JSON.parse rejects.
  return JSON.parse(fs.readFileSync(file, "utf8").replace(/^﻿/, ""));
}

export class EventApiSession {
  private constructor(
    private readonly http: APIRequestContext,
    private readonly accessToken: string,
  ) {}

  static async signIn(email: string, password: string) {
    const http = await request.newContext();
    const response = await http.post(`${API_BASE}/auth/login`, {
      data: { email, password },
    });
    if (!response.ok()) {
      throw new Error(`API sign-in for ${email} failed: ${response.status()}`);
    }
    const tokens: { accessToken: string } = await response.json();
    return new EventApiSession(http, tokens.accessToken);
  }

  /**
   * Reuses the access token of a signed-in browser tab. Team accounts keep one session (latest sign-in
   * wins), so a second API sign-in would sign the browser out.
   */
  static async fromPage(page: Page) {
    const accessToken = await page.evaluate(() => sessionStorage.getItem("devfest.access"));
    if (!accessToken) throw new Error("The page has no signed-in session.");
    return new EventApiSession(await request.newContext(), accessToken);
  }

  async send<T>(method: string, route: string, body?: unknown, idempotencyKey?: string): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.accessToken}` };
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
    const response = await this.http.fetch(`${API_BASE}${route}`, { method, headers, data: body });
    if (!response.ok()) {
      throw new Error(`${method} ${route} failed: ${response.status()} ${await response.text()}`);
    }
    return (await response.json()) as T;
  }

  /** The HTTP status of a GET, for checks that expect a refusal (no console noise in the page). */
  async status(route: string): Promise<number> {
    const response = await this.http.get(`${API_BASE}${route}`, {
      headers: { Authorization: `Bearer ${this.accessToken}` },
    });
    return response.status();
  }

  dispose() {
    return this.http.dispose();
  }
}

export class EventAdminShortcuts {
  constructor(private readonly admin: EventApiSession) {}

  /** Moves to the round; a running round begins ASSIGNED (its timer does not start). */
  setRound(round: number) {
    return this.admin.send("POST", "/event/admin/round", { round });
  }

  startTimer() {
    return this.admin.send("POST", "/event/admin/round/timer/start");
  }

  /** Ends the running round at once: its phase becomes Time's up. */
  endRound() {
    return this.admin.send("POST", "/event/admin/round/timer/end");
  }

  pauseTimer() {
    return this.admin.send("POST", "/event/admin/round/timer/pause");
  }

  resumeTimer() {
    return this.admin.send("POST", "/event/admin/round/timer/resume");
  }

  addTime(deltaSeconds: number) {
    return this.admin.send("POST", "/event/admin/round/timer/adjust", { deltaSeconds }, randomUUID());
  }

  openFinal() {
    return this.admin.send("POST", "/event/admin/final/open");
  }

  setPublication(published: boolean) {
    return this.admin.send("PUT", "/event/admin/publication", { published });
  }

  /** Moves to the round and starts its timer, so teams can submit right away. */
  async startRound(round: number) {
    await this.setRound(round);
    if (round >= 1 && round <= 4) await this.startTimer();
  }

  async openRoom(code: RoomCode) {
    const room = await this.room(code);
    await this.admin.send("POST", `/event/admin/rooms/${room.id}/open`);
  }

  async closeAllRooms() {
    for (const room of await this.rooms()) {
      if (room.open) await this.admin.send("POST", `/event/admin/rooms/${room.id}/close`);
    }
  }

  async roomId(code: RoomCode) {
    return (await this.room(code)).id;
  }

  /** The room's team activity; null gives the room the plain answer form (technical output + clue). */
  async setRoomActivity(code: RoomCode, activity: "AI_QUIZ" | null) {
    await this.admin.send("PUT", `/event/admin/rooms/${await this.roomId(code)}/activity`, { activity });
  }

  private async room(code: RoomCode) {
    const room = (await this.rooms()).find((candidate) => candidate.code === code);
    if (!room) throw new Error(`Room ${code} is missing from the admin board.`);
    return room;
  }

  private async rooms() {
    const board = await this.admin.send<{ rooms: AdminRoomCard[] }>("GET", "/event/admin/board");
    return board.rooms;
  }
}

interface ManagerRoom {
  room: { id: number; recoveryPrompt: string; recoveryAnswer: string; version: number };
}

/** A room manager setting the clue key the way the manager board does (PUT /rooms/{id}/content). */
export class EventManagerShortcuts {
  constructor(private readonly manager: EventApiSession) {}

  async setClueKey(clueKey: string) {
    const { roomId } = await this.manager.send<{ roomId: number }>("GET", "/event/home");
    const { room } = await this.manager.send<ManagerRoom>("GET", `/event/rooms/${roomId}/board`);
    await this.manager.send("PUT", `/event/rooms/${roomId}/content`, {
      clueKey,
      recoveryPrompt: room.recoveryPrompt,
      recoveryAnswer: room.recoveryAnswer,
      version: room.version,
    });
  }
}

/** Each listed room's manager signs in through the API and sets the room's practice key. */
export async function setPracticeKeys(accounts: RehearsalAccounts, rooms: readonly RoomCode[]) {
  for (const code of rooms) {
    const session = await EventApiSession.signIn(accounts.managers[code], accounts.seedPassword);
    try {
      await new EventManagerShortcuts(session).setClueKey(PRACTICE_KEYS[code]);
    } finally {
      await session.dispose();
    }
  }
}

export class EventTeamShortcuts {
  constructor(private readonly team: EventApiSession) {}

  submitClue(roomId: number, clueAnswer: string) {
    return this.team.send<Receipt>(
      "POST",
      `/event/team/rooms/${roomId}/submissions`,
      { technicalOutput: "Submitted by the browser rehearsal helper.", clueAnswer },
      randomUUID(),
    );
  }

  board() {
    return this.team.send<TeamBoardSummary>("GET", "/event/team/board");
  }
}
