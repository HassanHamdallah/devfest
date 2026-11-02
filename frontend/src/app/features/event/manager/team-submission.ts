import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  signal,
} from "@angular/core";
import { LatestSubmission } from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

/** Long technical output is cut here until the manager expands it, so rows stay scannable. */
const PREVIEW_CHARACTERS = 280;

/** A team's latest answer in this room: technical output, clue answer with its automatic check, time and count. */
@Component({
  selector: "event-team-submission",
  imports: [UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    pre {
      margin: 0;
      padding: var(--space-2) var(--space-3);
      font-size: var(--font-size-sm);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-sm);
    }
    .toggle {
      justify-self: start;
      padding: 0;
      font: inherit;
      font-size: var(--font-size-sm);
      color: var(--color-primary-text);
      background: none;
      border: 0;
      cursor: pointer;
      text-decoration: underline;
    }
    .answer {
      overflow-wrap: anywhere;
    }
  `,
  template: `@if (submission(); as latest) {
      <span class="event-muted">Technical output</span>
      <pre>{{ visibleOutput() }}</pre>
      @if (isLong()) {
        <button
          type="button"
          class="toggle"
          [attr.aria-expanded]="expanded()"
          (click)="expanded.set(!expanded())"
        >
          {{ expanded() ? "Show less" : "Show full output" }}
        </button>
      }
      <div class="event-inline">
        <span class="answer">Clue answer: {{ latest.clueAnswer }}</span>
        <ui-status-chip [variant]="latest.clueCorrect ? 'success' : 'danger'">{{
          latest.clueCorrect ? "Correct" : "Incorrect"
        }}</ui-status-chip>
      </div>
      <span class="event-muted">{{ summary() }}</span>
    } @else {
      <span class="event-muted">No submission yet.</span>
    }`,
})
export class EventTeamSubmission {
  readonly submission = input.required<LatestSubmission | null>();
  readonly submissionCount = input.required<number>();

  protected readonly expanded = signal(false);

  protected readonly isLong = computed(
    () => (this.submission()?.technicalOutput.length ?? 0) > PREVIEW_CHARACTERS,
  );

  protected readonly visibleOutput = computed(() => {
    const output = this.submission()?.technicalOutput ?? "";
    return this.isLong() && !this.expanded()
      ? `${output.slice(0, PREVIEW_CHARACTERS)}…`
      : output;
  });

  protected readonly summary = computed(() => {
    const latest = this.submission();
    if (!latest) return "";
    const count = this.submissionCount();
    const submissions = count === 1 ? "1 submission" : `${count} submissions`;
    return `Submitted ${formatTime(latest.receivedAt)} · round ${latest.round} · ${submissions}`;
  });
}
