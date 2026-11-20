import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from "@angular/core";
import { recoveryMlPath } from "../../../../../core/event/event-api";
import { MlCheckRequest, RecoveryCheckResult, TeamRecovery } from "../../../../../core/event/event-models";
import { UiAlert } from "../../../../../shared/ui/ui-alert/ui-alert";
import { UiCard } from "../../../../../shared/ui/ui-card/ui-card";
import { EventAttemptStatus } from "../../attempt-status";
import { PendingAttempt } from "../../pending-attempt";
import { FinalWait } from "../final-wait";
import { EventMlCleaning } from "./ml-cleaning";
import { EventMlEvidence } from "./ml-evidence";
import { EventMlLeakage } from "./ml-leakage";
import { EventMlModel } from "./ml-model";
import { EventMlPipeline } from "./ml-pipeline";
import { EventMlThreshold } from "./ml-threshold";

/** The ML checks come at most once every 2 seconds per team (the server's limit); the button waits that long too. */
const ML_CHECK_SECONDS = 2;

interface LastCheck {
  passed: boolean;
  message: string;
}

/**
 * The ML Forensic Audit: the six stages on the left (done / current / locked), the current stage on the right. Every
 * check goes to the server, which answers with the stage's neutral message; a passed check moves on to the next stage on
 * the next load. Nothing here decides whether an answer is right.
 */
