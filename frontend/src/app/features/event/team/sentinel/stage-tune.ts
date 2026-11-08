import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { TuneData, dataOf, runOf } from "./sentinel-data";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Stage 3: run up to four model settings (training and validation accuracy), then deploy any one. */
@Component({
  selector: "sentinel-stage-tune",
  imports: [SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .bars {
      display: grid;
      gap: 4px;
      min-inline-size: 150px;
    }
    .bar {
      display: grid;
      grid-template-columns: 6.2em 1fr 3.4em;
      align-items: center;
      gap: var(--space-2);
      font-size: var(--font-size-sm);
    }
    .bar svg {
      inline-size: 100%;
      block-size: 10px;
    }
    .bar__track {
      fill: var(--color-surface-subtle);
    }
    .bar__train {
      fill: var(--color-primary);
    }
    .bar__valid {
      fill: var(--brand-green-ink, var(--color-success));
    }
    .pick {
      display: grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      cursor: pointer;
    }
    .pick input {
      inline-size: 22px;
      block-size: 22px;
      accent-color: var(--color-primary);
    }
    .budget {
      font-weight: var(--font-weight-semibold);
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="deploy() !== null"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      submitLabel="Deploy this setting"
      [hint]="deploy() === null ? 'Choose the setting to deploy.' : null"
      (confirmed)="send()"
    >
      <div class="stage">
        <p class="budget" data-runs-left aria-live="polite">
          Runs left: {{ stage().runsLeft }} of {{ data().runsAllowed }}
        </p>
        <div class="table-wrap">
          <table>
            <caption class="event-sr-only">Model settings</caption>
            <thead>
              <tr>
                <th scope="col">Setting</th>
                <th scope="col">Accuracy</th>
                <th scope="col">Run</th>
                <th scope="col">Deploy</th>
              </tr>
            </thead>
            <tbody>
              @for (config of data().configs; track config.id) {
                <tr [attr.data-config]="config.id">
                  <th scope="row">{{ config.label }}</th>
                  <td>
                    @if (result(config.id); as shown) {
                      <div class="bars">
                        <div class="bar">
                          <span>Training</span>
                          <svg viewBox="0 0 100 10" preserveAspectRatio="none" aria-hidden="true"><rect class="bar__track" width="100" height="10" /><rect class="bar__train" [attr.width]="shown.train" height="10" /></svg>
                          <span class="num" data-train>{{ shown.train }}%</span>
                        </div>
                        <div class="bar">
                          <span>Validation</span>
                          <svg viewBox="0 0 100 10" preserveAspectRatio="none" aria-hidden="true"><rect class="bar__track" width="100" height="10" /><rect class="bar__valid" [attr.width]="shown.validation" height="10" /></svg>
                          <span class="num" data-validation>{{ shown.validation }}%</span>
                        </div>
                      </div>
                    } @else {
                      <span class="event-muted">Not run</span>
                    }
                  </td>
                  <td>
                    <button
                      type="button"
                      class="tool"
                      data-run
                      [attr.aria-label]="'Run ' + config.label"
                      [disabled]="readOnly() || running() !== null || ran(config.id) || stage().runsLeft === 0"
                      (click)="run(config.id)"
                    >
                      {{ ran(config.id) ? 'Done' : 'Run' }}
                    </button>
                  </td>
                  <td>
                    <label class="pick">
                      <input type="radio" name="tune-deploy" [checked]="deploy() === config.id" [disabled]="readOnly()" (change)="deploy.set(config.id)" [attr.aria-label]="'Deploy ' + config.label" />
                    </label>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageTune extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<TuneData>(this.stage()));
  protected readonly deploy = signal<string | null>(null);

  protected ran(config: string): boolean {
    return runOf(this.stage(), config) !== null;
  }

  protected result(config: string): { train: number; validation: number } | null {
    const run = runOf(this.stage(), config);
    return run ? { train: run.result["train"] as number, validation: run.result["validation"] as number } : null;
  }

  protected send(): void {
    const config = this.deploy();
    if (config) void this.submit({ config });
  }
}
