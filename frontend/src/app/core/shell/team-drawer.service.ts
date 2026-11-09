import { Injectable, signal } from "@angular/core";

/**
 * A team's override actions: each opens the matching section of the Overrides page with the team
 * preselected (`?team=<id>#<fragment>`), where the section's own dialog asks for the reason. Used by the
 * Teams page "…" menu and the team drawer, so both offer exactly the same actions.
 */
export const TEAM_OVERRIDE_LINKS: readonly { label: string; fragment: string }[] = [
  { label: "Swap with…", fragment: "swap" },
  { label: "Move to group…", fragment: "move" },
  { label: "Room exception…", fragment: "room-exception" },
  { label: "Grant/Revoke clue…", fragment: "clue" },
  { label: "Disqualify/Reinstate…", fragment: "disqualify" },
  { label: "Reset team…", fragment: "reset-team" },
];

/** Which team the organizer's team drawer shows (null = closed). Any admin page can open it. */
@Injectable({ providedIn: "root" })
export class TeamDrawerService {
  private readonly current = signal<number | null>(null);

  readonly teamId = this.current.asReadonly();

  open(teamId: number): void {
    this.current.set(teamId);
  }

  close(): void {
    this.current.set(null);
  }
}
