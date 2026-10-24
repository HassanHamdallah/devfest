import { JudgeStatus, PenaltyKind, TeamRoomStatus } from "./event-models";

const EMPTY = "—";

/** Adjusted final time in seconds → "h:mm:ss" (3725 → "1:02:05"); "—" when unfinished. */
export function formatAdjustedSeconds(seconds: number | null): string {
  if (seconds === null) return EMPTY;
  const safe = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const rest = safe % 60;
  return `${hours}:${pad(minutes)}:${pad(rest)}`;
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

/** 5 → "+5 min"; 0 → "0 min". */
export function formatPenaltyMinutes(minutes: number): string {
  return minutes > 0 ? `+${minutes} min` : `${minutes} min`;
}

/** Admin status text for `current_round`: "Not started" / "Round 2 of 4" / "Rooms finished". */
export function roundStatusLabel(currentRound: number): string {
  if (currentRound <= 0) return "Not started";
  if (currentRound >= 5) return "Rooms finished";
  return `Round ${currentRound} of 4`;
}

export const PENALTY_KIND_LABELS: Record<PenaltyKind, string> = {
  HINT: "Hint",
  MINOR_VIOLATION: "Minor violation",
  PROHIBITED_TOOL: "Prohibited tool",
  CHEATING: "Cheating",
  ANSWER_SHARING: "Answer sharing",
  RECOVERY: "Recovery",
  WRONG_ACCUSATION: "Wrong accusation",
  TIME_ADJUSTMENT: "Time adjustment",
};

export const PENALTY_KIND_MINUTES: Record<PenaltyKind, number> = {
  HINT: 2,
  MINOR_VIOLATION: 2,
  PROHIBITED_TOOL: 5,
  CHEATING: 5,
  ANSWER_SHARING: 5,
  RECOVERY: 5,
  WRONG_ACCUSATION: 3,
  /** Signed minutes per entry (set by the admin), so no fixed cost. */
  TIME_ADJUSTMENT: 0,
};

/** Kinds a manager or admin may add by hand; RECOVERY and WRONG_ACCUSATION are automatic. */
export const MANUAL_PENALTY_KINDS: readonly PenaltyKind[] = [
  "HINT",
  "MINOR_VIOLATION",
  "PROHIBITED_TOOL",
  "CHEATING",
  "ANSWER_SHARING",
];

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

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

function parse(iso: string | null): Date | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}
