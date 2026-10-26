import {
  AdminBoard,
  Organization,
  ROOM_CODES,
  RankingRow,
  RoomCode,
  TeamFlagsView,
  TeamView,
} from "../event/event-models";

export interface ClueSlot {
  code: RoomCode;
  name: string;
  held: boolean;
}

export interface TeamSummary {
  team: TeamView;
  /** The four rooms in order, each marked when the team holds its clue; empty until the board is loaded. */
  clues: ClueSlot[];
  /** This round's room (an exception wins over the rotation); null outside rounds 1..4. */
  room: { id: number; code: RoomCode; name: string } | null;
  round: number;
  ranking: RankingRow | null;
  flags: TeamFlagsView | null;
}

/** Everything the team drawer shows about one team, from data the pages already loaded. */
export function teamSummary(
  teamId: number,
  organization: Organization,
  board: AdminBoard | null,
): TeamSummary | null {
  const team = organization.teams.find((candidate) => candidate.id === teamId);
  if (!team) return null;
  const round = board?.state.currentRound ?? 0;
  const rotationCode =
    round >= 1 && round <= 4
      ? board?.rotation.find((row) => row.groupCode === team.groupCode)
          ?.rooms[round - 1]
      : undefined;
  const code =
    round >= 1 && round <= 4 ? (team.exceptionRoom ?? rotationCode) : undefined;
  const room = organization.rooms.find((candidate) => candidate.code === code);
  const ranking = board?.ranking.find((row) => row.teamId === teamId) ?? null;
  const held = new Set(ranking?.clueRooms ?? []);
  const clues = ranking
    ? [...organization.rooms]
        .sort((a, b) => ROOM_CODES.indexOf(a.code) - ROOM_CODES.indexOf(b.code))
        .map((slot) => ({ code: slot.code, name: slot.name, held: held.has(slot.code) }))
    : [];
  return {
    team,
    clues,
    room: room ? { id: room.id, code: room.code, name: room.name } : null,
    round,
    ranking,
    flags: board?.teamFlags.find((row) => row.teamId === teamId) ?? null,
  };
}
