import { formatMinutesSeconds } from "../../../../core/event/countdown";
import { AdminRoomCard } from "../../../../core/event/event-models";
import { EventApi } from "../../../../core/event/event-api";
import { UiButtonVariant } from "../../../../shared/ui/ui-button/ui-button";
import { ROOMS_FINISHED_ROUND } from "./control-view";

/** Every write the control page can make; child components emit these, the page runs them. */
export type ControlAction =
  | { kind: "openRoom"; room: AdminRoomCard }
  | { kind: "closeRoom"; room: AdminRoomCard }
  /** `secondsLeft`: time left in the current round, when it is running or paused (asks before moving on). */
  | { kind: "setRound"; round: number; secondsLeft?: number | null }
  /** `waiting`: rooms not confirmed ready; `warnings`: closed rooms and rooms without a clue key. */
  | { kind: "startTimer"; waiting: string[]; warnings: string[] }
  | { kind: "pauseTimer" }
  | { kind: "resumeTimer" }
  /** `key` is the Idempotency-Key: one per click, so a repeated request is applied once. */
  | { kind: "adjustTimer"; deltaSeconds: number; key: string }
  | { kind: "endRound" }
  | { kind: "saveRoundMinutes"; minutes: number }
  | { kind: "openFinal" }
  | { kind: "closeFinal" }
  | { kind: "setPublication"; published: boolean };

/** A room action the server refused, with its message (e.g. "Set the clue key for this room before opening it."). */
export interface RoomActionError {
  roomId: number;
  message: string;
}

export interface ConfirmationCopy {
  title: string;
  message: string;
  variant: UiButtonVariant;
}

/** Copy of the confirmation dialog, or null for actions that run immediately. */
export function confirmationFor(
  action: ControlAction,
): ConfirmationCopy | null {
  switch (action.kind) {
    case "closeRoom":
      return {
        title: `Close the ${action.room.name} room?`,
        message:
          "Teams scheduled in this room cannot submit answers until you open it again.",
        variant: "danger",
      };
    case "setRound":
      return roundConfirmation(action.round, action.secondsLeft ?? null);
    case "startTimer":
      return startConfirmation(action.waiting, action.warnings);
    case "endRound":
      return {
        title: "End the round now?",
        message:
          "The timer drops to 0:00 and teams can no longer submit answers for this round. You can add time afterwards.",
        variant: "danger",
      };
    case "openFinal":
      return {
        title: "Open the final?",
        message:
          "All teams see the final at the same time. Event content becomes read-only and the final can only be opened once.",
        variant: "primary",
      };
    case "closeFinal":
      return {
        title: "Close the final?",
        message:
          "Teams can no longer submit recovery answers or accusations. The final cannot be opened again.",
        variant: "danger",
      };
    case "setPublication":
      return publicationConfirmation(action.published);
    default:
      return null;
  }
}

/** Starting is never blocked; rooms that are not ready, closed or without a key only ask for a confirmation. */
function startConfirmation(
  waiting: string[],
  warnings: string[],
): ConfirmationCopy | null {
  if (!waiting.length && !warnings.length) return null;
  const parts = waiting.length ? [`Waiting: ${waiting.join(", ")}.`] : [];
  return {
    title: waiting.length
      ? `${waiting.length} ${waiting.length === 1 ? "room is" : "rooms are"} not ready. Start anyway?`
      : "Start the timer anyway?",
    message: [...parts, ...warnings].join(" "),
    variant: "primary",
  };
}

function roundConfirmation(
  round: number,
  secondsLeft: number | null,
): ConfirmationCopy {
  const copy = plainRoundConfirmation(round);
  if (!secondsLeft || secondsLeft <= 0) return copy;
  return {
    ...copy,
    message: `The current round still has ${formatMinutesSeconds(secondsLeft)} left. ${copy.message}`,
  };
}

function plainRoundConfirmation(round: number): ConfirmationCopy {
  if (round === 0)
    return {
      title: "Reset to not started?",
      message: "No room accepts answers until a round starts.",
      variant: "danger",
    };
  if (round === ROOMS_FINISHED_ROUND)
    return {
      title: "Finish the rooms?",
      message:
        "Rooms stop accepting answers. Teams keep the clues they collected.",
      variant: "primary",
    };
  return {
    title: `Start round ${round} of 4?`,
    message:
      "Each group is told to go to its room for this round. The timer starts only when you press Start timer.",
    variant: "primary",
  };
}

function publicationConfirmation(published: boolean): ConfirmationCopy {
  return published
    ? {
        title: "Publish results?",
        message:
          "Team representatives can see the ranking on the Results page.",
        variant: "primary",
      }
    : {
        title: "Unpublish results?",
        message: "Team representatives no longer see the ranking.",
        variant: "danger",
      };
}

export function successNotice(action: ControlAction): string {
  switch (action.kind) {
    case "openRoom":
      return `${action.room.name} room opened.`;
    case "closeRoom":
      return `${action.room.name} room closed.`;
    case "setRound":
      return "Round updated.";
    case "startTimer":
      return "Timer started.";
    case "pauseTimer":
      return "Timer paused.";
    case "resumeTimer":
      return "Timer resumed.";
    case "adjustTimer":
      return adjustNotice(action.deltaSeconds);
    case "endRound":
      return "Round ended.";
    case "saveRoundMinutes":
      return "Round minutes saved.";
    case "openFinal":
      return "Final opened.";
    case "closeFinal":
      return "Final closed.";
    case "setPublication":
      return action.published ? "Results published." : "Results unpublished.";
  }
}

export function performControlAction(
  eventApi: EventApi,
  action: ControlAction,
): Promise<unknown> {
  switch (action.kind) {
    case "openRoom":
      return eventApi.openRoom(action.room.id);
    case "closeRoom":
      return eventApi.closeRoom(action.room.id);
    case "setRound":
      return eventApi.setRound(action.round);
    case "startTimer":
      return eventApi.startTimer();
    case "pauseTimer":
      return eventApi.pauseTimer();
    case "resumeTimer":
      return eventApi.resumeTimer();
    case "adjustTimer":
      return eventApi.adjustTimer(action.deltaSeconds, action.key);
    case "endRound":
      return eventApi.endRound();
    case "saveRoundMinutes":
      return eventApi.saveSettings(action.minutes);
    case "openFinal":
      return eventApi.openFinal();
    case "closeFinal":
      return eventApi.closeFinal();
    case "setPublication":
      return eventApi.setPublication(action.published);
  }
}

/** "+5 min added." / "1 min removed." */
function adjustNotice(deltaSeconds: number): string {
  const minutes = Math.abs(deltaSeconds) / 60;
  const amount = Number.isInteger(minutes)
    ? `${minutes} min`
    : `${Math.abs(deltaSeconds)} s`;
  return deltaSeconds > 0 ? `+${amount} added.` : `${amount} removed.`;
}
