import { APIRequestContext, request } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

// Test-only shortcuts for steps the browser flow does not need to exercise through the UI.
export const API_BASE = "http://127.0.0.1:8082/api/v1";

export type RoomCode = "AI" | "PROBLEM_SOLVING" | "LINUX" | "HARDWARE";

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

  async send<T>(method: string, route: string, body?: unknown, idempotencyKey?: string): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.accessToken}` };
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
    const response = await this.http.fetch(`${API_BASE}${route}`, { method, headers, data: body });
    if (!response.ok()) {
      throw new Error(`${method} ${route} failed: ${response.status()} ${await response.text()}`);
    }
    return (await response.json()) as T;
  }

  dispose() {
    return this.http.dispose();
  }
}

export class EventAdminShortcuts {
  constructor(private readonly admin: EventApiSession) {}

  setRound(round: number) {
    return this.admin.send("POST", "/event/admin/round", { round });
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