@Component({
  selector: "event-ml-audit",
  imports: [
    EventAttemptStatus,
    EventMlCleaning,
    EventMlEvidence,
    EventMlLeakage,
    EventMlModel,
    EventMlPipeline,
    EventMlThreshold,
    UiAlert,
    UiCard,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    .head {
      display: grid;
      gap: var(--space-1);
    }
    .head p {
      margin: 0;
    }
    .badge {
      justify-self: start;
      padding: 2px var(--space-3);
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.06em;
      color: var(--color-primary-text);
      background: var(--color-primary-subtle);
      border-radius: var(--radius-full);
    }
    .audit {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--space-4);
      margin-block-start: var(--space-4);
    }
    @media (min-width: 760px) {
      .audit {
        grid-template-columns: 230px minmax(0, 1fr);
      }
    }
    .stages {
      display: grid;
      gap: var(--space-2);
      align-content: start;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .stages li {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      min-block-size: 44px;
      padding: 0 var(--space-3);
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .stages .n {
      display: grid;
      flex: none;
      place-items: center;
      inline-size: 26px;
      block-size: 26px;
      font-weight: var(--font-weight-bold);
      background: var(--color-surface-subtle);
      border-radius: 50%;
    }
    .stages .done {
      color: var(--color-text);
    }
    .stages .done .n {
      color: var(--color-surface);
      background: var(--brand-green-ink);
    }
    .stages .current {
      font-weight: var(--font-weight-bold);
      color: var(--color-text);
      background: var(--color-primary-subtle);
      border-color: var(--color-primary);
    }
    .stages .current .n {
      color: var(--color-on-primary);
      background: var(--color-primary);
    }
    .stages .locked {
      opacity: 0.65;
    }
    .pane {
      display: grid;
      gap: var(--space-4);
      min-inline-size: 0;
      align-content: start;
    }
    .pane h3 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .pane p {
      margin: 0;
    }
    .note {
      font-size: var(--font-size-sm);
    }
    /* On a phone the six stages become compact numbered chips above the stage. */
    @media (max-width: 759px) {
      .stages {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
      .stages li {
        padding: 0 var(--space-2);
        font-size: var(--font-size-xs);
      }
      .stages .label {
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
      }
    }
  `,
  template: `<ui-card data-recovery-level="ML">
    <div class="head">
      <h2 ui-card-header>{{ title() }}</h2>
      <p class="event-muted">{{ intro }}</p>
      <span class="badge">{{ badge }}</span>
    </div>
    <div class="audit">
      <ol class="stages" aria-label="ML stages" data-ml-stages>
        @for (s of stages(); track s.stage) {
          <li
            [class]="stateOf(s.stage, s.passed)"
            [attr.data-stage]="s.stage"
            [attr.data-state]="stateOf(s.stage, s.passed)"
            [attr.aria-current]="s.stage === current() ? 'step' : null"
          >
            <span class="n" aria-hidden="true">{{ s.stage }}</span>
            <span class="label"><span class="event-sr-only">Stage {{ s.stage }}: </span>{{ s.name }}</span>
          </li>
        }
      </ol>
      <div class="pane">
        @if (last(); as result) {
          <ui-alert [variant]="result.passed ? 'success' : 'error'" data-recovery-result [attr.data-passed]="result.passed">{{ result.message }}</ui-alert>
        }
        @if (stage(); as view) {
          <h3 data-ml-title>{{ view.title }}</h3>
          @if (view.prompt) {
            <p data-ml-prompt>{{ view.prompt }}</p>
          }
          @switch (view.stage) {
            @case (1) {
              <event-ml-leakage [stage]="view" [busy]="attempt.sending()" [waitLeft]="wait.left()" (answer)="check($event)" />
            }
            @case (2) {
              <event-ml-pipeline [stage]="view" [busy]="attempt.sending()" [waitLeft]="wait.left()" (answer)="check($event)" />
            }
            @case (3) {
              <event-ml-cleaning [stage]="view" [busy]="attempt.sending()" [waitLeft]="wait.left()" (answer)="check($event)" />
            }
            @case (4) {
              <event-ml-model [stage]="view" [busy]="attempt.sending()" [waitLeft]="wait.left()" (answer)="check($event)" />
            }
            @case (5) {
              <event-ml-threshold [stage]="view" [busy]="attempt.sending()" [waitLeft]="wait.left()" (answer)="check($event)" />
            }
            @case (6) {
              <event-ml-evidence [stage]="view" [busy]="attempt.sending()" [waitLeft]="wait.left()" (answer)="check($event)" />
            }
          }
          <event-attempt-status [attempt]="attempt" (retry)="retry()" />
          <p class="note event-muted" data-ml-note>{{ view.note }}</p>
        }
      </div>
    </div>
  </ui-card>`,
})
export class EventMlAudit {
  readonly recovery = input.required<TeamRecovery>();
  /** The team's recovery changed on the server (a check was answered): the page reloads. */
  readonly changed = output<void>();

  protected readonly attempt = new PendingAttempt<MlCheckRequest, RecoveryCheckResult>("recovery");
  protected readonly wait = new FinalWait(inject(DestroyRef));
  protected readonly last = signal<LastCheck | null>(null);

  private readonly level = computed(() => this.recovery().progress.find((p) => p.level === "ML") ?? null);
  protected readonly stages = computed(() => this.level()?.stages ?? []);
  protected readonly current = computed(() => (this.recovery().currentLevel === "ML" ? this.recovery().currentStage : null));
  protected readonly stage = computed(() => this.recovery().mlStage);
  protected readonly title = computed(() => this.level()?.title ?? "ML Forensic Audit");
  protected readonly intro = "One continuous investigation with six locked stages.";
  protected readonly badge = "MULTI-STAGE • 15–25 MIN";

  constructor() {
    // The server's own count of the seconds still to wait (the page may have been reloaded in the middle of the limit).
    // Untracked: set() reads the countdown itself, and every tick must not restart it.
    effect(() => {
      const seconds = this.recovery().waitSeconds;
      untracked(() => this.wait.set(seconds));
    });
  }

  protected stateOf(stage: number, passed: boolean): "done" | "current" | "locked" {
    return passed ? "done" : stage === this.current() ? "current" : "locked";
  }

  protected async check(body: MlCheckRequest): Promise<void> {
    const stage = this.current();
    if (stage === null || this.wait.left() > 0) return;
    this.finish(await this.attempt.submit(recoveryMlPath(stage), body));
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt.retry());
  }

  private finish(result: RecoveryCheckResult | null): void {
    if (result) {
      this.last.set({ passed: result.passed, message: result.message });
      this.wait.set(ML_CHECK_SECONDS);
    }
    this.changed.emit();
  }
}
