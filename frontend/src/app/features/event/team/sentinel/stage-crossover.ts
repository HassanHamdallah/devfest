import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal } from "@angular/core";
import { Api } from "../../../../core/api";
import { SentinelStage } from "../../../../core/event/event-models";
import { SentinelBrief } from "./sentinel-brief";
import { CrossoverFigureData, InputData, dataOf } from "./sentinel-data";
import { sentinelSession } from "./sentinel-session";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

type Bit = "0" | "1";
type Row = "A" | "B";

/** The child being built: a bit or null per slot, and the one slot the mutation flipped (a star marks it). */
interface Child {
  bits: (Bit | null)[];
  flipped: number | null;
}

/** True when this INPUT stage draws a crossover: it gets the builder instead of the text box. */
export function isCrossoverStage(stage: SentinelStage): boolean {
  const figure = (stage.data as { figure?: { type?: string } } | null)?.figure;
  return stage.type === "INPUT" && figure?.type === "crossover";
}

const flip = (bit: Bit): Bit => (bit === "0" ? "1" : "0");

/**
 * Stage 6 as a builder (rules §19.2): the team makes the child by copying bits from the two parents into the child's
 * slots, then flips one bit as the mutation, and submits the 6-bit string through the same answer call the text box
 * used. Nothing here knows the right answer and nothing hints at it: every tile and slot looks the same, there is no cut
 * line and no marker (the cut and the mutated position are only in the question text), any parent bit may go into any
 * slot and any filled slot may be flipped. A flipped slot shows a star only after the team flipped it.
 *
 * Copy a bit by dragging a parent tile onto a slot, by tapping a tile and then a slot, or with the keyboard (Enter or
 * Space on a tile, then on a slot; Esc cancels). The Mutate button turns the slots into flip buttons; only one slot is
 * flipped at a time (flipping another moves the mutation). Undo reverts the last action, Reset starts again. The child
 * in progress is kept in this tab (sessionStorage) so a refresh does not lose it.
 */
