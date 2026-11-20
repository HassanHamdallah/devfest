import { ChangeDetectionStrategy, Component, effect, inject, input, output } from "@angular/core";
import { EventApi } from "../../../../core/event/event-api";
import { TeamFinal, TeamRecovery } from "../../../../core/event/event-models";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../../shared/ui/ui-page-state/ui-page-state";
import { BoardLoader } from "../../manager/board-loader";
import { EventArduinoDebug } from "./recovery/arduino-debug";
import { EventArduinoReference } from "./recovery/arduino-reference";
import { EventMlAudit } from "./recovery/ml-audit";
import { EventRecoveryReference } from "./recovery/recovery-reference";
import { EventRecoveryStrip } from "./recovery/recovery-strip";

/**
 * The recovery screen (a team that held fewer than 4 room keys when the final opened): the path as a progress strip, the
 * level the server says is current, and a collapsible quick reference. The state always comes from the server, so a refresh
 * resumes exactly where the team was; the screen loads the recovery again whenever the page's final changes.
 */
@Component({
  selector: "event-final-recovery",
  imports: [EventArduinoDebug, EventArduinoReference, EventMlAudit, EventRecoveryReference, EventRecoveryStrip, UiCard, UiPageState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    .recovery {
      display: grid;
      gap: var(--space-4);
      min-inline-size: 0;
    }
    .token {
      margin: 0;
      font-weight: var(--font-weight-bold);
      color: var(--brand-green-ink);
    }
    code {
      font-family: var(--font-mono);
    }
  `,
  template: `<div data-final-screen="RECOVERY" class="recovery">
    <ui-page-state
      [loading]="loader.loading()"
      loadingLabel="Loading your recovery…"
      [skeletonCards]="1"
      [error]="loader.initialError()"
      (retry)="load()"
    />
    @if (recovery(); as view) {
      <event-recovery-strip [levels]="view.levels" [progress]="view.progress" [finished]="view.finished" />
      @if (view.currentLevel === "ML" && view.mlStage) {
        <event-ml-audit [recovery]="view" (changed)="changed.emit()" />
      } @else if (view.currentLevel === "ARDUINO") {
        @if (mlToken(view); as token) {
          <ui-card data-recovery-token="ML">
            <p class="token">{{ token.label }}: <code>{{ token.value }}</code></p>
          </ui-card>
        }
        @if (view.arduino; as program) {
          <event-arduino-debug [program]="program" [lastTests]="view.lastTests" [waitSeconds]="view.waitSeconds" (changed)="changed.emit()" />
        }
      }
    }
    @if (recovery()?.currentLevel === "ARDUINO") {
      <event-arduino-reference />
    } @else {
      <event-recovery-reference />
    }
  </div>`,
})
export class EventFinalRecovery {
  private readonly eventApi = inject(EventApi);

  /** The page's final; a new one (after a check, a revision push or a reload) loads the recovery again. */
  readonly final = input.required<TeamFinal>();
  /** A check was answered: the page reloads, which moves the team to its next stage or to the unlock screen. */
  readonly changed = output<void>();

  protected readonly loader = new BoardLoader<TeamRecovery>(() => this.eventApi.teamRecovery());
  protected readonly recovery = this.loader.data;
  protected readonly mlToken = (view: TeamRecovery) => {
    const ml = view.progress.find((p) => p.level === "ML");
    return ml?.token ? { label: ml.tokenLabel ?? "ML RECOVERY TOKEN", value: ml.token } : null;
  };

  constructor() {
    effect(() => {
      this.final();
      void this.loader.load();
    });
  }

  protected load(): Promise<void> {
    return this.loader.load();
  }
}
