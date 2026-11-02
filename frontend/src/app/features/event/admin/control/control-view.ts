import {
  AdminBoard,
  AdminRoomCard,
  EventStateView,
  RoomCode,
} from "../../../../core/event/event-models";

/** Rounds 1..4 run the rooms; 5 means the rooms are finished. */
export const ROOMS_FINISHED_ROUND = 5;

export interface RoundOption {
  value: string;
  label: string;
}

/** Choices of the "Set round" select: 0 (not started) to 5 (rooms finished). */
export const ROUND_OPTIONS: readonly RoundOption[] = [
  { value: "0", label: "0 · Not started" },
  { value: "1", label: "Round 1" },
  { value: "2", label: "Round 2" },
  { value: "3", label: "Round 3" },
  { value: "4", label: "Round 4" },
  { value: "5", label: "5 · Rooms finished" },
];

export function nextRoundBlockReason(state: EventStateView): string | null {
  return state.currentRound >= ROOMS_FINISHED_ROUND
    ? "All four rounds are done."
    : null;
}

export function nextRoundLabel(state: EventStateView): string {
  const next = state.currentRound + 1;
  if (next > ROOMS_FINISHED_ROUND) return "";
  return next === ROOMS_FINISHED_ROUND
    ? "Next: finish the rooms."
    : `Next: round ${next} of 4.`;
}

export function openFinalBlockReason(state: EventStateView): string | null {
  return state.finalStartedAt !== null
    ? "The final was already opened once."
    : null;
}

export {
  type FinalStatus,
  finalStatus,
} from "../../../../core/event/event-status";

export interface RoomCardView {
  room: AdminRoomCard;
  managerText: string;
  hasManager: boolean;
  groupText: string;
  teamsText: string;
  progressText: string;
  reviewText: string;
}

export function toRoomCardView(room: AdminRoomCard): RoomCardView {
  return {
    room,
    managerText: room.managerName ?? "No manager assigned",
    hasManager: room.managerName !== null,
    groupText: room.groupNow ? `Group ${room.groupNow} now` : "No group now",
    teamsText: room.teamsNow.length
      ? room.teamsNow.join(", ")
      : "No teams in this room right now.",
    progressText: `${room.passed} / ${room.totalTeams} passed`,
    reviewText: `${room.pending} pending · ${room.failed} failed`,
  };
}

export interface RotationColumn {
  round: number;
  current: boolean;
}

export interface RotationLine {
  groupCode: string;
  cells: { code: RoomCode; roomName: string; current: boolean }[];
}

export function rotationColumns(currentRound: number): RotationColumn[] {
  return [1, 2, 3, 4].map((round) => ({
    round,
    current: round === currentRound,
  }));
}

/** Rotation rows with room names (from the board's rooms) instead of codes. */
export function rotationLines(board: AdminBoard): RotationLine[] {
  const names = new Map<RoomCode, string>(
    board.rooms.map((room) => [room.code, room.name]),
  );
  const current = board.state.currentRound;
  return board.rotation.map((row) => ({
    groupCode: row.groupCode,
    cells: row.rooms.map((code, index) => ({
      code,
      roomName: names.get(code) ?? code,
      current: index + 1 === current,
    })),
  }));
}
