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

/** The plaintexts the server derives from docs/final/crypto-content.json. Test code only: no page ever shows them. */
export const LAYER_ANSWERS = [52, 17, 8, 17] as const;
export const RECOVERY_QUESTION = "How many bits are in a byte?";
export const RECOVERY_ANSWER = "EIGHT BITS";
/** Index 1 of the suspects in the content file (the correct one) and the first (a wrong one). */
export const CORRECT_SUSPECT = "Leila Nasser";
export const WRONG_SUSPECT = "Kareem Shehab";
export const SUSPECT_NAMES = ["Kareem Shehab", "Leila Nasser", "Tariq Al-Khatib", "Nadia Murad", "Farid Mansour", "Mazen Haddad"];

/** The shipped content file with the test Recovery Challenge in place of its placeholder. */
export function finalContentJson(): string {
  const file = path.resolve("..", "docs", "final", "crypto-content.json");
  return fs
    .readFileSync(file, "utf8")
    .replace("PLACEHOLDER - replace this with the real Recovery Challenge question before the event.", RECOVERY_QUESTION)
    .replace("PLACEHOLDER-REPLACE-ME", RECOVERY_ANSWER);
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

  layer(layer: number, value: string) {
    return this.team.send<FinalAnswer>("POST", `/event/team/final/layers/${layer}`, { value }, this.newKey());
  }

  recover(answer: string) {
    return this.team.send<{ correct: boolean }>("POST", "/event/team/final/recovery", { answer }, this.newKey());
  }

  verdict(layer: number, verdict: "YES" | "NO") {
    return this.team.send<FinalAnswer>("POST", `/event/team/final/verdicts/${layer}`, { verdict });
  }

  suspect(suspectId: number) {
    return this.team.send<FinalAnswer>("POST", "/event/team/final/suspect", { suspectId });
  }

  /** Unlock, start, the four layers, both right verdicts and the right suspect. */
  async playAll() {
    await this.unlock();
    await this.start();
    for (let layer = 1; layer <= 4; layer++) await this.layer(layer, String(LAYER_ANSWERS[layer - 1]));
    await this.verdict(3, "NO");
    await this.verdict(4, "YES");
    await this.suspect(SUSPECT_NAMES.indexOf(CORRECT_SUSPECT) + 1);
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
  cluesCollected: number;
  waitSeconds: number;
  notebook: { id: string }[];
}

/** One team's row of the admin's scoreboard, as the API returns it. */
export interface ScoreRow {
  rank: number | null;
  scores: {
    teamName: string;
    total: number;
    roomsTotal: number;
    rooms: { code: string; base: number; score: number; hintDeduction: number; cheatingDeduction: number }[];
    finalScore: { status: string; layers: number; verdicts: number; suspect: number; time: number; total: number };
  };
}
