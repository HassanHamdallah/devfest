import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { ThreatData, dataOf } from "./sentinel-data";
import { parseDecimal } from "./stage-camera";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** The width of the longest bar. */
const BAR_SCALE_PX = 140;

/** Stage 8: Naive Bayes by hand on one message: prior × the probability of each word, for both classes. */
@Component({
  selector: "sentinel-stage-threat",
  imports: [SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .message {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .message li {
      padding: var(--space-1) var(--space-3);
      font-family: var(--font-mono);
      font-weight: var(--font-weight-semibold);
      background: var(--room-tint);
      border: var(--border-outline-thin);
      border-radius: var(--radius-full);
    }
    .in-message th,
    .in-message td {
      background: var(--room-tint);
      font-weight: var(--font-weight-semibold);
    }
    .bar {
      display: block;
      block-size: 8px;
      background: var(--color-primary);
      border-radius: 4px;
    }
    .bar--normal {
      background: var(--brand-green-ink, var(--color-success));
    }
    .opts--row {
      grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="complete()"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      [hint]="complete() ? null : 'Enter both values and choose the class.'"
      (confirmed)="send()"
    >
      <div class="stage stage--split">
        <div class="stage__side">
          <div class="panel">
            <h3>Message</h3>
            <ul class="message" data-message>
              @for (word of data().message; track $index) {
                <li>{{ word }}</li>
              }
            </ul>
            <p>Priors: P(Threat) = {{ data().priors.threat }}, P(Normal) = {{ data().priors.normal }}</p>
          </div>
          <div class="table-wrap">
            <table data-table>
              <caption class="event-sr-only">Word probabilities by class</caption>
              <thead>
                <tr>
                  <th scope="col">Word</th>
                  <th scope="col">P(word | Threat)</th>
                  <th scope="col">P(word | Normal)</th>
                </tr>
              </thead>
              <tbody>
                @for (row of data().table; track row.word) {
                  <tr [class.in-message]="data().message.includes(row.word)">
                    <th scope="row">{{ row.word }}</th>
                    <td class="num"><span class="bar" data-bar [attr.data-value]="row.threat" [style.inline-size.px]="barWidth(row.threat)"></span>{{ row.threat }}</td>
                    <td class="num"><span class="bar bar--normal" data-bar [attr.data-value]="row.normal" [style.inline-size.px]="barWidth(row.normal)"></span>{{ row.normal }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
        <div class="stage__side">
          <div class="field">
            <label for="thr-threat">Threat value</label>
            <input id="thr-threat" inputmode="decimal" autocomplete="off" [value]="threat()" (input)="threat.set(text($event))" [disabled]="readOnly()" aria-describedby="thr-hint" />
          </div>
          <div class="field">
            <label for="thr-normal">Normal value</label>
            <input id="thr-normal" inputmode="decimal" autocomplete="off" [value]="normal()" (input)="normal.set(text($event))" [disabled]="readOnly()" aria-describedby="thr-hint" />
          </div>
          <p id="thr-hint" class="field__hint">Decimals such as 0.00042, or 4.2e-4. Within 1% of the exact value is accepted.</p>
          <fieldset>
            <legend>Which class wins?</legend>
            <div class="opts opts--row">
              @for (name of data().classes; track name) {
                <label class="opt"><input type="radio" name="thr-label" [checked]="label() === name" [disabled]="readOnly()" (change)="label.set(name)" />{{ name }}</label>
              }
            </div>
          </fieldset>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageThreat extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<ThreatData>(this.stage()));
  protected readonly threat = signal("");
  protected readonly normal = signal("");
  protected readonly label = signal<string | null>(null);
  protected readonly complete = computed(
    () => (parseDecimal(this.threat()) ?? -1) >= 0 && (parseDecimal(this.normal()) ?? -1) >= 0 && this.label() !== null,
  );

  /** The largest probability in the table: every bar is drawn on this one scale. */
  private readonly largest = computed(() => Math.max(...this.data().table.flatMap((row) => [row.threat, row.normal]), Number.MIN_VALUE));

  /** A bar's width in px: exactly proportional to its value, the same scale for both columns, no minimum. */
  protected barWidth(value: number): number {
    return (value / this.largest()) * BAR_SCALE_PX;
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected send(): void {
    void this.submit({ threat: parseDecimal(this.threat()), normal: parseDecimal(this.normal()), label: this.label() });
  }
}
