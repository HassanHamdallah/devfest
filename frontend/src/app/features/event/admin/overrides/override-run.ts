import { signal } from "@angular/core";
import { message } from "../../../../core/api";
import {
  OrganizationRoom,
  OverrideOutcome,
  TeamView,
} from "../../../../core/event/event-models";
import { UiSelectOption } from "../../../../shared/ui/ui-select/ui-select";

/** Dialog and result state of one override: open → confirm with a reason → outcome or server error. */
export class OverrideRun {
  readonly open = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly outcome = signal<OverrideOutcome | null>(null);

  ask(): void {
    this.error.set(null);
    this.open.set(true);
  }

  cancel(): void {
    if (!this.busy()) this.open.set(false);
  }

  /** Runs `work`; on success closes the dialog and keeps the outcome, on failure shows the server message. */
  async run(work: () => Promise<OverrideOutcome>): Promise<boolean> {
    if (this.busy()) return false;
    this.busy.set(true);
    this.error.set(null);
    try {
      this.outcome.set(await work());
      this.open.set(false);
      return true;
    } catch (e) {
      this.error.set(message(e));
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}

export const NO_SELECTION = "";

/** Shared layout of the override sections (each a card with a heading, fields and buttons). */
export const SECTION_STYLES = `
  :host {
    display: block;
  }
  .override-section {
    display: grid;
    gap: var(--space-3);
    padding: var(--space-4);
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-card);
  }
  h2 {
    margin: 0;
    font-size: var(--font-size-lg);
  }
  .override-section__details {
    display: grid;
    gap: var(--space-3);
  }
  .override-section__details > summary {
    cursor: pointer;
  }
  .override-section__details > summary h2 {
    display: inline;
  }
  p {
    margin: 0;
  }
  .override-section__fields {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr));
    gap: var(--space-3);
  }
  .override-section__check {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  ul {
    margin: 0;
    padding-left: var(--space-5);
  }
`;

export function teamOptions(teams: readonly TeamView[]): UiSelectOption[] {
  return teams.map((team) => ({
    value: String(team.id),
    label: `${team.name} (group ${team.groupCode})`,
  }));
}

export function roomOptions(
  rooms: readonly OrganizationRoom[],
): UiSelectOption[] {
  return rooms.map((room) => ({ value: String(room.id), label: room.name }));
}

export function findTeam(
  teams: readonly TeamView[],
  id: string,
): TeamView | null {
  return teams.find((team) => String(team.id) === id) ?? null;
}

export function findRoom(
  rooms: readonly OrganizationRoom[],
  id: string,
): OrganizationRoom | null {
  return rooms.find((room) => String(room.id) === id) ?? null;
}
