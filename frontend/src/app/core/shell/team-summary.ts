import {
  AdminBoard,
  Organization,
  RankingRow,
  RoomCode,
  TeamFlagsView,
  TeamView,
} from "../event/event-models";

export interface TeamSummary {
  team: TeamView;
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
  return {
    team,
    room: room ? { id: room.id, code: room.code, name: room.name } : null,
    round,
    ranking: board?.ranking.find((row) => row.teamId === teamId) ?? null,
    flags: board?.teamFlags.find((row) => row.teamId === teamId) ?? null,
  };
}