@Component({
  selector: "sentinel-stage-crossover",
  imports: [SentinelBrief, SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .builder {
      display: grid;
      gap: var(--space-3);
      max-inline-size: 560px;
      --gap: 6px;
      --label: 4.6rem;
      --pad: var(--space-3);
    }
    /* On a phone the label column and the gaps shrink so the tiles stay big enough to tap. */
    @media (max-width: 480px) {
      .builder {
        --gap: 4px;
        --label: 3.3rem;
        --pad: var(--space-2);
      }
      .row__label {
        font-size: 12px;
        line-height: 1.15;
      }
    }
    .grid {
      position: relative;
      display: grid;
      row-gap: var(--space-2);
      padding: var(--space-3) var(--pad);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    .row {
      display: grid;
      grid-template-columns: var(--label) repeat(6, minmax(0, 1fr));
      align-items: center;
      gap: var(--gap);
    }
    .row__label {
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
    }
    .num {
      text-align: center;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .tile,
    .slot {
      position: relative;
      min-inline-size: 0;
      block-size: 46px;
      padding: 0;
      font-family: var(--font-mono);
      font-size: 20px;
      font-weight: var(--font-weight-semibold);
      color: var(--color-text);
      background: var(--color-surface-subtle);
      border: 2px solid var(--color-border);
      border-radius: var(--radius-sm);
      cursor: pointer;
    }
    .tile[draggable="true"] {
      cursor: grab;
    }
    .tile--picked {
      outline: 3px solid var(--room-color);
      outline-offset: 2px;
    }
    .slot {
      background: var(--color-surface);
      border-style: dashed;
      border-color: var(--color-text-muted);
    }
    .slot--filled {
      background: var(--color-surface-subtle);
      border-style: solid;
      border-color: var(--room-color);
    }
    .slot--over {
      background: var(--room-tint);
      border-style: solid;
      border-color: var(--room-color);
      box-shadow: 0 0 0 3px var(--color-focus-ring);
    }
    .slot--mutating.slot--filled {
      border-color: var(--brand-yellow);
      box-shadow: 0 0 0 2px var(--brand-yellow);
    }
    .tile:focus-visible,
    .slot:focus-visible {
      outline: 3px solid var(--room-color);
      outline-offset: 2px;
    }
    .tile:disabled,
    .slot:disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }
    .star {
      position: absolute;
      inset-block-start: -2px;
      inset-inline-end: 2px;
      font-size: 13px;
      line-height: 1;
      color: var(--brand-red-ink);
    }
    .slot--anim {
      animation: flip 0.36s ease-in-out;
    }
    @keyframes flip {
      0% {
        transform: rotateX(0deg);
      }
      50% {
        transform: rotateX(90deg);
      }
      100% {
        transform: rotateX(0deg);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .slot--anim {
        animation: none;
      }
    }
    .controls {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
    }
    .tool[aria-pressed="true"] {
      color: var(--room-ink);
      background: var(--room-tint);
      border-color: var(--room-color);
    }
    .status {
      margin: 0;
      font-weight: var(--font-weight-semibold);
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="shellStage()"
      [ready]="complete()"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      submitLabel="Submit child"
      [confirmTitle]="'Submit ' + (bits() ?? '') + '? One answer only.'"
      [hint]="complete() ? null : 'Fill all 6 slots of the child.'"
      (confirmed)="send()"
    >
      <div class="stage">
        <sentinel-brief data-crossover-brief [brief]="stage().brief" />
        <div class="builder" data-crossover (keydown.escape)="cancelPick()">
          <div class="grid" role="group" aria-label="Crossover builder">
            <div class="row" aria-hidden="true">
              <span></span>
              @for (n of positions(); track n) {
                <span class="num">{{ n }}</span>
              }
            </div>
            @for (parent of parents(); track parent.row) {
              <div class="row">
                <span class="row__label">Parent {{ parent.row }}</span>
                @for (bit of parent.bits; track $index; let i = $index) {
                  <button
                    type="button"
                    class="tile"
                    [class.tile--picked]="isPicked(parent.row, i)"
                    [attr.data-tile]="parent.row + '-' + (i + 1)"
                    [attr.aria-label]="'Parent ' + parent.row + ' bit ' + (i + 1) + ': ' + bit"
                    [attr.aria-pressed]="isPicked(parent.row, i)"
                    [attr.draggable]="locked() || mutating() ? 'false' : 'true'"
                    [disabled]="locked() || mutating()"
                    (click)="pickTile(parent.row, i)"
                    (dragstart)="dragStart($event, parent.row, i)"
                  >
                    {{ bit }}
                  </button>
                }
              </div>
            }
            <div class="row">
              <span class="row__label">Child</span>
              @for (bit of child().bits; track $index; let i = $index) {
                <button
                  type="button"
                  class="slot"
                  [class.slot--filled]="bit !== null"
                  [class.slot--over]="dropTarget() === i"
                  [class.slot--mutating]="mutating()"
                  [class.slot--anim]="animating() === i"
                  [attr.data-slot]="i + 1"
                  [attr.aria-label]="slotLabel(i)"
                  [disabled]="locked()"
                  (click)="slotClick(i)"
                  (dragenter)="dragOver($event, i)"
                  (dragover)="dragOver($event, i)"
                  (dragleave)="dragLeave(i)"
                  (drop)="drop($event, i)"
                >
                  {{ bit ?? "?" }}
                  @if (child().flipped === i) {
                    <span class="star" data-star aria-hidden="true">★</span>
                  }
                </button>
              }
            </div>
          </div>
          <div class="controls">
            <button type="button" class="tool" data-mutate [attr.aria-pressed]="mutating()" [disabled]="locked()" (click)="toggleMutate()">⚡ Mutate</button>
            <button type="button" class="tool" data-undo [disabled]="locked() || history().length === 0" (click)="undo()">Undo</button>
            <button type="button" class="tool" data-reset [disabled]="locked() || isEmpty()" (click)="reset()">Reset</button>
          </div>
          <p class="status" data-status>{{ status() }}</p>
          <div class="event-sr-only" aria-live="polite" data-live>{{ announce() }}</div>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageCrossover extends SentinelStageBase implements OnInit {
  private readonly api = inject(Api);

  protected readonly figure = computed(() => dataOf<InputData>(this.stage()).figure as CrossoverFigureData);
  protected readonly positions = computed(() => Array.from({ length: this.figure().parentA.length }, (_, i) => i + 1));
  protected readonly parents = computed(() => [
    { row: "A" as Row, bits: [...this.figure().parentA] as Bit[] },
    { row: "B" as Row, bits: [...this.figure().parentB] as Bit[] },
  ]);

  protected readonly child = signal<Child>({ bits: [], flipped: null });
  protected readonly history = signal<Child[]>([]);
  protected readonly picked = signal<{ row: Row; index: number } | null>(null);
  protected readonly mutating = signal(false);
  protected readonly dropTarget = signal<number | null>(null);
  protected readonly animating = signal<number | null>(null);
  protected readonly announce = signal("");
  private restored = false;

  /** The shell shows the brief as one paragraph; this stage shows it line by line, so the shell gets none. */
  protected readonly shellStage = computed<SentinelStage>(() => ({ ...this.stage(), brief: null }));
  protected readonly locked = computed(() => this.readOnly() || this.submitting());
  protected readonly complete = computed(() => this.child().bits.length > 0 && this.child().bits.every((bit) => bit !== null));
  protected readonly isEmpty = computed(() => this.child().bits.every((bit) => bit === null));
  /** The 6-bit string the text box used to take; null until every slot is filled. */
  protected readonly bits = computed(() => (this.complete() ? this.child().bits.join("") : null));
  protected readonly status = computed(() => {
    if (this.mutating()) return "Mutation mode: tap one child bit to flip it.";
    return this.complete() ? "Ready to submit." : "Fill the child, then apply the mutation.";
  });

  constructor() {
    super();
    effect(() => {
      const child = this.child();
      if (this.restored) this.save(child);
    });
  }

  ngOnInit(): void {
    const length = this.figure().parentA.length;
    this.child.set(this.restore(length) ?? { bits: Array<Bit | null>(length).fill(null), flipped: null });
    this.restored = true;
  }

  // ---- labels ---------------------------------------------------------------------------------------

  protected slotLabel(i: number): string {
    const bit = this.child().bits[i];
    if (bit === null) return `Child slot ${i + 1}: empty`;
    return `Child slot ${i + 1}: ${bit}${this.child().flipped === i ? ", flipped" : ""}`;
  }

  protected isPicked(row: Row, index: number): boolean {
    const picked = this.picked();
    return picked !== null && picked.row === row && picked.index === index;
  }

  // ---- crossover: copy a parent bit into a slot -----------------------------------------------------------

  /** Tap or Enter or Space on a parent tile: pick it (tap it again to put it down). */
  protected pickTile(row: Row, index: number): void {
    if (this.locked() || this.mutating()) return;
    if (this.isPicked(row, index)) {
      this.cancelPick();
      return;
    }
    this.picked.set({ row, index });
    this.announce.set(`Picked Parent ${row} bit ${index + 1}: ${this.parentBit(row, index)}. Now choose a child slot.`);
  }

  protected cancelPick(): void {
    if (this.picked() === null) return;
    this.picked.set(null);
    this.announce.set("Nothing picked.");
  }

  /** A click on a child slot: flip it in Mutate mode, else drop the picked bit into it. */
  protected slotClick(slot: number): void {
    if (this.locked()) return;
    if (this.mutating()) {
      this.flipSlot(slot);
      return;
    }
    const picked = this.picked();
    if (picked === null) {
      this.announce.set("Pick a parent bit first.");
      return;
    }
    this.place(slot, this.parentBit(picked.row, picked.index), `Parent ${picked.row} bit ${picked.index + 1}`);
    this.picked.set(null);
  }

  /** Copies a bit into a slot (a filled slot is replaced); a replaced flipped slot is no longer the mutation. */
  private place(slot: number, bit: Bit, from: string): void {
    const before = this.child();
    const bits = [...before.bits];
    const replaced = bits[slot] !== null;
    bits[slot] = bit;
    this.commit({ bits, flipped: before.flipped === slot ? null : before.flipped });
    this.announce.set(`${from} copied into child slot ${slot + 1}${replaced ? ", replacing the old bit" : ""}: ${bit}.`);
  }

  protected dragStart(event: DragEvent, row: Row, index: number): void {
    if (this.locked() || this.mutating() || !event.dataTransfer) return;
    event.dataTransfer.setData("text/plain", `${row}${index}`);
    event.dataTransfer.effectAllowed = "copy";
    this.picked.set(null);
  }

  protected dragOver(event: DragEvent, slot: number): void {
    if (this.locked() || this.mutating()) return;
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = "copy";
    this.dropTarget.set(slot);
  }

  protected dragLeave(slot: number): void {
    if (this.dropTarget() === slot) this.dropTarget.set(null);
  }

  protected drop(event: DragEvent, slot: number): void {
    event.preventDefault();
    this.dropTarget.set(null);
    if (this.locked() || this.mutating()) return;
    const match = /^([AB])(\d+)$/.exec(event.dataTransfer?.getData("text/plain") ?? "");
    if (!match) return;
    const row = match[1] as Row;
    const index = Number(match[2]);
    if (index < 0 || index >= this.figure().parentA.length) return;
    this.place(slot, this.parentBit(row, index), `Parent ${row} bit ${index + 1}`);
  }

  // ---- mutation -------------------------------------------------------------------------------------

  protected toggleMutate(): void {
    if (this.locked()) return;
    this.mutating.update((on) => !on);
    this.picked.set(null);
    this.announce.set(this.mutating() ? "Mutation mode. Choose one child bit to flip." : "Mutation mode off.");
  }

  /** Flips a filled slot. Only one slot is flipped: flipping another moves the mutation, and the first flips back. */
  private flipSlot(slot: number): void {
    const before = this.child();
    const current = before.bits[slot];
    if (current === null) {
      this.announce.set(`Child slot ${slot + 1} is empty: fill it before flipping it.`);
      return;
    }
    const bits = [...before.bits];
    bits[slot] = flip(current);
    let flipped: number | null = slot;
    const earlier = before.flipped;
    if (earlier === slot) {
      flipped = null;
    } else if (earlier !== null && bits[earlier] !== null) {
      bits[earlier] = flip(bits[earlier] as Bit);
    }
    this.commit({ bits, flipped });
    this.animating.set(slot);
    setTimeout(() => this.animating.update((now) => (now === slot ? null : now)), 400);
    this.announce.set(
      `Child slot ${slot + 1} flipped to ${bits[slot]}.` +
        (earlier !== null && earlier !== slot ? ` The earlier flip of slot ${earlier + 1} is undone.` : flipped === null ? " The mutation is removed." : ""),
    );
  }

  // ---- undo and reset -------------------------------------------------------------------------------

  protected undo(): void {
    const history = this.history();
    if (this.locked() || history.length === 0) return;
    this.child.set(history[history.length - 1]);
    this.history.set(history.slice(0, -1));
    this.picked.set(null);
    this.announce.set("Undid the last action.");
  }

  protected reset(): void {
    if (this.locked()) return;
    this.child.set({ bits: Array<Bit | null>(this.child().bits.length).fill(null), flipped: null });
    this.history.set([]);
    this.picked.set(null);
    this.mutating.set(false);
    this.announce.set("The child is cleared.");
  }

  // ---- submit ---------------------------------------------------------------------------------------

  /** The existing answer call, with the same 6-bit string the text box took. A refused answer leaves the stage open. */
  protected async send(): Promise<void> {
    const answer = this.bits();
    if (answer === null) return;
    await this.submit({ answer });
    if (this.error() === null) {
      const team = this.teamId();
      sentinelSession.saveSubmitted(team, this.roomId(), this.stage().number, answer);
      sentinelSession.clearDraft(team, this.roomId(), this.stage().number);
    }
  }

  // ---- helpers --------------------------------------------------------------------------------------

  private parentBit(row: Row, index: number): Bit {
    return (row === "A" ? this.figure().parentA : this.figure().parentB)[index] as Bit;
  }

  /** Records an action for Undo and shows its result. */
  private commit(next: Child): void {
    this.history.update((all) => [...all, this.child()]);
    this.child.set(next);
  }

  private teamId(): number {
    return this.api.user()?.id ?? 0;
  }

  private save(child: Child): void {
    const text = child.bits.map((bit) => bit ?? "-").join("") + (child.flipped === null ? "" : `:${child.flipped}`);
    sentinelSession.saveDraft(this.teamId(), this.roomId(), this.stage().number, text);
  }

  /** The child kept in this tab, when it still fits this stage. */
  private restore(length: number): Child | null {
    const text = sentinelSession.draft(this.teamId(), this.roomId(), this.stage().number);
    const match = text ? /^([01-]+)(?::(\d+))?$/.exec(text) : null;
    if (!match || match[1].length !== length) return null;
    const bits = [...match[1]].map((c) => (c === "-" ? null : (c as Bit)));
    const flipped = match[2] === undefined ? null : Number(match[2]);
    if (flipped !== null && (flipped >= length || bits[flipped] === null)) return null;
    return { bits, flipped };
  }
}
