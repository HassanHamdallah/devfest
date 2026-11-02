import {
  AccountView,
  EventAccountRole,
  GROUP_CODES,
  Organization,
  TeamView,
} from "../../../../core/event/event-models";
import { UiSelectOption } from "../../../../shared/ui/ui-select/ui-select";
import { UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";

export const NO_SELECTION = "";

export const ACCOUNT_ROLE_LABELS: Record<EventAccountRole, string> = {
  MANAGER: "Room manager",
  MEMBER: "Team representative",
};

export const ROLE_OPTIONS: readonly UiSelectOption[] = [
  { value: "MEMBER", label: ACCOUNT_ROLE_LABELS.MEMBER },
  { value: "MANAGER", label: ACCOUNT_ROLE_LABELS.MANAGER },
];

export const GROUP_OPTIONS: readonly UiSelectOption[] = GROUP_CODES.map(
  (code) => ({ value: code, label: `Group ${code}` }),
);

export interface GroupCounter {
  groupCode: string;
  text: string;
  overLimit: boolean;
  hint: string;
  variant: UiStatusChipVariant;
}

/** "active / max" per group; max is ceil(planned teams / 4) from the server. */
export function groupCounters(org: Organization): GroupCounter[] {
  const max = org.maxPerGroup;
  return org.groups.map(({ groupCode, activeTeams }) => {
    const open = max - activeTeams;
    const overLimit = open < 0;
    return {
      groupCode,
      text: `${activeTeams} / ${max}`,
      overLimit,
      hint: overLimit ? "Over the limit" : open === 0 ? "Full" : `${open} open`,
      variant: overLimit ? "danger" : open === 0 ? "success" : "neutral",
    };
  });
}

/** "14:05" from an ISO instant; null when the team was never seen. */
export function lastSeenLabel(iso: string | null): string | null {
  if (!iso) return null;
  return new Date(iso).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Active managers for a room's select; a manager already holding another room is labelled so. */
export function managerOptions(
  org: Organization,
  roomId: number,
): UiSelectOption[] {
  const roomNames = new Map(org.rooms.map((room) => [room.id, room.name]));
  const managers = org.accounts.filter(
    (account) => account.role === "MANAGER" && account.active,
  );
  return [
    { value: NO_SELECTION, label: "No manager" },
    ...managers.map((account) => ({
      value: String(account.id),
      label:
        account.roomId !== null && account.roomId !== roomId
          ? `${account.name} (manages ${roomNames.get(account.roomId) ?? "another room"})`
          : account.name,
    })),
  ];
}

/** Active member accounts without a team, plus the edited team's own account. */
export function memberAccountOptions(
  org: Organization,
  team: TeamView | null,
): UiSelectOption[] {
  return org.accounts
    .filter(
      (account) =>
        account.role === "MEMBER" &&
        (account.id === team?.accountId ||
          (account.active && account.teamId === null)),
    )
    .map((account) => ({
      value: String(account.id),
      label: `${account.name} (${account.email})`,
    }));
}

/** "Team 01", "AI room" or "—": what an account is attached to. */
export function accountLinkLabel(
  account: AccountView,
  org: Organization,
): string {
  if (account.teamId !== null)
    return org.teams.find((team) => team.id === account.teamId)?.name ?? "—";
  if (account.roomId !== null) {
    const room = org.rooms.find((candidate) => candidate.id === account.roomId);
    return room ? `${room.name} room` : "—";
  }
  return "—";
}
