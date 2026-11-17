import fs from "node:fs";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  EventTeamShortcuts,
  PRACTICE_KEYS,
  RehearsalAccounts,
  RoomCode,
} from "./event-api-helper";

/** Rooms in position order; group g visits position (g + round - 1) mod 4 in round `round`. */
export const ROOMS: readonly RoomCode[] = ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"];

const SHIPMENT = path.resolve("..", "docs", "final", "last-shipment");
const CONTENT_FILE = path.resolve("..", "docs", "final", "last-shipment-content.json");

interface ShipmentFile {
  mission: { title: string; intro: string; phase1: string; phase2: string };
  clues: { number: number; title: string; encFile: string; rsaFile: string; keyReference: string; report: string }[];
  keyIndex: { reference: string; key: string }[];
  suspects: { id: string; name: string; position: string; statement: string }[];
  correctSuspect: string;
  recovery: { question: string; acceptedAnswers: string[] };
}

/** The shipped content file, as the admin imports it (texts and answers of the specs come from it). */
export const SHIPMENT_CONTENT = JSON.parse(fs.readFileSync(CONTENT_FILE, "utf8")) as ShipmentFile;
export const RECOVERY_QUESTION = SHIPMENT_CONTENT.recovery.question;
export const RECOVERY_ANSWER = SHIPMENT_CONTENT.recovery.acceptedAnswers[0];
export const CORRECT_SUSPECT = SHIPMENT_CONTENT.correctSuspect;
export const WRONG_SUSPECT = SHIPMENT_CONTENT.suspects.find((s) => s.id !== CORRECT_SUSPECT)!.id;
export const suspectName = (id: string) => SHIPMENT_CONTENT.suspects.find((s) => s.id === id)!.name;

/** keys/clueN_secret.txt exactly as the organizers have them (with their line ending). Test code only. */
export const PASSPHRASES: readonly string[] = [1, 2, 3, 4].map((n) =>
  fs.readFileSync(path.join(SHIPMENT, "keys", `clue${n}_secret.txt`), "ascii"),
);

/** The key letter (A to D) the content's key index gives clue n. */
export function keyLetter(n: number): string {
  const reference = SHIPMENT_CONTENT.clues[n - 1].keyReference;
  return SHIPMENT_CONTENT.keyIndex.find((entry) => entry.reference === reference)!.key;
}

/** The final terminal package the organizers build (docs/final/last-shipment/final-package.zip). */
export function finalPackageZip(): Buffer {
  return fs.readFileSync(path.join(SHIPMENT, "final-package.zip"));
}

/** The content file; `phase1Minutes` replaces the decryption phase's length (a spec that waits for the switch uses 1 to 3). */
export function finalContentJson(phase1Minutes?: number): string {
  const raw = fs.readFileSync(CONTENT_FILE, "utf8");
  return phase1Minutes === undefined ? raw : JSON.stringify({ ...JSON.parse(raw), phase1Minutes });
}

/** The group a seeded team (Team 01..16) is in: blocks of four, A to D. */
export function groupIndexOf(teamNumber: number): number {
  return Math.floor(((teamNumber - 1) * 4) / 16);
}

export function teamName(teamNumber: number): string {
  return `Team ${String(teamNumber).padStart(2, "0")}`;
}

export interface TeamPlan {
  /** 1..16, as in "Team 01". */
  number: number;
  /** The rooms whose clue this team collects. */
  rooms: readonly RoomCode[];
}

/**
 * Plays the four rounds through the API so that each planned team collects exactly its rooms' clues (everyone
 * answers with the room's practice key in the room its group visits that round), then finishes the rooms.
 */
