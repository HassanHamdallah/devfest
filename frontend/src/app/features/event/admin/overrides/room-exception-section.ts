import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import {
  OrganizationRoom,
  TeamFlagsView,
  TeamView,
} from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { OverrideDialog } from "./override-dialog";
import { OverrideOutcomeView } from "./override-outcome";
import {
  NO_SELECTION,
  OverrideRun,
  SECTION_STYLES,
  findRoom,
  findTeam,
  roomOptions,
  teamOptions,
} from "./override-run";

/** O3: send one team to another room for the current round only; active exceptions can be cleared. */
@Component({
  selector: "app-room-exception-section",
  imports: [
    FormsModule,
    OverrideDialog,
    OverrideOutcomeView,
    UiAlert,
    UiButton,
    UiSelect,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [SECTION_STYLES],
  template: `<section
    class="override-section"
    id="room-exception"
    aria-labelledby="room-exception-heading"
  >
    <h2 id="room-exception-heading">Room exception (current round)</h2>
    <p class="event-muted">
      Send one team to a different room for this round only, e.g. when a room
      broke or the team was late. From the next round it follows the rotation.
    </p>
    @if (!roundRunning()) {
      <ui-alert variant="info">Room exceptions need a running round (1–4).</ui-alert>
    }
    <div class="override-section__fields">
      <ui-select
        label="Team"
        placeholder="Choose a team"
        [options]="teamChoices()"
        [ngModel]="team()"
        (ngModelChange)="team.set($event)"
      />
      <ui-select
        label="Room"
        placeholder="Choose a room"
        [options]="roomChoices()"
        [ngModel]="room()"
        (ngModelChange)="room.set($event)"
      />
    </div>
    <div class="event-inline">
      <ui-button
        [disabled]="!team() || !room() || !roundRunning()"
        [loading]="checking()"
        (click)="review()"
        >Review exception…</ui-button
      >
    </div>
    @if (reviewError(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    @if (active().length) {
      <ul aria-label="Active room exceptions">
        @for (flag of active(); track flag.teamId) {
          <li>
            {{ flag.teamName }} → {{ roomName(flag) }}
            <ui-button variant="ghost" size="sm" (click)="askClear(flag)"
              >Clear</ui-button
            >
          </li>
        }
      </ul>
    }
    <app-override-outcome [outcome]="run.outcome() ?? clearRun.outcome()" />
    <app-override-dialog
      [open]="run.open()"
      title="Apply this room exception?"
      [consequence]="consequence()"
      [busy]="run.busy()"
      [error]="run.error()"
      (confirmed)="apply($event)"
      (cancelled)="run.cancel()"
    >
      @if (warnings().length) {
        <ui-alert variant="warning">
          <ul>
            @for (warning of warnings(); track warning) {
              <li>{{ warning }}</li>
            }
          </ul>
        </ui-alert>
      }
    </app-override-dialog>
    <app-override-dialog
      [open]="clearRun.open()"
      title="Clear this room exception?"
      [consequence]="clearConsequence()"
      [busy]="clearRun.busy()"
      [error]="clearRun.error()"
      (confirmed)="clear($event)"
      (cancelled)="clearRun.cancel()"
    />
  </section>`,
})
export class RoomExceptionSection {
  private readonly eventApi = inject(EventApi);

  readonly teams = input.required<readonly TeamView[]>();
  readonly rooms = input.required<readonly OrganizationRoom[]>();
  readonly flags = input.required<readonly TeamFlagsView[]>();
  readonly roundRunning = input(false);
  readonly preselect = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly run = new OverrideRun();
  protected readonly clearRun = new OverrideRun();
  protected readonly teamChoices = computed(() =>
    teamOptions(this.teams().filter((team) => team.active)),
  );
  protected readonly roomChoices = computed(() => roomOptions(this.rooms()));
  protected readonly team = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly room = signal(NO_SELECTION);
  protected readonly warnings = signal<string[]>([]);
  protected readonly checking = signal(false);
  protected readonly reviewError = signal<string | null>(null);
  protected readonly clearing = signal<TeamFlagsView | null>(null);
  protected readonly active = computed(() =>
    this.flags().filter((flag) => flag.exceptionRoom !== null),
  );
  protected readonly consequence = computed(() => {
    const team = findTeam(this.teams(), this.team());
    const room = findRoom(this.rooms(), this.room());
    if (!team || !room) return "";
    return (
      `This round ${team.name} goes to the ${room.name} room instead of its rotation room and can submit ` +
      `only there. From the next round it follows the rotation again.`
    );
  });
  protected readonly clearConsequence = computed(() => {
    const flag = this.clearing();
    return flag ? `${flag.teamName} follows the rotation again in this round.` : "";
  });

  protected roomName(flag: TeamFlagsView): string {
    return this.rooms().find((room) => room.code === flag.exceptionRoom)?.name ?? "";
  }

  /** Loads the server's warnings (closed room, no key, clue already collected), then asks to confirm. */
  protected async review(): Promise<void> {
    this.checking.set(true);
    this.reviewError.set(null);
    try {
      this.warnings.set(
        await this.eventApi.roomExceptionPreview(Number(this.team()), Number(this.room())),
      );
      this.run.ask();
    } catch (e) {
      this.reviewError.set(message(e));
    } finally {
      this.checking.set(false);
    }
  }

  protected async apply(reason: string): Promise<void> {
    if (await this.run.run(() =>
      this.eventApi.setRoomException(Number(this.team()), Number(this.room()), reason),
    )) {
      this.clearRun.outcome.set(null);
      this.changed.emit();
    }
  }

  protected askClear(flag: TeamFlagsView): void {
    this.clearing.set(flag);
    this.clearRun.ask();
  }

  protected async clear(reason: string): Promise<void> {
    const flag = this.clearing();
    if (!flag) return;
    if (await this.clearRun.run(() => this.eventApi.clearRoomException(flag.teamId, reason))) {
      this.run.outcome.set(null);
      this.changed.emit();
    }
  }
}
