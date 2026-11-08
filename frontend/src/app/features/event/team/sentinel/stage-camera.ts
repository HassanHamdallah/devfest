import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { CameraData, dataOf } from "./sentinel-data";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Parses what a team typed: "0,75" and "0.75" both work; anything else is not a number. */
export function parseDecimal(text: string): number | null {
  const trimmed = text.trim().replace(",", ".");
  if (trimmed === "" || !/^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

export function parseCount(text: string): number | null {
  return /^\d{1,4}$/.test(text.trim()) ? Number(text.trim()) : null;
}

/** Stage 5: count the camera's hits and misses from 20 frames, then precision, recall and the metric to trust. */
@Component({
  selector: "sentinel-stage-camera",
  imports: [SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .frames {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0 var(--space-4);
    }
    .frames table {
      min-inline-size: 0;
    }
    @media (min-width: 900px) {
      .stage--split {
        grid-template-columns: minmax(0, 1fr) minmax(300px, 400px);
      }
    }
    .matrix {
      display: grid;
      grid-template-columns: 4.5rem minmax(0, 1fr) minmax(0, 1fr);
      gap: var(--space-2);
      align-items: center;
    }
    .matrix__head {
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-muted);
      text-align: center;
    }
    .opts--row {
      grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
    }
    .yes {
      font-weight: var(--font-weight-semibold);
    }
    @media (max-width: 520px) {
      .frames {
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
      [hint]="complete() ? null : 'Fill in every box and choose a metric.'"
      (confirmed)="send()"
    >
      <div class="stage stage--split">
        <div class="frames" data-frames>
          @for (half of halves(); track $index; let h = $index) {
            <div class="table-wrap">
              <table>
                <caption class="event-sr-only">Frames {{ h * half0() + 1 }} to {{ h * half0() + half.length }}</caption>
                <thead>
                  <tr>
                    <th scope="col">#</th>
                    <th scope="col">{{ data().positive }} there?</th>
                    <th scope="col">Camera said</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of half; track $index) {
                    <tr>
                      <th scope="row" class="num">{{ h * half0() + $index + 1 }}</th>
                      <td [class.yes]="row.actual">{{ row.actual ? 'Yes' : 'No' }}</td>
                      <td [class.yes]="row.predicted">{{ row.predicted ? 'Yes' : 'No' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
        <div class="stage__side">
          <fieldset>
            <legend>Counts</legend>
            <div class="matrix">
              <span></span>
              <span class="matrix__head">Camera said yes</span>
              <span class="matrix__head">Camera said no</span>
              <span class="matrix__head">{{ data().positive }} was there</span>
              <div class="field"><label for="cam-tp">TP</label><input id="cam-tp" inputmode="numeric" autocomplete="off" [value]="tp()" (input)="tp.set(text($event))" [disabled]="readOnly()" /></div>
              <div class="field"><label for="cam-fn">FN</label><input id="cam-fn" inputmode="numeric" autocomplete="off" [value]="fn()" (input)="fn.set(text($event))" [disabled]="readOnly()" /></div>
              <span class="matrix__head">No {{ data().positive.toLowerCase() }}</span>
              <div class="field"><label for="cam-fp">FP</label><input id="cam-fp" inputmode="numeric" autocomplete="off" [value]="fp()" (input)="fp.set(text($event))" [disabled]="readOnly()" /></div>
              <div class="field"><label for="cam-tn">TN</label><input id="cam-tn" inputmode="numeric" autocomplete="off" [value]="tn()" (input)="tn.set(text($event))" [disabled]="readOnly()" /></div>
            </div>
          </fieldset>
          <div class="fields">
            <div class="field">
              <label for="cam-precision">Precision</label>
              <input id="cam-precision" inputmode="decimal" autocomplete="off" [value]="precision()" (input)="precision.set(text($event))" [disabled]="readOnly()" aria-describedby="cam-decimal" />
            </div>
            <div class="field">
              <label for="cam-recall">Recall</label>
              <input id="cam-recall" inputmode="decimal" autocomplete="off" [value]="recall()" (input)="recall.set(text($event))" [disabled]="readOnly()" aria-describedby="cam-decimal" />
            </div>
          </div>
          <p id="cam-decimal" class="field__hint">As decimals, for example 0.80 (not 80%). Two decimals are enough.</p>
          <fieldset>
            <legend>Which metric matters most for not missing a {{ data().positive.toLowerCase() }}?</legend>
            <div class="opts opts--row">
              @for (name of data().metrics; track name) {
                <label class="opt"><input type="radio" name="cam-metric" [checked]="metric() === name" [disabled]="readOnly()" (change)="metric.set(name)" />{{ name }}</label>
              }
            </div>
          </fieldset>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageCamera extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<CameraData>(this.stage()));
  protected readonly halves = computed(() => {
    const rows = this.data().rows;
    const half = Math.ceil(rows.length / 2);
    return [rows.slice(0, half), rows.slice(half)];
  });
  protected readonly half0 = computed(() => Math.ceil(this.data().rows.length / 2));
  protected readonly tp = signal("");
  protected readonly fp = signal("");
  protected readonly fn = signal("");
  protected readonly tn = signal("");
  protected readonly precision = signal("");
  protected readonly recall = signal("");
  protected readonly metric = signal<string | null>(null);
  protected readonly complete = computed(
    () =>
      [this.tp(), this.fp(), this.fn(), this.tn()].every((v) => parseCount(v) !== null) &&
      [this.precision(), this.recall()].every((v) => parseDecimal(v) !== null) &&
      this.metric() !== null,
  );

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected send(): void {
    void this.submit({
      tp: parseCount(this.tp()),
      fp: parseCount(this.fp()),
      fn: parseCount(this.fn()),
      tn: parseCount(this.tn()),
      precision: parseDecimal(this.precision()),
      recall: parseDecimal(this.recall()),
      metric: this.metric(),
    });
  }
}
