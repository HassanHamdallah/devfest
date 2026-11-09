import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../api";
import { EventApi } from "../event/event-api";
import { DeductionView, RoomCode } from "../event/event-models";
import { EventLiveStore } from "../event/event-live-store";
import { EventTeamDeductions } from "../../features/event/manager/team-deductions";
import { UiAlert } from "../../shared/ui/ui-alert/ui-alert";
import { UiSelect, UiSelectOption } from "../../shared/ui/ui-select/ui-select";

/**
 * "Hints and cheating" in the organizer's team panel: the same two actions and list as on the room board, for any
 * team and any room (the admin is not limited to the team's current room). Reads the full log once and filters it.
 */
@Component({
  selector: "app-team-drawer-deductions",
  imports: [EventTeamDeductions, FormsModule, UiAlert, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    section {
      display: grid;
      gap: var(--space-3);
    }
    h3 {
      margin: 0;
    }
  `,
  template: `<section aria-labelledby="team-drawer-deductions" data-drawer-deductions>
    <h3 id="team-drawer-deductions">Hints and cheating</h3>
    <ui-select
      label="Room"
      [compact]="true"
      [options]="roomOptions()"
      [ngModel]="roomId()"
      (ngModelChange)="roomId.set($event)"
    />
    @if (error(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    @if (selectedRoomId(); as id) {
      <event-team-deductions
        [roomId]="id"
        [teamId]="teamId()"
        [teamName]="teamName()"
        [deductions]="inRoom()"
        [hintsUsed]="hintsUsed()"
        [cheating]="cheating()"
        (changed)="load()"
      />
    }
  </section>`,
})
export class TeamDrawerDeductions {
  private readonly eventApi = inject(EventApi);
  private readonly store = inject(EventLiveStore);

  readonly teamId = input.required<number>();
  readonly teamName = input.required<string>();

  protected readonly roomId = signal("");
  protected readonly error = signal<string | null>(null);
  private readonly log = signal<readonly DeductionView[]>([]);

  protected readonly rooms = computed(() => this.store.organization()?.rooms ?? []);
  protected readonly roomOptions = computed<UiSelectOption[]>(() =>
    this.rooms().map((room) => ({ value: String(room.id), label: `${room.name} room` })),
  );
  protected readonly selectedRoomId = computed(() => {
    const chosen = Number(this.roomId());
    return this.rooms().some((room) => room.id === chosen) ? chosen : (this.rooms()[0]?.id ?? null);
  });
  private readonly mine = computed(() => this.log().filter((d) => d.teamId === this.teamId()));
  protected readonly inRoom = computed(() => this.mine().filter((d) => d.roomId === this.selectedRoomId()));
  protected readonly hintsUsed = computed(() => this.mine().filter((d) => d.kind === "HINT" && !d.cancelled).length);
  protected readonly cheating = computed(() => this.inRoom().some((d) => d.kind === "CHEATING" && !d.cancelled));

  constructor() {
    effect(() => {
      this.teamId();
      untracked(() => void this.load());
    });
  }

  protected async load(): Promise<void> {
    try {
      this.log.set(await this.eventApi.adminDeductions());
      this.error.set(null);
    } catch (e) {
      this.error.set(message(e));
    }
  }
}

export type { RoomCode };
