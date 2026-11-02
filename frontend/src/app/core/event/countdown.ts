import { RoundClock } from "./event-models";

/** Server minus client time in ms, measured when a response carrying `serverNow` arrived. */
export function clockOffsetMs(
  serverNow: string,
  clientTimeAtFetch: number,
): number {
  const server = Date.parse(serverNow);
  return Number.isNaN(server) ? 0 : server - clientTimeAtFetch;
}

/** Rooms only run in rounds 1..4 and only once the round has a start time. */
export function isRoundRunning(clock: RoundClock): boolean {
  return (
    clock.currentRound >= 1 &&
    clock.currentRound <= 4 &&
    clock.roundStartedAt !== null
  );
}

/**
 * Whole seconds left in the running round, never below 0; null when no round is running.
 * remaining = roundStartedAt + roundMinutes·60 s − (now + offset).
 */
export function remainingSeconds(
  clock: RoundClock,
  offsetMs: number,
  nowMs: number = Date.now(),
): number | null {
  if (!isRoundRunning(clock)) return null;
  const startedAt = Date.parse(clock.roundStartedAt as string);
  if (Number.isNaN(startedAt)) return null;
  const endsAt = startedAt + clock.roundMinutes * 60_000;
  return Math.max(0, Math.ceil((endsAt - (nowMs + offsetMs)) / 1000));
}

/** 754 → "12:34"; minutes are not capped at 59 (a 180 minute round shows "180:00"). */
export function formatMinutesSeconds(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

/** "12:34 left", "Time is up", or "" when no round is running. */
export function countdownLabel(seconds: number | null): string {
  if (seconds === null) return "";
  return seconds > 0 ? `${formatMinutesSeconds(seconds)} left` : "Time is up";
}
