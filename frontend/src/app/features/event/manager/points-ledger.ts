import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { PointsHistoryEntry } from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { newIdempotencyKey } from "../../../core/event/pending-request";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";

const MAX_STEP = 100;
/** The room's score is the total x 0.15; shown with 2 decimals next to the total. */
const SCORE_MAX = 15;

/** "+10" / "-5": a change with its sign. */
export function signed(delta: number): string {
  return delta > 0 ? `+${delta}` : `${delta}`;
}

/**
 * One team's points in a points-and-phone room (rules §21.1): the total out of 100 and its score out of 15, a number box
 * with "Add" and "Subtract" (Enter adds), "Undo last" naming the change it reverses, and a collapsible history. Every
 * click carries its own request key, so a double click never counts twice; the buttons wait for the answer. The
 * server keeps the total between 0 and 100 and says so when a change would leave it.
 */
@Component({
  selector: "event-points-ledger",
  imports: [FormsModule, UiAlert, UiButton, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
      min-inline-size: 0;
    }
    .points__line {
      margin: 0;
    }
    .points__form {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-2);
    }
    .points__box {
      inline-size: 7rem;
    }
    .points__history {
      font-size: var(--font-size-sm);
    }
    .points__history summary {
      cursor: pointer;
    }
    .points__table {
      inline-size: 100%;
      margin-block-start: var(--space-2);
      border-collapse: collapse;
    }
    .points__table th,
    .points__table td {
      padding: var(--space-1) var(--space-2);
      text-align: start;
      border-block-end: 1px solid var(--color-border);
    }
    .points__table td.num {
      text-align: end;
    }
    .points__struck {
      text-decoration: line-through;
      color: var(--color-text-muted);
    }
  `,
  template: `<p class="points__line" data-points-line>
      Points: <strong class="event-num" data-points-total>{{ total() }} / 100</strong>
      <span class="event-muted event-num" data-points-score>(= {{ scoreText() }} / ${SCORE_MAX})</span>
    </p>
    <form class="points__form" (ngSubmit)="change(1)" [attr.aria-label]="'Points for ' + teamName()">
      <div class="points__box">
        <ui-input
          label="Points"
          name="points"
          type="number"
          [compact]="true"
          [ngModel]="draft()"
          (ngModelChange)="draft.set($event)"
        />
      </div>
      <ui-button type="submit" size="sm" [disabled]="busy()" data-points-add>Add</ui-button>
      <ui-button size="sm" variant="secondary" [disabled]="busy()" (click)="change(-1)" data-points-subtract>Subtract</ui-button>
      <ui-button size="sm" variant="ghost" [disabled]="busy() || undoDelta() === null" (click)="undo()" data-points-undo>{{ undoLabel() }}</ui-button>
    </form>
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true" (dismissed)="error.set(null)">{{ text }}</ui-alert>
    }
    <details class="points__history" data-points-history (toggle)="toggled($event)">
      <summary>History</summary>
      @if (history(); as entries) {
        @if (entries.length) {
          <table class="points__table">
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col" class="num">Change</th>
                <th scope="col" class="num">Total after</th>
                <th scope="col">By</th>
              </tr>
            </thead>
            <tbody>
              @for (entry of entries; track entry.id) {
                <tr [attr.data-entry]="entry.id">
                  <td class="event-num">{{ time(entry) }}</td>
                  <td class="num event-num" [class.points__struck]="entry.undone">{{ sign(entry.delta) }}{{ entry.undo ? " (undo)" : "" }}</td>
                  <td class="num event-num">{{ entry.totalAfter }}</td>
                  <td>{{ entry.enteredBy }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <span class="event-muted">No points yet.</span>
        }
      } @else {
        <span class="event-muted">{{ historyError() ?? "Loading…" }}</span>
      }
    </details>`,
})
export class EventPointsLedger {
  private readonly eventApi = inject(EventApi);

  readonly roomId = input.required<number>();
  readonly teamId = input.required<number>();
  readonly teamName = input.required<string>();
  /** 0..100 (null reads as 0). */
  readonly pointsTotal = input.required<number | null>();
  readonly pointsScore = input.required<number | null>();
  /** The change "Undo last" would reverse; null when there is nothing to undo. */
  readonly undoDeltaValue = input.required<number | null>({ alias: "undoDelta" });
  /** Any write finished; the page reloads its board. */
  readonly changed = output<void>();

  protected readonly draft = signal<string | number | null>("");
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly history = signal<PointsHistoryEntry[] | null>(null);
  protected readonly historyError = signal<string | null>(null);
  private historyOpen = false;

  protected readonly total = computed(() => this.pointsTotal() ?? 0);
  protected readonly undoDelta = computed(() => this.undoDeltaValue());
  protected readonly scoreText = computed(() => (this.pointsScore() ?? 0).toFixed(2));
  protected readonly undoLabel = computed(() => {
    const delta = this.undoDelta();
    return delta === null ? "Undo last" : `Undo ${signed(delta)}`;
  });
  protected readonly sign = signed;

  protected time(entry: PointsHistoryEntry): string {
    return formatTime(entry.enteredAt);
  }

  /** Add (sign 1) or Subtract (sign -1) the number in the box. */
  protected async change(sign: 1 | -1): Promise<void> {
    if (this.busy()) return;
    const text = String(this.draft() ?? "").trim();
    const value = Number(text);
    if (text === "" || !Number.isInteger(value) || value < 1 || value > MAX_STEP) {
      this.error.set("Enter a whole number of points from 1 to 100.");
      return;
    }
    await this.write(() => this.eventApi.addPoints(this.roomId(), this.teamId(), sign * value, newIdempotencyKey()), true);
  }

  protected async undo(): Promise<void> {
    if (this.busy() || this.undoDelta() === null) return;
    await this.write(() => this.eventApi.undoPoints(this.roomId(), this.teamId(), newIdempotencyKey()), false);
  }

  protected toggled(event: Event): void {
    this.historyOpen = (event.target as HTMLDetailsElement).open;
    if (this.historyOpen) void this.loadHistory();
  }

  private async write(send: () => Promise<unknown>, clear: boolean): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await send();
      if (clear) this.draft.set("");
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
      this.changed.emit();
      if (this.historyOpen) void this.loadHistory();
    }
  }

  private async loadHistory(): Promise<void> {
    try {
      this.history.set(await this.eventApi.pointsHistory(this.roomId(), this.teamId()));
      this.historyError.set(null);
    } catch (e) {
      this.historyError.set(message(e));
    }
  }
}
