import { RoundClock, RoundPhase } from "./event-models";

/** Under this many seconds left, a running countdown shows the warning color. */
export const COUNTDOWN_WARNING_SECONDS = 120;

/** Rounds 1..4 have a phase; rounds 0 and 5 do not. */
export function hasRoundTimer(clock: RoundClock): boolean {
  return clock.phase !== null && clock.remainingSeconds !== null;
}

/**
 * Whole seconds left now, never below 0; null outside rounds 1..4. The server computed `remainingSeconds` at
 * `serverNow`; only a RUNNING timer moves, by the local time elapsed since the response arrived
 * (`receivedAtMs`). That is independent of the client's clock offset, so after every refetch the value is
 * within a second of the server's.
 */
export function remainingSeconds(
  clock: RoundClock,
  receivedAtMs: number,
  nowMs: number = Date.now(),
): number | null {
  if (!hasRoundTimer(clock)) return null;
  const remaining = clock.remainingSeconds as number;
  if (clock.phase !== "RUNNING") return remaining;
  const elapsed = Math.max(0, Math.floor((nowMs - receivedAtMs) / 1000));
  return Math.max(0, remaining - elapsed);
}

/** The phase now: a running timer that reached 0 locally shows as TIME_UP before the next refetch. */
export function effectivePhase(
  clock: RoundClock,
  seconds: number | null,
): RoundPhase | null {
  if (clock.phase === "RUNNING" && seconds === 0) return "TIME_UP";
  return clock.phase;
}

/** 754 → "12:34"; minutes are not capped at 59 (a 180 minute round shows "180:00"). */
export function formatMinutesSeconds(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

export const PHASE_LABELS: Record<RoundPhase, string> = {
  ASSIGNED: "Assigned",
  RUNNING: "Running",
  PAUSED: "Paused",
  TIME_UP: "Time's up",
};

/** "12:34 left", "12:34 · paused", "25:00 · not started", "Time is up", or "" outside rounds 1..4. */
export function countdownLabel(
  phase: RoundPhase | null,
  seconds: number | null,
): string {
  if (phase === null || seconds === null) return "";
  switch (phase) {
    case "ASSIGNED":
      return `${formatMinutesSeconds(seconds)} · not started`;
    case "PAUSED":
      return `${formatMinutesSeconds(seconds)} · paused`;
    case "TIME_UP":
      return "Time is up";
    case "RUNNING":
      return seconds > 0 ? `${formatMinutesSeconds(seconds)} left` : "Time is up";
  }
}
