import { ChangeDetectionStrategy, Component, ElementRef, computed, signal, viewChild } from "@angular/core";
import { SentinelStage } from "../../../../core/event/event-models";
import { SentinelBrief } from "./sentinel-brief";
import { ChoiceData, dataOf } from "./sentinel-data";
import { SentinelFigure } from "./sentinel-figure";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** The letter on a card: A to D in the order this team sees the options (the answer sent is the option's id). */
export function badgeOf(index: number): string {
  return String.fromCharCode(65 + index);
}

/**
 * A multiple choice stage (stage 3): one answer only. The options are large cards in this team's own order (the same on
 * every refresh), each with a letter badge; the whole card is the button. The group is a radio group for the keyboard
 * and for screen readers: Tab enters it, the arrow keys move and choose, Space or Enter choose. The answer sent is the
 * option's id, never its position. Submitting asks "One answer only. Submit?" and locks the stage; like every stage it
 * then shows only "Saved", never whether the answer was right.
 */
@Component({
  selector: "sentinel-stage-choice",
  imports: [SentinelBrief, SentinelFigure, SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .cards {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--space-3);
      max-inline-size: 720px;
    }
    @media (max-width: 599px) {
      .cards {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .card {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      min-block-size: 72px;
      padding: var(--space-3) var(--space-4);
      text-align: start;
      cursor: pointer;
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
      color: var(--color-text);
    }
    .card:hover:not([aria-disabled="true"]) {
      background: var(--color-surface-subtle);
    }
    .card:focus-visible {
      outline: none;
      box-shadow: 0 0 0 4px var(--color-focus-ring);
    }
    .card[aria-checked="true"] {
      background: var(--room-tint);
      border: 2px solid var(--room-color);
      padding: calc(var(--space-3) - 1px) calc(var(--space-4) - 1px);
    }
    .card[aria-disabled="true"] {
      cursor: not-allowed;
      opacity: 0.6;
    }
    .card__badge {
      display: grid;
      flex: none;
      place-items: center;
      inline-size: 36px;
      block-size: 36px;
      font-weight: var(--font-weight-semibold);
      color: var(--room-ink);
      background: var(--room-tint);
      border: var(--border-outline-thin);
      border-radius: var(--radius-full);
    }
    .card[aria-checked="true"] .card__badge {
      color: var(--color-on-primary);
      background: var(--room-color);
      border-color: var(--room-color);
    }
    .card__text {
      flex: 1 1 auto;
      min-inline-size: 0;
      font-family: var(--font-mono);
      font-size: var(--font-size-lg);
      overflow-wrap: anywhere;
    }
    .card__check {
      flex: none;
      inline-size: 24px;
      block-size: 24px;
      fill: none;
      stroke: var(--room-ink);
      stroke-width: 3;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    fieldset {
      margin: 0;
      padding: 0;
      border: 0;
      min-inline-size: 0;
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="shellStage()"
      [ready]="selected() !== null"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      submitLabel="Submit answer"
      confirmTitle="One answer only. Submit?"
      [hint]="selected() === null ? 'Choose one answer.' : null"
      (confirmed)="send()"
    >
      <div class="stage">
        @if (data().figure; as figure) {
          <sentinel-figure [figure]="figure" />
        }
        <sentinel-brief data-choice-brief [brief]="stage().brief" />
        <fieldset>
          <legend class="event-sr-only">Choose one answer</legend>
          <div #group class="cards" role="radiogroup" aria-label="Choose one answer" data-choice-options>
            @for (option of data().options; track option.id; let i = $index) {
              <div
                class="card"
                role="radio"
                [attr.aria-checked]="selected() === option.id"
                [attr.aria-disabled]="readOnly()"
                [attr.tabindex]="tabbable() === option.id ? 0 : -1"
                [attr.data-option]="option.id"
                (click)="choose(option.id)"
                (keydown)="key($event, i)"
              >
                <span class="card__badge" aria-hidden="true">{{ badge(i) }}</span>
                <span class="card__text">{{ option.text }}</span>
                @if (selected() === option.id) {
                  <svg class="card__check" viewBox="0 0 24 24" aria-hidden="true" data-check><path d="M4 12.5l5 5L20 6.5" /></svg>
                }
              </div>
            }
          </div>
        </fieldset>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageChoice extends SentinelStageBase {
  private readonly group = viewChild.required<ElementRef<HTMLElement>>("group");

  protected readonly data = computed(() => dataOf<ChoiceData>(this.stage()));
  protected readonly selected = signal<string | null>(null);
  /** The one card the Tab key lands on: the chosen one, else the first. */
  protected readonly tabbable = computed(() => this.selected() ?? this.data().options[0]?.id ?? null);

  /** The shell shows the brief as one paragraph; this stage shows it line by line, so the shell gets none. */
  protected readonly shellStage = computed<SentinelStage>(() => ({ ...this.stage(), brief: null }));
  protected readonly badge = badgeOf;

  protected choose(id: string): void {
    if (!this.readOnly()) this.selected.set(id);
  }

  /** Space or Enter choose the card; the arrow keys move to the next or previous card, choose it and keep the focus. */
  protected key(event: KeyboardEvent, index: number): void {
    const options = this.data().options;
    let target = -1;
    switch (event.key) {
      case " ":
      case "Enter":
        event.preventDefault();
        this.choose(options[index].id);
        return;
      case "ArrowRight":
      case "ArrowDown":
        target = (index + 1) % options.length;
        break;
      case "ArrowLeft":
      case "ArrowUp":
        target = (index - 1 + options.length) % options.length;
        break;
      default:
        return;
    }
    event.preventDefault();
    if (this.readOnly()) return;
    this.choose(options[target].id);
    this.group().nativeElement.querySelectorAll<HTMLElement>("[role=radio]")[target]?.focus();
  }

  protected send(): void {
    const option = this.selected();
    if (option !== null) void this.submit({ option });
  }
}
