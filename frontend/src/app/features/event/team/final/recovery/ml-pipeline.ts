import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, signal } from "@angular/core";
import { MlStageBase } from "./ml-stage-base";
import { movedOrder, stepLabel } from "./ml-logic";
import { EventRecoveryStageForm } from "./recovery-stage-form";

/**
 * Stage 2: put the steps in order with up and down buttons (real buttons, so the keyboard works; focus follows the moved
 * step). A step the content relabels shows its new label once it is after the other step, as in the prototype.
 */
@Component({
  selector: "event-ml-pipeline",
  imports: [EventRecoveryStageForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./ml-stage.css",
  styles: `
    ol {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    li {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      min-block-size: 52px;
      padding: var(--space-1) var(--space-2) var(--space-1) var(--space-4);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .step__n {
      font-variant-numeric: tabular-nums;
      color: var(--color-text-muted);
    }
    .step__label {
      flex: 1;
      min-inline-size: 0;
      overflow-wrap: anywhere;
      font-weight: var(--font-weight-medium);
    }
    .step__moves {
      display: flex;
      gap: var(--space-1);
    }
    button {
      display: grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      font-size: var(--font-size-lg);
      line-height: 1;
      color: var(--color-text);
      cursor: pointer;
      background: var(--color-surface-subtle);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    button:hover:not(:disabled) {
      background: var(--color-primary-subtle);
    }
    button:focus-visible {
      outline: 2px solid var(--color-border-focus);
      outline-offset: 2px;
    }
    button:disabled {
      cursor: not-allowed;
      opacity: 0.4;
    }
  `,
  template: `<event-recovery-stage-form
    [action]="stage().action"
    [busy]="busy()"
    [waitLeft]="waitLeft()"
    (submitted)="send()"
  >
    <ol aria-label="Training pipeline, first step first" data-ml-pipeline>
      @for (item of items(); track item.id; let index = $index; let last = $last) {
        <li [attr.data-step]="item.id">
          <span class="step__n" aria-hidden="true">{{ index + 1 }}.</span>
          <span class="step__label">{{ labelOf(item) }}</span>
          <span class="step__moves">
            <button type="button" [disabled]="index === 0" [attr.aria-label]="'Move up: ' + labelOf(item)" (click)="move(index, -1)" data-move="up">
              <span aria-hidden="true">↑</span>
            </button>
            <button type="button" [disabled]="last" [attr.aria-label]="'Move down: ' + labelOf(item)" (click)="move(index, 1)" data-move="down">
              <span aria-hidden="true">↓</span>
            </button>
          </span>
        </li>
      }
    </ol>
  </event-recovery-stage-form>`,
})
export class EventMlPipeline extends MlStageBase {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  private readonly pipeline = computed(() => this.stage().pipeline);
  /** The team's order of step ids; null until the first move, so the stage starts in the server's start order. */
  private readonly chosen = signal<readonly string[] | null>(null);
  protected readonly order = computed(() => this.chosen() ?? this.pipeline()?.startOrder ?? []);
  protected readonly items = computed(() => {
    const byId = new Map((this.pipeline()?.items ?? []).map((item) => [item.id, item]));
    return this.order().flatMap((id) => byId.get(id) ?? []);
  });

  protected labelOf(item: { id: string; label: string }): string {
    return stepLabel(item, this.order(), this.pipeline()?.relabel ?? null);
  }

  protected move(index: number, delta: -1 | 1): void {
    const moved = movedOrder(this.order(), index, delta);
    this.chosen.set(moved);
    // The button that was pressed may now be at an edge (disabled): keep focus on that step's other button.
    const target = index + delta;
    const edge = target === 0 || target === moved.length - 1;
    const kind = edge ? (delta === -1 ? "down" : "up") : delta === -1 ? "up" : "down";
    afterNextRender(
      () => this.host.nativeElement.querySelectorAll<HTMLButtonElement>(`li:nth-child(${target + 1}) [data-move="${kind}"]`)[0]?.focus(),
      { injector: this.injector },
    );
  }

  protected send(): void {
    this.answer.emit({ order: [...this.order()] });
  }
}
