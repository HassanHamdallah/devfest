import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { FinalMission } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";

const EMPHASIS = "NOOR DISAPPEARED.";
const COMPARE = ["Times", "Access permissions", "Statements", "System records", "Information across all four files"];

/**
 * The mission screen of "The Last Shipment": the content's title, the case briefing (paragraphs; the sentence about
 * Noor stands out), the two phases and what to compare. Start opens the case.
 */
@Component({
  selector: "event-final-mission",
  imports: [UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .mission {
      display: grid;
      gap: var(--space-4);
      padding: var(--space-6);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-card);
    }
    .mission__top {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .mission__clues {
      padding: var(--space-1) var(--space-3);
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.06em;
      color: var(--color-success);
      background: var(--color-success-subtle);
      border-radius: var(--radius-full);
    }
    .mission__brand {
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.06em;
      color: var(--color-primary-text);
    }
    .mission__head {
      display: grid;
      justify-items: center;
      gap: var(--space-2);
      text-align: center;
    }
    .mission__shield {
      inline-size: 40px;
      block-size: 40px;
      color: var(--color-success);
    }
    .mission__head h2 {
      margin: 0;
      font-size: var(--font-size-2xl);
      letter-spacing: 0.02em;
    }
    .mission__title {
      margin: 0;
      font-size: var(--font-size-xl);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.04em;
      color: var(--color-primary-text);
    }
    .mission__congrats {
      margin: 0;
      font-weight: var(--font-weight-bold);
    }
    .briefing {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-4);
      text-align: start;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-md);
    }
    .briefing h3 {
      margin: 0;
      font-size: var(--font-size-sm);
      letter-spacing: 0.06em;
      color: var(--color-primary-text);
    }
    .briefing p {
      max-inline-size: 70ch;
      margin: 0;
      line-height: 1.6;
    }
    .briefing strong {
      color: var(--color-error);
      letter-spacing: 0.02em;
    }
    .mission__compare {
      display: grid;
      gap: var(--space-2);
      margin: 0;
    }
    .mission__compare ul {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .mission__compare li {
      padding: 2px var(--space-3);
      font-size: var(--font-size-sm);
      background: var(--color-surface-subtle);
      border: var(--border-card);
      border-radius: var(--radius-full);
    }
    .mission__closing {
      margin: 0;
      font-weight: var(--font-weight-semibold);
    }
    .mission__tiles {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--space-3);
    }
    .mission__tile {
      display: grid;
      justify-items: center;
      gap: var(--space-1);
      padding: var(--space-4);
      text-align: center;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-md);
    }
    .mission__tile strong {
      font-family: var(--font-mono);
      font-size: var(--font-size-2xl);
    }
    .mission__tile span {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .mission__box {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-4);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    .mission__box h3 {
      margin: 0;
      font-size: var(--font-size-sm);
      letter-spacing: 0.06em;
    }
    .mission__box ol {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding-inline-start: var(--space-5);
    }
    @media (max-width: 480px) {
      .mission {
        padding: var(--space-4);
      }
      .mission__head h2 {
        font-size: var(--font-size-xl);
      }
    }
  `,
  template: `<section class="mission" data-final-screen="MISSION" aria-labelledby="mission-heading">
    <div class="mission__top">
      <span class="mission__clues" data-mission-clues>CLUES COLLECTED {{ cluesCollected() }}/4</span>
      <span class="mission__brand">NOVA TECHNOLOGIES · DEVFEST 2026</span>
    </div>
    <div class="mission__head">
      <svg class="mission__shield" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z" />
        <path d="m8.5 12 2.5 2.5 4.5-5" />
      </svg>
      <h2 id="mission-heading">MISSION UNLOCKED</h2>
      <p class="mission__title" data-mission-title>{{ mission().title }}</p>
      <p class="mission__congrats">Congratulations, Investigators!</p>
    </div>
    <div class="briefing" data-mission-intro>
      <h3>CASE BRIEFING</h3>
      @for (paragraph of paragraphs(); track $index) {
        <p>
          @for (part of paragraph; track $index) {
            @if (part.strong) {
              <strong>{{ part.text }}</strong>
            } @else {
              {{ part.text }}
            }
          }
        </p>
      }
    </div>
    <div class="mission__tiles">
      <div class="mission__tile"><strong>40:00</strong><span>Total challenge time</span></div>
      <div class="mission__tile"><strong>04</strong><span>Encrypted clues</span></div>
    </div>
    <div class="mission__box">
      <h3>YOUR MISSION</h3>
      <ol>
        <li data-mission-phase="1">Phase 1 — {{ mission().phase1 }}</li>
        <li data-mission-phase="2">Phase 2 — {{ mission().phase2 }}</li>
      </ol>
      <div class="mission__compare" data-mission-compare>
        <span>Compare:</span>
        <ul>
          @for (item of compare; track item) {
            <li>{{ item }}</li>
          }
        </ul>
      </div>
      <p class="mission__closing">Trust the evidence. Question your assumptions.</p>
      <p class="mission__closing">Good luck, investigators.</p>
    </div>
    @if (error(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    <ui-button size="lg" [fullWidth]="true" [loading]="sending()" (click)="start()">START FINAL INVESTIGATION</ui-button>
  </section>`,
})
export class EventFinalMission {
  private readonly eventApi = inject(EventApi);

  readonly mission = input.required<FinalMission>();
  readonly cluesCollected = input(4);
  readonly changed = output<void>();

  protected readonly compare = COMPARE;
  /** The intro's paragraphs (separated by blank lines), each cut so the sentence about Noor can be emphasised. */
  protected readonly paragraphs = computed(() =>
    this.mission()
      .intro.split(/\n{2,}/)
      .map((paragraph) => paragraph.trim())
      .filter((paragraph) => paragraph.length > 0)
      .map((paragraph) =>
        paragraph
          .split(EMPHASIS)
          .flatMap((text, i) => (i === 0 ? [{ text, strong: false }] : [{ text: EMPHASIS, strong: true }, { text, strong: false }]))
          .filter((part) => part.text.trim().length > 0),
      ),
  );
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async start(): Promise<void> {
    if (this.sending()) return;
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.eventApi.startCase();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.sending.set(false);
      this.changed.emit();
    }
  }
}
