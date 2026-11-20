import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { MlStageBase } from "./ml-stage-base";
import { EventRecoveryStageForm } from "./recovery-stage-form";

/** Stage 1: keep every feature that would exist at prediction time. All start ticked, as in the prototype. */
@Component({
  selector: "event-ml-leakage",
  imports: [EventRecoveryStageForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./ml-stage.css",
  template: `<event-recovery-stage-form
    [action]="stage().action"
    [busy]="busy()"
    [waitLeft]="waitLeft()"
    [disabled]="kept().size === 0"
    (submitted)="send()"
  >
    <fieldset class="stage-group" data-ml-features>
      <legend>Features the model may use</legend>
      @for (feature of features(); track feature) {
        <label class="choice" [class.choice--on]="kept().has(feature)">
          <input type="checkbox" [checked]="kept().has(feature)" (change)="toggle(feature)" [attr.data-feature]="feature" />
          <span class="mono">{{ feature }}</span>
        </label>
      }
    </fieldset>
  </event-recovery-stage-form>`,
})
export class EventMlLeakage extends MlStageBase {
  protected readonly features = computed(() => this.stage().leakage?.features ?? []);
  /** The team's own selection; null until the first change, so every feature starts kept. */
  private readonly chosen = signal<ReadonlySet<string> | null>(null);
  protected readonly kept = computed<ReadonlySet<string>>(() => this.chosen() ?? new Set(this.features()));

  protected toggle(feature: string): void {
    const next = new Set(this.kept());
    if (!next.delete(feature)) next.add(feature);
    this.chosen.set(next);
  }

  protected send(): void {
    this.answer.emit({ features: this.features().filter((feature) => this.kept().has(feature)) });
  }
}
