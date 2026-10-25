import { RoomTeamRow } from "../../../core/event/event-models";

export type TeamFilter = "NOW" | "PENDING" | "ALL";

export const TEAM_FILTERS: readonly { value: TeamFilter; label: string }[] = [
  { value: "NOW", label: "Now" },
  { value: "PENDING", label: "Pending" },
  { value: "ALL", label: "All" },
];

/** Submitted and not judged yet — the same as the "Pending review" counter. */
export function isAwaitingDecision(row: RoomTeamRow): boolean {
  return row.latestSubmission !== null && row.judgement.status === "PENDING";
}

export const FILTER_MATCHES: Record<TeamFilter, (row: RoomTeamRow) => boolean> = {
  NOW: (row) => row.scheduledNow,
  PENDING: isAwaitingDecision,
  ALL: () => true,
};

/** Phones show one expanded row at a time. */
export function isPhoneWidth(): boolean {
  return window.matchMedia("(max-width: 640px)").matches;
}

/**
 * Opens every row whose newest submission awaits a decision and was not seen before (on phones only when no
 * row is open yet). Rows are never closed here, so a row the manager just judged stays open.
 */
export function withNewPendingRows(
  rows: readonly RoomTeamRow[],
  expanded: ReadonlySet<number>,
  seenSubmissions: Set<string>,
  phone: boolean,
): ReadonlySet<number> {
  const next = new Set(expanded);
  for (const row of rows) {
    const id = row.latestSubmission?.id;
    if (!id || seenSubmissions.has(id)) continue;
    seenSubmissions.add(id);
    if (!isAwaitingDecision(row)) continue;
    if (!phone || next.size === 0) next.add(row.teamId);
  }
  return next.size === expanded.size ? expanded : next;
}

/** The first team awaiting a decision after `afterTeamId` in board order, wrapping around. */
export function nextPendingTeam(
  rows: readonly RoomTeamRow[],
  afterTeamId: number | null,
): RoomTeamRow | null {
  const pending = rows.filter(isAwaitingDecision);
  if (!pending.length) return null;
  const start = rows.findIndex((row) => row.teamId === afterTeamId);
  return (
    pending.find((row) => rows.indexOf(row) > start) ?? pending[0]
  );
}
