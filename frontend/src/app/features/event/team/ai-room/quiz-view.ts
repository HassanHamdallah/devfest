import { AiRoomActivity, QuizStage } from "../../../../core/event/event-models";

/** Short headline per stage; the activity card shows it above the current part. */
export const STAGE_TITLES: Record<QuizStage, string> = {
  NOT_STARTED: "Waiting for the timer",
  MCQ: "Multiple-choice questions",
  REPAIRS: "Code repairs",
  TIME_UP: "Time is up",
};

/**
 * MCQ seconds left now. The server computed `mcqRemainingSeconds` at `serverNow`; it only moves while the round
 * runs, by the local time since the response arrived (independent of the device clock's offset).
 */
export function mcqSecondsLeft(activity: AiRoomActivity, receivedAtMs: number, nowMs: number): number {
  if (activity.stage !== "MCQ" || activity.phase !== "RUNNING") return activity.mcqRemainingSeconds;
  const elapsed = Math.max(0, Math.floor((nowMs - receivedAtMs) / 1000));
  return Math.max(0, activity.mcqRemainingSeconds - elapsed);
}

/** Index of the first unanswered question, or 0 when every question is answered. */
export function firstUnanswered(activity: AiRoomActivity): number {
  const index = activity.questions.findIndex((q) => q.selectedOptionId === null);
  return index < 0 ? 0 : index;
}

/** The next unanswered question after `from` (wrapping around), or null when all are answered. */
export function nextUnanswered(activity: AiRoomActivity, from: number): number | null {
  const count = activity.questions.length;
  for (let step = 1; step <= count; step++) {
    const index = (from + step) % count;
    if (activity.questions[index].selectedOptionId === null) return index;
  }
  return null;
}

/** Moves one item of a copy of `ids` by `delta` places (clamped). */
export function moveItem(ids: readonly number[], index: number, delta: number): number[] {
  const target = Math.min(ids.length - 1, Math.max(0, index + delta));
  const next = [...ids];
  const [item] = next.splice(index, 1);
  next.splice(target, 0, item);
  return next;
}

/** True when `candidate` holds exactly the ids of `server` (a stored local order is still usable). */
export function samePieces(candidate: unknown, server: readonly number[]): candidate is number[] {
  if (!Array.isArray(candidate) || candidate.length !== server.length) return false;
  const expected = new Set(server);
  return candidate.every((id) => typeof id === "number" && expected.has(id)) && new Set(candidate).size === server.length;
}