export async function collectClues(accounts: RehearsalAccounts, admin: EventApiSession, plans: readonly TeamPlan[]) {
  const control = new EventAdminShortcuts(admin);
  const sessions = new Map<number, EventApiSession>();
  try {
    for (const plan of plans) {
      sessions.set(plan.number, await EventApiSession.signIn(accounts.members[plan.number - 1], accounts.seedPassword));
    }
    for (let round = 1; round <= 4; round++) {
      await control.startRound(round);
      for (const code of ROOMS) await control.openRoom(code);
      for (const plan of plans) {
        const code = ROOMS[(groupIndexOf(plan.number) + round - 1) % 4];
        if (!plan.rooms.includes(code)) continue;
        const member = new EventTeamShortcuts(sessions.get(plan.number)!);
        const receipt = await member.submitClue(await control.roomId(code), PRACTICE_KEYS[code]);
        if (!receipt.clueCorrect) throw new Error(`${teamName(plan.number)} did not collect the ${code} clue.`);
      }
    }
    await control.setRound(5);
    await control.closeAllRooms();
  } finally {
    await Promise.all([...sessions.values()].map((session) => session.dispose()));
  }
}

/** The four unlock inputs in room order, with the practice keys. */
export const UNLOCK_CODES = ROOMS.map((code) => PRACTICE_KEYS[code]);

/** A team's final through its API session, for the steps a spec does not need to click through. */
export class FinalShortcuts {
  constructor(private readonly team: EventApiSession, private readonly newKey: () => string) {}

  view() {
    return this.team.send<FinalView>("GET", "/event/team/final");
  }

  unlock() {
    return this.team.send<FinalAnswer>("POST", "/event/team/final/unlock", { codes: UNLOCK_CODES }, this.newKey());
  }

  start() {
    return this.team.send<FinalAnswer>("POST", "/event/team/final/start", {});
  }

  passphrase(clue: number, passphrase: string) {
    return this.team.send<FinalAnswer>("POST", `/event/team/final/clues/${clue}/passphrase`, { passphrase }, this.newKey());
  }

  recover(answer: string) {
    return this.team.send<{ correct: boolean }>("POST", "/event/team/final/recovery", { answer }, this.newKey());
  }

  suspect(suspectId: string) {
    return this.team.send<FinalAnswer>("POST", "/event/team/final/suspect", { suspectId });
  }

  /** Waits (polling the view) until the final's investigation phase has opened; the suspects can be submitted from then on. */
  async waitForInvestigation(timeoutMs = 240000) {
    const deadline = Date.now() + timeoutMs;
    while ((await this.view()).phase !== "INVESTIGATION") {
      if (Date.now() > deadline) throw new Error("The investigation phase did not open in time.");
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
  }

  /** Unlock, start, the four right passphrases, then (once the investigation phase opens) the right suspect. */
  async playAll() {
    await this.unlock();
    await this.start();
    for (let clue = 1; clue <= 4; clue++) await this.passphrase(clue, PASSPHRASES[clue - 1]);
    await this.waitForInvestigation();
    await this.suspect(CORRECT_SUSPECT);
  }
}

export interface FinalAnswer {
  accepted: boolean;
  message: string;
  waitSeconds: number;
  status: string;
  screen: string | null;
}

export interface FinalView {
  status: string;
  screen: string;
  remainingSeconds: number | null;
  timerPhase: string | null;
  phase: string | null;
  phase1Minutes: number | null;
  phase1EndsInSeconds: number | null;
  cluesCollected: number;
  waitSeconds: number;
  clues: { number: number; solved: boolean; waitSeconds: number; report: string | null }[];
  suspects: { id: string }[];
  terminal: { sha256: string } | null;
  submitted: boolean;
}

/** One team's row of the admin's scoreboard, as the API returns it. */
export interface ScoreRow {
  rank: number | null;
  scores: {
    teamName: string;
    total: number;
    roomsTotal: number;
    rooms: { code: string; base: number; score: number; hintDeduction: number; cheatingDeduction: number }[];
    finalScore: { status: string; cluesSolved: number; clues: number; suspect: number; time: number; total: number };
  };
}
