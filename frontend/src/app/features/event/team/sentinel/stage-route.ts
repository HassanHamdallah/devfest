import { ChangeDetectionStrategy, Component, computed, linkedSignal } from "@angular/core";
import { RouteData, dataOf, plain } from "./sentinel-data";
import { Search, expand, foundRoute, frontier, startSearch } from "./sentinel-logic";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/**
 * Stage 7: A* by hand on the campus graph. Click (or press Enter on) a place on the frontier to expand it; its
 * f = g + h is shown on the map and in the list. The search the team ran is what it submits.
 */
@Component({
  selector: "sentinel-stage-route",
  imports: [SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .road {
      stroke: var(--color-text-muted);
      stroke-width: 2.5;
    }
    .road--path {
      stroke: var(--color-success);
      stroke-width: 6;
    }
    .cost {
      font-size: 16px;
      font-weight: 700;
      fill: var(--color-text);
      paint-order: stroke;
      stroke: var(--color-surface);
      stroke-width: 4px;
    }
    .node circle {
      fill: var(--color-surface);
      stroke: var(--color-text-muted);
      stroke-width: 2;
    }
    .node--frontier circle {
      fill: var(--brand-yellow);
      stroke: var(--color-text);
      stroke-width: 3;
    }
    .node--open {
      cursor: pointer;
    }
    .node--open:hover circle {
      stroke-width: 5;
    }
    .node--open:focus-visible {
      outline: none;
    }
    .node--open:focus-visible circle {
      stroke: var(--color-primary);
      stroke-width: 6;
    }
    .node--done circle {
      fill: var(--color-success-subtle);
      stroke: var(--color-success);
      stroke-width: 3;
    }
    .node text {
      font-size: 15px;
      fill: var(--color-text);
      text-anchor: middle;
      paint-order: stroke;
      stroke: var(--color-surface);
      stroke-width: 3px;
    }
    .node .name {
      font-weight: 700;
    }
    .node .order {
      font-size: 17px;
      font-weight: 800;
      dominant-baseline: central;
      stroke: none;
    }
    @media (max-width: 600px) {
      .node text {
        font-size: 19px;
      }
      .cost {
        font-size: 20px;
      }
    }
    .frontier-list {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .frontier-list .tool {
      inline-size: 100%;
      text-align: start;
    }
    .rule {
      margin: 0;
      font-weight: var(--font-weight-semibold);
    }
    .goal-row {
      display: grid;
      gap: var(--space-1);
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="done()"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      [hint]="done() ? null : 'Expand ' + goalName() + ' to finish the search.'"
      (confirmed)="send()"
    >
      <div class="stage stage--split">
        <div>
          <svg class="plot" viewBox="0 0 660 450" role="img" aria-labelledby="route-title route-desc">
            <title id="route-title">Campus map: places, roads with costs and estimates h</title>
            <desc id="route-desc">
              From {{ placeName(data().start) }} to {{ goalName() }}. Expanded so far: {{ expandedNames() || 'nothing' }}.
            </desc>
            @for (road of data().roads; track $index) {
              <svg:line class="road" [class.road--path]="onPath(road.from, road.to)" [attr.x1]="at(road.from).x" [attr.y1]="at(road.from).y" [attr.x2]="at(road.to).x" [attr.y2]="at(road.to).y" />
            }
            @for (road of data().roads; track $index) {
              <svg:text class="cost" text-anchor="middle" [attr.x]="(at(road.from).x + at(road.to).x) / 2" [attr.y]="(at(road.from).y + at(road.to).y) / 2 + 4">{{ road.cost }}</svg:text>
            }
            @for (place of data().places; track place.id) {
              <svg:g
                class="node"
                [class.node--frontier]="isFrontier(place.id)"
                [class.node--open]="isFrontier(place.id) && !readOnly() && !done()"
                [class.node--done]="isExpanded(place.id)"
                [attr.transform]="'translate(' + place.x + ' ' + place.y + ')'"
                [attr.role]="isFrontier(place.id) && !done() ? 'button' : null"
                [attr.tabindex]="isFrontier(place.id) && !readOnly() && !done() ? 0 : null"
                [attr.aria-label]="isFrontier(place.id) ? 'Expand ' + place.name + ', f ' + plain(f(place.id)) : null"
                (click)="open(place.id)"
                (keydown.enter)="open(place.id)"
                (keydown.space)="open(place.id); $event.preventDefault()"
              >
                <svg:circle r="26" />
                @if (isExpanded(place.id)) {
                  <svg:text class="order" y="0">{{ orderOf(place.id) }}</svg:text>
                }
                <svg:text class="name" y="-35">{{ place.name }}</svg:text>
                <svg:text y="48">h = {{ plain(place.h) }}{{ isFrontier(place.id) ? ' · f = ' + plain(f(place.id)) : '' }}</svg:text>
              </svg:g>
            }
          </svg>
          <p class="plot-caption">Numbers on the roads are costs. Yellow places are on the frontier; green places are expanded, numbered in order.</p>
        </div>
        <div class="stage__side">
          <p class="rule" data-tie-break>{{ data().tieBreak }}</p>
          <div class="panel">
            <h3>Frontier</h3>
            @if (done()) {
              <p>The search reached {{ goalName() }}.</p>
            } @else {
              <ul class="frontier-list" data-frontier>
                @for (item of options(); track item.place.id) {
                  <li>
                    <button type="button" class="tool" [attr.data-place]="item.place.id" [disabled]="readOnly()" (click)="open(item.place.id)">
                      Expand {{ item.place.name }} <span class="mono">g {{ plain(item.g) }} + h {{ plain(item.place.h) }} = f {{ plain(item.f) }}</span>
                    </button>
                  </li>
                }
              </ul>
            }
          </div>
          <div class="panel goal-row" aria-live="polite">
            <p data-order><strong>Expanded:</strong> {{ expandedNames() || 'nothing yet' }}</p>
            @if (route().path.length > 0) {
              <p data-route><strong>Route found:</strong> {{ pathNames() }} · cost {{ plain(route().cost) }}</p>
            }
          </div>
          <div class="btn-row">
            <button type="button" class="tool" data-reset [disabled]="readOnly() || search().expanded.length === 0" (click)="reset()">Start again</button>
          </div>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageRoute extends SentinelStageBase {
  protected readonly plain = plain;
  protected readonly data = computed(() => dataOf<RouteData>(this.stage()));
  protected readonly search = linkedSignal<string, Search>({
    source: () => this.data().places.map((p) => p.id).join(","),
    computation: () => startSearch(this.data()),
  });
  protected readonly options = computed(() => frontier(this.data(), this.search()));
  protected readonly route = computed(() => foundRoute(this.data(), this.search()));
  protected readonly done = computed(() => this.search().expanded.includes(this.data().goal));
  protected readonly goalName = computed(() => this.placeName(this.data().goal));
  protected readonly expandedNames = computed(() => this.search().expanded.map((id) => this.placeName(id)).join(" → "));
  protected readonly pathNames = computed(() => this.route().path.map((id) => this.placeName(id)).join(" → "));

  protected placeName(id: string): string {
    return this.data().places.find((p) => p.id === id)?.name ?? id;
  }

  protected at(id: string): { x: number; y: number } {
    const place = this.data().places.find((p) => p.id === id);
    return { x: place?.x ?? 0, y: place?.y ?? 0 };
  }

  protected isFrontier(id: string): boolean {
    return this.options().some((o) => o.place.id === id);
  }

  protected isExpanded(id: string): boolean {
    return this.search().expanded.includes(id);
  }

  protected orderOf(id: string): number {
    return this.search().expanded.indexOf(id) + 1;
  }

  protected f(id: string): number {
    return this.options().find((o) => o.place.id === id)?.f ?? 0;
  }

  protected onPath(from: string, to: string): boolean {
    const path = this.route().path;
    return path.some((id, i) => i > 0 && ((path[i - 1] === from && id === to) || (path[i - 1] === to && id === from)));
  }

  protected open(id: string): void {
    if (this.readOnly() || this.done() || !this.isFrontier(id)) return;
    this.search.update((s) => expand(this.data(), s, id));
  }

  protected reset(): void {
    this.search.set(startSearch(this.data()));
  }

  protected send(): void {
    const route = this.route();
    void this.submit({ order: this.search().expanded, path: route.path, cost: route.cost });
  }
}
