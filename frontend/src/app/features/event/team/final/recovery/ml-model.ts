import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { MlStageBase } from "./ml-stage-base";
import { twoDecimals } from "./ml-logic";
import { EventRecoveryStageForm } from "./recovery-stage-form";

/** Stage 4: three model cards with their metrics; pick one (a radio group, so the arrow keys work). */
@Component({
  selector: "event-ml-model",
  imports: [EventRecoveryStageForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./ml-stage.css",
  styles: `
    .models {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
      gap: var(--space-3);
    }
    .model {
      position: relative;
      display: grid;
      gap: var(--space-1);
      align-content: start;
      padding: var(--space-4);
      cursor: pointer;
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .model:hover {
      background: var(--color-surface-subtle);
    }
    .model:has(input:focus-visible) {
      outline: 2px solid var(--color-border-focus);
      outline-offset: 2px;
    }
    .model--on {
      background: var(--color-primary-subtle);
      border-color: var(--color-primary);
    }
    .model input {
      position: absolute;
      opacity: 0;
      pointer-events: none;
    }
    .model__name {
      font-weight: var(--font-weight-bold);
    }
    .model__metrics {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `<event-recovery-stage-form
    [action]="stage().action"
    [busy]="busy()"
    [waitLeft]="waitLeft()"
    [disabled]="picked() === null"
    (submitted)="send()"
  >
    <fieldset class="stage-group" data-ml-models>
      <legend>Models</legend>
      <div class="models">
        @for (model of models(); track model.id) {
          <label class="model" [class.model--on]="picked() === model.id" [attr.data-model]="model.id">
            <input type="radio" name="model" [value]="model.id" [checked]="picked() === model.id" (change)="picked.set(model.id)" />
            <span class="model__name">{{ model.name }}</span>
            <span class="model__metrics">Precision {{ two(model.precision) }} • Recall {{ two(model.recall) }}</span>
          </label>
        }
      </div>
    </fieldset>
  </event-recovery-stage-form>`,
})
export class EventMlModel extends MlStageBase {
  protected readonly models = computed(() => this.stage().model?.models ?? []);
  protected readonly picked = signal<string | null>(null);
  protected readonly two = twoDecimals;

  protected send(): void {
    const model = this.picked();
    if (model !== null) this.answer.emit({ model });
  }
}
