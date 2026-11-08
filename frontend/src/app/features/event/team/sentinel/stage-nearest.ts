import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { SentinelAxes } from "./sentinel-axes";
import { NearestData, dataOf } from "./sentinel-data";
import { makePlot, rankVehicles, sx, sy, unitsX, unitsY, votes } from "./sentinel-logic";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Stage 4: the k control draws the circle of the k nearest labelled vehicles and their votes. */
@Component({
  selector: "sentinel-stage-nearest",
  imports: [SentinelAxes, SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .k {
      display: grid;
      gap: var(--space-2);
    }
    .k input[type="range"] {
      inline-size: 100%;
      min-block-size: 44px;
      accent-color: var(--color-primary);
    }
    .k__value {
      font-weight: var(--font-weight-semibold);
    }
    .vote {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .vote li {
      padding: var(--space-1) var(--space-3);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-full);
    }
    .opts--row {
      grid-template-columns: repeat(auto-fit, minmax(90px, 1fr));
    }
    /* The unknown vehicle: a hollow diamond drawn last, so every point under it stays visible. */
    .unknown {
      fill: none;
      pointer-events: none;
      stroke-linejoin: miter;
    }
    .unknown--halo {
      stroke: white;
      stroke-width: 5.5;
    }
    .unknown--ink {
      stroke: var(--color-text);
      stroke-width: 2.5;
    }
    .key-unknown {
      fill: none;
      stroke: var(--color-text);
      stroke-width: 2;
    }
    .link {
      stroke: var(--color-text);
      stroke-width: 1.5;
      stroke-dasharray: 3 3;
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="k1() !== null && k5() !== null && reliable() !== null"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      [hint]="'Answer the three questions.'"
      (confirmed)="send()"
    >
      <div class="stage stage--split">
        <div>
          <svg class="plot" [attr.viewBox]="'0 0 ' + plot().width + ' ' + plot().height" role="img" aria-labelledby="near-title near-desc">
            <title id="near-title">Labelled vehicles by length and height, and the unknown vehicle</title>
            <desc id="near-desc">With k = {{ k() }} the nearest vehicles vote: {{ voteText() }}.</desc>
            <svg:g sentinelAxes [plot]="plot()" xLabel="length (m)" yLabel="height (m)" />
            <svg:ellipse class="ring" [attr.cx]="sx(data().unknown.length)" [attr.cy]="sy(data().unknown.height)" [attr.rx]="radius() * ux()" [attr.ry]="radius() * uy()" />
            @for (item of ranked().slice(0, k()); track item.vehicle.id) {
              <svg:line class="link" [attr.x1]="sx(data().unknown.length)" [attr.y1]="sy(data().unknown.height)" [attr.x2]="sx(item.vehicle.length)" [attr.y2]="sy(item.vehicle.height)" />
            }
            @for (vehicle of data().vehicles; track vehicle.id) {
              @switch (shapeOf(vehicle.kind)) {
                @case (0) {
                  <svg:circle class="pt" [attr.cx]="sx(vehicle.length)" [attr.cy]="sy(vehicle.height)" [attr.r]="isNear(vehicle.id) ? 8 : 6"><svg:title>{{ vehicle.label }}: {{ vehicle.kind }}</svg:title></svg:circle>
                }
                @case (1) {
                  <svg:rect class="pt pt--c" [attr.x]="sx(vehicle.length) - (isNear(vehicle.id) ? 7 : 5)" [attr.y]="sy(vehicle.height) - (isNear(vehicle.id) ? 7 : 5)" [attr.width]="isNear(vehicle.id) ? 14 : 10" [attr.height]="isNear(vehicle.id) ? 14 : 10"><svg:title>{{ vehicle.label }}: {{ vehicle.kind }}</svg:title></svg:rect>
                }
                @default {
                  <svg:path class="pt pt--b" [attr.d]="triangle(sx(vehicle.length), sy(vehicle.height), isNear(vehicle.id) ? 9 : 7)"><svg:title>{{ vehicle.label }}: {{ vehicle.kind }}</svg:title></svg:path>
                }
              }
            }
            <!-- The unknown vehicle is drawn last, on top: a hollow diamond (12 px wide) with a thin white halo. -->
            <svg:g data-unknown-vehicle>
              <svg:path class="unknown unknown--halo" [attr.d]="diamond(sx(data().unknown.length), sy(data().unknown.height), 6)" />
              <svg:path class="unknown unknown--ink" [attr.d]="diamond(sx(data().unknown.length), sy(data().unknown.height), 6)" />
            </svg:g>
          </svg>
          <ul class="legend" aria-hidden="true">
            @for (kind of data().classes; track kind) {
              <li>
                <svg width="16" height="16">
                  @switch (shapeOf(kind)) {
                    @case (0) { <circle cx="8" cy="8" r="6" class="pt" /> }
                    @case (1) { <rect x="3" y="3" width="10" height="10" class="pt pt--c" /> }
                    @default { <path d="M8 2 L14 14 L2 14 Z" class="pt pt--b" /> }
                  }
                </svg>
                {{ kind }}
              </li>
            }
            <li><svg width="16" height="16"><path d="M8 2 L14 8 L8 14 L2 8 Z" class="key-unknown" /></svg>unknown</li>
          </ul>
          <p class="plot-caption" data-plot-note>Distance is measured in metres on both axes, so the neighbourhood looks like an ellipse.</p>
        </div>
        <div class="stage__side">
          <div class="panel k">
            <label for="near-k" class="k__value">k = <span data-k>{{ k() }}</span> nearest</label>
            <input id="near-k" type="range" min="1" max="7" step="1" [value]="k()" (input)="setK($event)" [attr.aria-valuetext]="'k equals ' + k()" />
            <ul class="vote" data-votes aria-live="polite">
              @for (vote of votesNow(); track vote.kind) {
                <li>{{ vote.kind }} × {{ vote.count }}</li>
              }
            </ul>
          </div>
          <fieldset>
            <legend>Class when k = 1</legend>
            <div class="opts opts--row">
              @for (kind of data().classes; track kind) {
                <label class="opt"><input type="radio" name="near-k1" [checked]="k1() === kind" [disabled]="readOnly()" (change)="k1.set(kind)" />{{ kind }}</label>
              }
            </div>
          </fieldset>
          <fieldset>
            <legend>Class when k = 5</legend>
            <div class="opts opts--row">
              @for (kind of data().classes; track kind) {
                <label class="opt"><input type="radio" name="near-k5" [checked]="k5() === kind" [disabled]="readOnly()" (change)="k5.set(kind)" />{{ kind }}</label>
              }
            </div>
          </fieldset>
          <fieldset>
            <legend>Which setting is more reliable?</legend>
            <div class="opts opts--row">
              <label class="opt"><input type="radio" name="near-reliable" [checked]="reliable() === 1" [disabled]="readOnly()" (change)="reliable.set(1)" />k = 1</label>
              <label class="opt"><input type="radio" name="near-reliable" [checked]="reliable() === 5" [disabled]="readOnly()" (change)="reliable.set(5)" />k = 5</label>
            </div>
          </fieldset>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageNearest extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<NearestData>(this.stage()));
  protected readonly plot = computed(() => {
    const d = this.data();
    return makePlot([...d.vehicles.map((v) => v.length), d.unknown.length], [...d.vehicles.map((v) => v.height), d.unknown.height]);
  });
  protected readonly ranked = computed(() => rankVehicles(this.data().vehicles, this.data().unknown));
  protected readonly k = signal(1);
  protected readonly k1 = signal<string | null>(null);
  protected readonly k5 = signal<string | null>(null);
  protected readonly reliable = signal<1 | 5 | null>(null);
  /** Radius of the circle that just reaches the k-th nearest vehicle. */
  protected readonly radius = computed(() => this.ranked()[Math.min(this.k(), this.ranked().length) - 1]?.distance ?? 0);
  protected readonly ux = computed(() => unitsX(this.plot()));
  protected readonly uy = computed(() => unitsY(this.plot()));
  protected readonly votesNow = computed(() => votes(this.ranked(), this.k()));
  protected readonly voteText = computed(() => this.votesNow().map((v) => `${v.kind} ${v.count}`).join(", "));
  private readonly nearIds = computed(() => new Set(this.ranked().slice(0, this.k()).map((n) => n.vehicle.id)));

  protected sx(value: number): number {
    return sx(this.plot(), value);
  }

  protected sy(value: number): number {
    return sy(this.plot(), value);
  }

  protected isNear(id: string): boolean {
    return this.nearIds().has(id);
  }

  protected shapeOf(kind: string): number {
    return Math.min(2, this.data().classes.indexOf(kind));
  }

  protected triangle(x: number, y: number, size: number): string {
    return `M${x} ${y - size} L${x + size} ${y + size * 0.8} L${x - size} ${y + size * 0.8} Z`;
  }

  protected diamond(x: number, y: number, size: number): string {
    return `M${x} ${y - size} L${x + size} ${y} L${x} ${y + size} L${x - size} ${y} Z`;
  }

  protected setK(event: Event): void {
    this.k.set(Number((event.target as HTMLInputElement).value));
  }

  protected send(): void {
    void this.submit({ k1: this.k1(), k5: this.k5(), reliable: this.reliable() });
  }
}
