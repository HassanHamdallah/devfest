import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { FeatureData, dataOf } from "./sentinel-data";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Stage 2: for each column decide whether the model may use it, or it must be dropped. */
@Component({
  selector: "sentinel-stage-features",
  imports: [SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .flow {
      display: grid;
      grid-template-columns: 1fr auto 1fr;
      align-items: center;
      gap: var(--space-2);
      max-inline-size: 640px;
    }
    .flow__box {
      padding: var(--space-3);
      text-align: center;
      font-weight: var(--font-weight-semibold);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .flow__box--target {
      background: var(--room-tint);
    }
    .flow svg {
      inline-size: 36px;
      block-size: 20px;
    }
    .flow path {
      stroke: var(--color-text-muted);
      stroke-width: 2;
      fill: none;
    }
    .features {
      display: grid;
      gap: var(--space-2);
      max-inline-size: 640px;
    }
    .feature {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: var(--space-3);
      padding: var(--space-2) var(--space-3);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    .feature legend {
      float: inline-start;
      margin: 0;
      font-weight: var(--font-weight-semibold);
    }
    .feature .choices {
      display: flex;
      gap: var(--space-2);
    }
    @media (max-width: 520px) {
      .feature {
        grid-template-columns: 1fr;
      }
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="complete()"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      [hint]="complete() ? null : remaining() + ' column' + (remaining() === 1 ? '' : 's') + ' left to decide.'"
      (confirmed)="send()"
    >
      <div class="stage">
        <div class="flow" aria-hidden="true">
          <div class="flow__box">Inputs you keep</div>
          <svg viewBox="0 0 36 20"><path d="M2 10h28m-6-6 6 6-6 6" /></svg>
          <div class="flow__box flow__box--target">Predict: {{ data().target }}</div>
        </div>
        <div class="features" data-features>
          @for (feature of data().features; track feature.id) {
            <fieldset class="feature">
              <legend>{{ feature.label }}</legend>
              <div class="choices">
                <label class="opt"><input type="radio" [name]="'feat-' + feature.id" [checked]="choices()[feature.id] === 'USE'" [disabled]="readOnly()" (change)="choose(feature.id, 'USE')" />Use</label>
                <label class="opt"><input type="radio" [name]="'feat-' + feature.id" [checked]="choices()[feature.id] === 'DROP'" [disabled]="readOnly()" (change)="choose(feature.id, 'DROP')" />Drop</label>
              </div>
            </fieldset>
          }
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageFeatures extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<FeatureData>(this.stage()));
  protected readonly choices = signal<Record<string, "USE" | "DROP">>({});
  protected readonly remaining = computed(() => this.data().features.filter((f) => !this.choices()[f.id]).length);
  protected readonly complete = computed(() => this.remaining() === 0);

  protected choose(id: string, choice: "USE" | "DROP"): void {
    this.choices.update((current) => ({ ...current, [id]: choice }));
  }

  protected send(): void {
    void this.submit({ choices: this.choices() });
  }
}
