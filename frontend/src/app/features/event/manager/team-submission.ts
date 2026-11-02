import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import {
  ClueOverride,
  LatestSubmission,
} from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";

/** Long technical output is cut here until the manager expands it, so rows stay scannable. */
const PREVIEW_CHARACTERS = 280;

const VERDICT_OPTIONS: readonly { value: ClueOverride; label: string }[] = [
  { value: "AUTOMATIC", label: "Automatic" },
  { value: "ACCEPT", label: "Accept" },
  { value: "REJECT", label: "Reject" },
];

/**
 * A team's latest answer in this room: technical output, then the clue answer with its automatic result
 * (against the current key) and the manager's verdict control. The team sees only the effective result.
 */
@Component({
  selector: "event-team-submission",
  imports: [UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    pre {
      margin: 0;
      padding: var(--space-3);
      font-size: var(--font-size-sm);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-md);
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
    .clue {
      display: grid;
      gap: var(--space-2);
      padding-top: var(--space-2);
      border-top: 1px solid var(--color-border);
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
      <div class="clue" data-clue-verdict>
        <span class="answer">Clue answer: <strong>{{ latest.clueAnswer }}</strong></span>
        <div class="event-inline" role="group" aria-label="Clue verdict">
          @for (option of options; track option.value) {
            <ui-button
              size="sm"
              variant="secondary"
              [pressed]="latest.override === option.value"
              [disabled]="!editable() || saving()"
              (click)="choose(latest, option.value)"
              >{{ option.label }}</ui-button
            >
          }
        </div>
        @if (overrideLine(); as line) {
          <span class="event-muted">{{ line }}</span>
        }
        <span class="event-muted"
          >Team sees: {{ latest.effectiveCorrect ? "Correct" : "Incorrect" }}</span
        >
        @if (!editable()) {
          <span class="event-muted"
            >Clue verdicts are final after the final opens.</span
          >
        }
        @if (error(); as text) {
          <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
        }
      </div>
      <span class="event-muted">{{ summary() }}</span>
    } @else {
      <span class="event-muted">No submission yet.</span>
    }`,
})
export class EventTeamSubmission {
  private readonly eventApi = inject(EventApi);

  readonly roomId = input.required<number>();
  readonly submission = input.required<LatestSubmission | null>();
  readonly submissionCount = input.required<number>();
  /** False once the final has opened. */
  readonly editable = input.required<boolean>();
  /** A verdict was saved (or refused); the page reloads its board. */
  readonly changed = output<void>();

  protected readonly options = VERDICT_OPTIONS;
  protected readonly expanded = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly isLong = computed(
    () => (this.submission()?.technicalOutput.length ?? 0) > PREVIEW_CHARACTERS,
  );

  protected readonly visibleOutput = computed(() => {
    const output = this.submission()?.technicalOutput ?? "";
    return this.isLong() && !this.expanded()
      ? `${output.slice(0, PREVIEW_CHARACTERS)}…`
      : output;
  });

  /** "Accepted by AI manager at 14:05" while an override is in force. */
  protected readonly overrideLine = computed(() => {
    const latest = this.submission();
    if (!latest || latest.override === "AUTOMATIC") return null;
    const verb = latest.override === "ACCEPT" ? "Accepted" : "Rejected";
    const time = latest.overrideAt
      ? new Date(latest.overrideAt).toLocaleTimeString([], {
          hour: "2-digit",
          minute: "2-digit",
        })
      : "";
    return `${verb} by ${latest.overrideByName ?? "a manager"} at ${time}`;
  });

  protected readonly summary = computed(() => {
    const latest = this.submission();
    if (!latest) return "";
    const count = this.submissionCount();
    const submissions = count === 1 ? "1 submission" : `${count} submissions`;
    return `Submitted ${formatTime(latest.receivedAt)} · round ${latest.round} · ${submissions}`;
  });

  protected async choose(
    latest: LatestSubmission,
    override: ClueOverride,
  ): Promise<void> {
    if (this.saving() || latest.override === override) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.eventApi.setClueOverride(this.roomId(), latest.id, {
        override,
        version: latest.verdictVersion,
      });
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}
