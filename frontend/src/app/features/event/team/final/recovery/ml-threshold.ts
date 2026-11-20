import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { MlStageBase } from "./ml-stage-base";
import { hundredths, metricsAt, twoDecimals } from "./ml-logic";
import { EventRecoveryStageForm } from "./recovery-stage-form";

/**
 * Stage 5: the detection threshold on a slider (a native range input, so the arrow keys work) with the live precision,
 * recall, F1 and false negatives. The numbers are the server's for each slider step (display only); the server
 * decides whether the sent threshold passes.
 */
@Component({
  selector: "event-ml-threshold",
  imports: [EventRecoveryStageForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./ml-stage.css",
  styles: `
    .range {
      display: flex;
      align-items: center;
      gap: var(--space-4);
    }
    .range label {
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-muted);
    }
    .range input {
      flex: 1;
      min-inline-size: 0;
      block-size: 44px;
      accent-color: var(--color-primary);
    }
    .range output {
      min-inline-size: 3.5ch;
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-bold);
      font-variant-numeric: tabular-nums;
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
      gap: var(--space-3);
      margin: 0;
    }
    .tile {
      display: grid;
      gap: var(--space-1);
      padding: var(--space-3) var(--space-4);
      background: var(--color-surface-subtle);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .tile dt {
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-muted);
    }
    .tile dd {
      margin: 0;
      font-size: var(--font-size-xl);
      font-weight: var(--font-weight-bold);
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `<event-recovery-stage-form
    [action]="stage().action"
    [busy]="busy()"
    [waitLeft]="waitLeft()"
    [disabled]="slider() === null"
    (submitted)="send()"
  >
    @if (slider(); as s) {
      <div class="range">
        <label for="ml-threshold">{{ label() }}</label>
        <input
          id="ml-threshold"
          type="range"
          [min]="s.min"
          [max]="s.max"
          [step]="s.step"
          [value]="value()"
          (input)="setValue($event)"
          data-ml-threshold
        />
        <output for="ml-threshold" data-ml-threshold-value>{{ two(value()) }}</output>
      </div>
      <dl class="tiles" data-ml-metrics aria-live="polite">
        <div class="tile">
          <dt>Precision</dt>
          <dd data-metric="precision">{{ shown()?.precision ?? "—" }}</dd>
        </div>
        <div class="tile">
          <dt>Recall</dt>
          <dd data-metric="recall">{{ shown()?.recall ?? "—" }}</dd>
        </div>
        <div class="tile">
          <dt>F1</dt>
          <dd data-metric="f1">{{ shown()?.f1 ?? "—" }}</dd>
        </div>
        <div class="tile">
          <dt>False Negatives</dt>
          <dd data-metric="falseNegatives">{{ shown()?.falseNegatives ?? "—" }}</dd>
        </div>
      </dl>
    }
  </event-recovery-stage-form>`,
})
export class EventMlThreshold extends MlStageBase {
  protected readonly slider = computed(() => this.stage().threshold?.slider ?? null);
  protected readonly label = computed(() => this.stage().threshold?.label ?? "Detection threshold");
  private readonly picked = signal<number | null>(null);
  /** The slider's value: the stage's start value until the team moves it. */
  protected readonly value = computed(() => this.picked() ?? this.slider()?.start ?? 0);
  protected readonly two = twoDecimals;
  protected readonly shown = computed(() => {
    const m = metricsAt(this.stage().threshold?.metrics ?? [], this.value());
    return (
      m && {
        precision: twoDecimals(m.precision),
        recall: twoDecimals(m.recall),
        f1: twoDecimals(m.f1),
        falseNegatives: m.falseNegatives,
      }
    );
  });

  protected setValue(event: Event): void {
    this.picked.set(hundredths(Number((event.target as HTMLInputElement).value)) / 100);
  }

  protected send(): void {
    this.answer.emit({ threshold: this.value() });
  }
}
