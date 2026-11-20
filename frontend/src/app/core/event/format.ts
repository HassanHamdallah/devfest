import { JudgeStatus, RoomActivity, TeamRoomStatus } from "./event-models";

const EMPTY = "—";

/** Seconds → "m:ss" (2400 → "40:00"); "—" when absent. */
export function formatClock(seconds: number | null): string {
  if (seconds === null) return EMPTY;
  const safe = Math.max(0, Math.floor(seconds));
  return `${Math.floor(safe / 60)}:${pad(safe % 60)}`;
}

/** Active seconds → "h:mm:ss"; "—" when absent. */
export function formatAdjustedSeconds(seconds: number | null): string {
  if (seconds === null) return EMPTY;
  const safe = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const rest = safe % 60;
  return `${hours}:${pad(minutes)}:${pad(rest)}`;
}

/** Points with at most 2 decimals and no trailing zeros: 14.85 → "14.85", 15 → "15", 7.5 → "7.5". */
export function formatPoints(points: number | null): string {
  if (points === null || points === undefined) return EMPTY;
  return String(Math.round(points * 100) / 100);
}

/** ISO instant → local time with seconds, e.g. "14:05:09"; "—" when absent. */
export function formatTime(iso: string | null): string {
  const date = parse(iso);
  return date
    ? date.toLocaleTimeString([], {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      })
    : EMPTY;
}

/** ISO instant → local date and time, e.g. "Oct 23, 2026, 14:05:09"; "—" when absent. */
export function formatDateTime(iso: string | null): string {
  const date = parse(iso);
  return date
    ? date.toLocaleString([], { dateStyle: "medium", timeStyle: "medium" })
    : EMPTY;
}

/** Admin status text for `current_round`: "Not started" / "Round 2 of 4" / "Rooms finished". */
export function roundStatusLabel(currentRound: number): string {
  if (currentRound <= 0) return "Not started";
  if (currentRound >= 5) return "Rooms finished";
  return `Round ${currentRound} of 4`;
}

export const FINAL_STAGE_LABELS: Record<string, string> = {
  LOCKED: "Not open",
  RECOVERY: "Recovery",
  IN_FINAL: "In final",
  COMPLETED: "Completed",
};

export const JUDGE_STATUS_LABELS: Record<JudgeStatus, string> = {
  PENDING: "Pending",
  PASS: "Passed",
  FAIL: "Failed",
};

export const TEAM_ROOM_STATUS_LABELS: Record<TeamRoomStatus, string> = {
  NOT_STARTED: "Not started",
  SUBMITTED: "Submitted",
  PASSED: "Passed",
  FAILED: "Failed",
};

/**
 * The team's status label for a room. A room without an answer form (the AI mission and the contest results room)
 * records the team's result itself, so there is nothing the team "submitted": its status reads "Room complete".
 */
export function teamRoomStatusLabel(status: TeamRoomStatus, activity: RoomActivity | null): string {
  const noForm = activity === "AI_QUIZ" || activity === "CONTEST_RESULTS";
  return status === "SUBMITTED" && noForm ? "Room complete" : TEAM_ROOM_STATUS_LABELS[status];
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

function parse(iso: string | null): Date | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}
