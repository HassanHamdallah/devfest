import {
  RoundPhase,
  ScheduledRoom,
  TeamBoard,
} from "../../../core/event/event-models";

export type SubmissionAvailability =
  { open: true; room: ScheduledRoom } | { open: false; reason: string };

/**
 * Mirrors the server's checks so the page explains exactly why there is no form. `phase` defaults to the
 * board's; the panel passes the locally followed phase so the form closes when the countdown reaches 0.
 */
export function submissionAvailability(
  board: TeamBoard,
  phase: RoundPhase | null = board.clock.phase,
): SubmissionAvailability {
  if (board.disqualified) {
    return {
      open: false,
      reason: "Your team has been disqualified. Contact the organizer.",
    };
  }
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
  switch (phase) {
    case "ASSIGNED":
      return {
        open: false,
        reason: `Go to the ${room.name} room. The timer starts soon.`,
      };
    case "PAUSED":
      return { open: false, reason: "The round is paused." };
    case "TIME_UP":
      return { open: false, reason: "Time is up for this round." };
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
  if (room?.exception) return `This round your team goes to the ${room.name} room. (Round ${round} of 4)`;
  return room
    ? `Current room: ${room.name} (round ${round} of 4)`
    : `Round ${round} of 4`;
}
