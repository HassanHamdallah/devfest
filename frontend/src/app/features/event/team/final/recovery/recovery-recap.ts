import { ChangeDetectionStrategy, Component, inject, input, signal } from "@angular/core";
import { EventApi } from "../../../../../core/event/event-api";
import { RecoveredKey, TeamRecovery } from "../../../../../core/event/event-models";
import { UiAlert } from "../../../../../shared/ui/ui-alert/ui-alert";
import { EventRecoveryStrip } from "./recovery-strip";

/**
 * On the unlock screen of a team that recovered: its path (every recovery level done, Crypto current), each level's
 * token and the room keys its recovery granted. The keys are the team's own; the server sends them to nobody else.
 */
@Component({
  selector: "event-recovery-recap",
  imports: [EventRecoveryStrip, UiAlert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
    }
    .tokens {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-5);
      margin: 0;
      font-weight: var(--font-weight-bold);
      color: var(--brand-green-ink);
    }
    ul {
      margin: 0;
      padding-inline-start: var(--space-5);
    }
    code {
      font-family: var(--font-mono);
      overflow-wrap: anywhere;
    }
  `,
  template: `@if (recovery(); as view) {
      <event-recovery-strip [levels]="view.levels" [progress]="view.progress" [finished]="view.finished" />
      <p class="tokens" data-recovery-tokens>
        @for (level of view.progress; track level.level) {
          @if (level.token) {
            <span>{{ level.tokenLabel }}: <code>{{ level.token }}</code></span>
          }
        }
      </p>
    }
    <ui-alert variant="success" data-recovered-keys>
      <p>Your recovery is complete. These keys were added to your team:</p>
      <ul>
        @for (key of keys(); track key.roomName) {
          <li>{{ key.roomName }}: <code>{{ key.key }}</code></li>
        }
      </ul>
    </ui-alert>`,
})
export class EventRecoveryRecap {
  private readonly eventApi = inject(EventApi);

  readonly keys = input.required<readonly RecoveredKey[]>();
  protected readonly recovery = signal<TeamRecovery | null>(null);

  constructor() {
    // A recap that cannot load still shows the keys; the strip and tokens are extras.
    this.eventApi.teamRecovery().then(
      (view) => this.recovery.set(view),
      () => undefined,
    );
  }
}
