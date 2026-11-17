import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal, viewChild } from "@angular/core";
import { Api } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { TerminalBundleInfo, TerminalStartFiles } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { EventLinuxTerminal } from "../../terminal/linux-terminal";
import { FINAL_SNAPSHOT_PLACE, SnapshotScope } from "../../terminal/terminal-snapshot";

/** An 80-column terminal next to the files needs a laptop-sized window. */
const WIDE_QUERY = "(min-width: 1000px)";
/** The final is not a room round; its saved terminal is filed under this round number with the place "final". */
const FINAL_ROUND = 5;
/** What typing a command into the terminal came to: "sent", or why it could not be. */
export type SendResult = "sent" | "missing" | "closed" | "starting";
/** Types a command into the running terminal (no Enter). */
export type TerminalSender = (text: string) => SendResult;

const BANNER = "The Last Shipment: the case files are in ~/DevFest-Crypto. There is no internet in this terminal.";

/**
 * The final's in-browser terminal: the same Linux terminal as the rooms, started from the final's own package (no
 * text files). Its saved copy is filed under the place "final", never a room id, so a room terminal is never restored
 * here and this one never in a room. Phones get "works best on a laptop" and can open it anyway.
 */
@Component({
  selector: "event-final-terminal",
  imports: [EventLinuxTerminal, UiButton, UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    p {
      margin: 0 0 var(--space-3);
      color: var(--color-text-muted);
    }
    p:last-child {
      margin-block-end: 0;
    }
  `,
  template: `<ui-card data-final-terminal>
    <h2 ui-card-header>Terminal</h2>
    @if (!bundle()) {
      <p data-final-terminal-missing>The terminal package is not ready yet. Ask the organizer.</p>
    } @else if (!opened()) {
      <p>The terminal works best on a laptop.</p>
      <ui-button variant="secondary" (click)="opened.set(true)">Open the terminal anyway</ui-button>
    } @else {
      @defer (on immediate) {
        <event-linux-terminal
          [loadFiles]="loadFiles"
          [loadBundle]="loadBundle"
          [ended]="ended()"
          [snapshotScope]="snapshotScope()"
          [packageBanner]="banner"
          [seedEntropy]="true"
        />
      } @loading (minimum 300ms) {
        <p>Loading the terminal…</p>
      } @error {
        <p>The terminal could not be loaded. Reload the page and try again.</p>
      }
    }
  </ui-card>`,
})
export class EventFinalTerminal {
  private readonly eventApi = inject(EventApi);
  private readonly api = inject(Api);

  /** The final's package as the case view reports it; null until the organizer uploads one. */
  readonly bundle = input<TerminalBundleInfo | null>(null);
  /** The final's time is over (or the final is paused for good): the terminal stops. */
  readonly ended = input(false);

  private readonly linux = viewChild(EventLinuxTerminal);

  protected readonly banner = BANNER;
  /**
   * The terminal is shown: at once on a laptop, after "Open anyway" on a phone. Once shown it stays, even when the
   * window becomes narrow, so a resize never throws the team's running Linux away.
   */
  protected readonly opened = signal(matchMedia(WIDE_QUERY).matches);
  protected readonly snapshotScope = computed<SnapshotScope | null>(() => {
    const teamId = this.api.user()?.id;
    return teamId === undefined ? null : { teamId, roomId: FINAL_SNAPSHOT_PLACE, round: FINAL_ROUND };
  });
  /** Every (re)start asks the server again, so a replaced package is picked up. */
  protected readonly loadFiles = async (): Promise<TerminalStartFiles> => {
    const view = await this.eventApi.teamFinal();
    return { files: [], bundle: view.terminal };
  };
  protected readonly loadBundle = () => this.eventApi.finalTerminalBundle();

  /** Types a command into the running terminal for the team to review and run; says why when it cannot. */
  readonly send: TerminalSender = (text) => {
    if (!this.bundle()) return "missing";
    if (!this.opened()) return "closed";
    return this.linux()?.type(text) ? "sent" : "starting";
  };

  constructor() {
    const media = matchMedia(WIDE_QUERY);
    const onMedia = (event: MediaQueryListEvent) => {
      if (event.matches) this.opened.set(true);
    };
    media.addEventListener("change", onMedia);
    inject(DestroyRef).onDestroy(() => {
      media.removeEventListener("change", onMedia);
      // Leaving the case frees the downloaded Linux image; the terminal destroys its own VM.
      if (this.bundle() && this.opened()) {
        void import("../../terminal/terminal-assets").then((module) => module.releaseTerminal());
      }
    });
  }
}
