import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { DeductionView } from "../../../core/event/event-models";
import { ToastService } from "../../../shared/ui/toast/toast.service";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventDeductionItem } from "./deduction-item";

const NOTE_MAX_CHARACTERS = 500;

/**
 * The two actions and the list of a team's point deductions in one room: "Hint given (−2)" (one click, optional
 * note), "Cheating (−7.5)" (a confirmation and a required reason) and "Hints: n of 2". Used on the manager's team
 * card, and by the admin for any team and room. Nothing here ever adds time.
 */
@Component({
  selector: "event-team-deductions",
  imports: [EventDeductionItem, FormsModule, UiAlert, UiButton, UiInput, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    .bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
    .note {
      flex: 1 1 14rem;
      min-inline-size: 0;
    }
    form {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-3);
      border: 1px solid var(--color-danger, var(--color-border));
      border-radius: var(--radius-sm);
    }
  `,
  template: `<div class="bar" data-deductions>
      <ui-status-chip [variant]="hintsUsed() >= hintsAllowed() ? 'warning' : 'neutral'" data-hints-count
        >Hints: {{ hintsUsed() }} of {{ hintsAllowed() }}</ui-status-chip
      >
      @if (cheating()) {
        <ui-status-chip variant="danger" data-cheating-active>Cheating recorded</ui-status-chip>
      }
    </div>
    <div class="bar">
      <div class="note">
        <ui-input label="Hint note (optional)" name="note" [compact]="true" [(ngModel)]="note" />
      </div>
      <ui-button
        variant="secondary"
        [loading]="sending() === 'HINT'"
        [disabled]="sending() !== null || hintsUsed() >= hintsAllowed()"
        (click)="giveHint()"
        >Hint given (−2)</ui-button
      >
      <ui-button
        variant="danger"
        [disabled]="sending() !== null || cheating() || cheatingFormOpen()"
        (click)="openCheating()"
        >Cheating (−7.5)</ui-button
      >
    </div>
    @if (hintsUsed() >= hintsAllowed()) {
      <span class="event-muted">This team has used its {{ hintsAllowed() }} hints.</span>
    }
    @if (cheatingFormOpen()) {
      <form (ngSubmit)="recordCheating()" data-cheating-form>
        <strong>Record cheating for {{ teamName() }}? It takes 7.5 points from this room's score.</strong>
        <ui-input label="Cheating reason" name="reason" [compact]="true" [required]="true" [(ngModel)]="reason" />
        <div class="event-inline">
          <ui-button type="submit" variant="danger" size="sm" [loading]="sending() === 'CHEATING'"
            >Confirm cheating (−7.5)</ui-button
          >
          <ui-button size="sm" variant="ghost" [disabled]="sending() !== null" (click)="closeCheating()"
            >Do not record</ui-button
          >
        </div>
      </form>
    }
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
    }
    @for (deduction of deductions(); track deduction.id) {
      <event-deduction-item [deduction]="deduction" (changed)="changed.emit()" />
    } @empty {
      <span class="event-muted">No deductions in this room.</span>
    }`,
})
export class EventTeamDeductions {
  private readonly eventApi = inject(EventApi);
  private readonly toasts = inject(ToastService);

  readonly roomId = input.required<number>();
  readonly teamId = input.required<number>();
  readonly teamName = input.required<string>();
  readonly deductions = input.required<readonly DeductionView[]>();
  /** Active hints across all four rooms. */
  readonly hintsUsed = input.required<number>();
  readonly hintsAllowed = input(2);
  /** An active cheating violation exists for this team in this room. */
  readonly cheating = input.required<boolean>();
  readonly changed = output<void>();

  protected readonly note = signal("");
  protected readonly reason = signal("");
  protected readonly cheatingFormOpen = signal(false);
  protected readonly sending = signal<"HINT" | "CHEATING" | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly activeCount = computed(() => this.deductions().filter((d) => !d.cancelled).length);

  protected async giveHint(): Promise<void> {
    const note = this.note().trim();
    if (note.length > NOTE_MAX_CHARACTERS) {
      this.error.set(`Hint note is too long (max ${NOTE_MAX_CHARACTERS}).`);
      return;
    }
    await this.send("HINT", async () => {
      await this.eventApi.giveHint(this.roomId(), { teamId: this.teamId(), note });
      this.note.set("");
      this.toasts.show("Hint recorded: −2 points");
    });
  }

  protected openCheating(): void {
    this.error.set(null);
    this.cheatingFormOpen.set(true);
  }

  protected closeCheating(): void {
    this.cheatingFormOpen.set(false);
    this.reason.set("");
  }

  protected async recordCheating(): Promise<void> {
    const reason = this.reason().trim();
    if (reason.length < 3) {
      this.error.set("Give a reason (3 to 500 characters).");
      return;
    }
    if (reason.length > NOTE_MAX_CHARACTERS) {
      this.error.set(`Reason is too long (max ${NOTE_MAX_CHARACTERS}).`);
      return;
    }
    await this.send("CHEATING", async () => {
      await this.eventApi.recordCheating(this.roomId(), { teamId: this.teamId(), reason, confirmed: true });
      this.closeCheating();
      this.toasts.show("Cheating recorded: −7.5 points");
    });
  }

  private async send(kind: "HINT" | "CHEATING", action: () => Promise<void>): Promise<void> {
    if (this.sending() !== null) return;
    this.sending.set(kind);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.sending.set(null);
      // Also after a failure: "already used its 2 hints" means the board is stale.
      this.changed.emit();
    }
  }
}
