import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { JudgeStatus, JudgementView } from "../../../core/event/event-models";
import { JUDGE_STATUS_LABELS, formatTime } from "../../../core/event/format";
import { ToastService } from "../../../shared/ui/toast/toast.service";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import {
  UiStatusChip,
  UiStatusChipVariant,
} from "../../../shared/ui/ui-status-chip/ui-status-chip";

const MAX_HINTS = 50;

const STATUS_VARIANTS: Record<JudgeStatus, UiStatusChipVariant> = {
  PENDING: "warning",
  PASS: "success",
  FAIL: "danger",
};

/** Which half to render: the row summary shows the decision, the expanded details the hint counter. */
export type JudgementPart = "decision" | "hints";

/** Pass / Fail and the hint counter for one team. Every save carries the version the manager saw. */
@Component({
  selector: "event-judgement-controls",
  imports: [UiAlert, UiButton, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    .decision {
      flex-wrap: nowrap;
    }
    .hints {
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `@if (part() === "decision") {
      <div class="event-inline decision">
        <ui-status-chip [variant]="statusVariant()">{{
          statusLabel()
        }}</ui-status-chip>
        <ui-button
          size="sm"
          variant="secondary"
          [pressed]="judgement().status === 'PASS'"
          [disabled]="saving()"
          (click)="save('PASS', judgement().hints)"
          >Pass</ui-button
        >
        <ui-button
          size="sm"
          variant="secondary"
          [pressed]="judgement().status === 'FAIL'"
          [disabled]="saving()"
          (click)="save('FAIL', judgement().hints)"
          >Fail</ui-button
        >
      </div>
    } @else {
      <div class="event-inline">
        <span class="hints">Hints: {{ judgement().hints }}</span>
        <ui-button
          size="sm"
          variant="ghost"
          [disabled]="saving() || judgement().hints >= maxHints"
          (click)="save(judgement().status, judgement().hints + 1)"
          >Add hint</ui-button
        >
        <ui-button
          size="sm"
          variant="ghost"
          [disabled]="saving() || judgement().hints <= 0"
          (click)="save(judgement().status, judgement().hints - 1)"
          >Remove hint</ui-button
        >
      </div>
      @if (hintPenaltyMismatch()) {
        <ui-alert variant="warning"
          >The hint count does not match the active Hint penalties in this room.
          Add or void a Hint penalty so the time penalty matches.</ui-alert
        >
      }
      <span class="event-muted" aria-live="polite">{{ statusLine() }}</span>
    }
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
    }`,
})
export class EventJudgementControls {
  private readonly eventApi = inject(EventApi);
  private readonly toasts = inject(ToastService);

  readonly roomId = input.required<number>();
  readonly judgement = input.required<JudgementView>();
  readonly hintPenaltyMismatch = input.required<boolean>();
  readonly part = input<JudgementPart>("decision");
  /** A save finished (or conflicted): the page reloads its board. */
  readonly changed = output<void>();

  protected readonly maxHints = MAX_HINTS;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly statusVariant = computed(
    () => STATUS_VARIANTS[this.judgement().status],
  );
  protected readonly statusLabel = computed(
    () => JUDGE_STATUS_LABELS[this.judgement().status],
  );
  protected readonly statusLine = computed(() => {
    if (this.saving()) return "Saving…";
    const { reviewerName, updatedAt } = this.judgement();
    return reviewerName
      ? `Last change by ${reviewerName} at ${formatTime(updatedAt)}`
      : "Not reviewed yet.";
  });

  protected async save(status: JudgeStatus, hints: number): Promise<void> {
    if (this.saving()) return;
    const current = this.judgement();
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.eventApi.saveJudgement(this.roomId(), current.teamId, {
        status,
        hints,
        note: current.note,
        version: current.version,
      });
      this.toasts.show(
        hints === current.hints
          ? `Decision saved: ${JUDGE_STATUS_LABELS[status]}`
          : `Hints: ${hints}`,
      );
    } catch (e) {
      this.error.set(describeSaveError(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}

/** A 409 means someone else saved first; the board reload that follows shows their change. */
function describeSaveError(error: unknown): string {
  const conflict = error instanceof HttpErrorResponse && error.status === 409;
  return conflict
    ? `${message(error)} The latest review is now shown.`
    : message(error);
}
