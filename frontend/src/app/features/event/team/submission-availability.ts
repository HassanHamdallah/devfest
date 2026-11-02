import { ScheduledRoom, TeamBoard } from "../../../core/event/event-models";

export type SubmissionAvailability =
  { open: true; room: ScheduledRoom } | { open: false; reason: string };

/** Mirrors the server's order of checks so the page explains exactly why there is no form. */
export function submissionAvailability(
  board: TeamBoard,
): SubmissionAvailability {
  const round = board.clock.currentRound;
  if (round < 1) {
    return {
      open: false,
      reason:
        "Rooms have not started yet. The answer form appears here when your first round begins.",
    };
  }
  if (round > 4) {
    return {
      open: false,
      reason: "All room rounds are finished. There is nothing left to submit.",
    };
  }
  const room = board.scheduledRoom;
  if (!room) {
    return {
      open: false,
      reason: "Your team has no room in the current round.",
    };
  }
  if (!room.open) {
    return {
      open: false,
      reason: `The ${room.name} room is closed right now. The answer form appears when the organizers open it.`,
    };
  }
  return { open: true, room };
}

/** "Current room: AI (round 2 of 4)" or why there is none. */
export function currentRoomLine(board: TeamBoard): string {
  const round = board.clock.currentRound;
  if (round < 1) return "Rooms have not started yet.";
  if (round > 4) return "All room rounds are finished.";
  const room = board.scheduledRoom;
  return room
    ? `Current room: ${room.name} (round ${round} of 4)`
    : `Round ${round} of 4`;
}
