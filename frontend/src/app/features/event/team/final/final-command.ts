import { ChangeDetectionStrategy, Component, computed, input, signal } from "@angular/core";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCopyButton } from "../../../../shared/ui/ui-copy-button/ui-copy-button";
import { SendResult, TerminalSender } from "./final-terminal";

const NOTES: Record<SendResult, string> = {
  sent: "Typed in the terminal. Check it, then press Enter there.",
  missing: "The terminal package is not ready yet. Ask the organizer.",
  closed: "Open the terminal first (the Terminal card), then send the command.",
  starting: "The terminal is still starting. Try again in a moment.",
};

/**
 * One command in a light box: long lines wrap (no sideways scroll), Copy, and "Send to terminal", which types it into
 * the running terminal without pressing Enter. `ready` false (a command still waiting for a choice) shows `waiting`
 * in place of the buttons.
 */
@Component({
  selector: "event-final-command",
  imports: [UiButton, UiCopyButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-1);
      min-inline-size: 0;
    }
    .command {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-2) var(--space-3);
      background: var(--color-surface-subtle);
      border: var(--border-card);
      border-radius: var(--radius-sm);
    }
    code {
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      line-height: 1.5;
      color: var(--color-text);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
    .waiting,
    .note {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .note--problem {
      color: var(--color-error);
    }
  `,
  template: `<div class="command" data-final-command>
      <code>{{ command() }}</code>
      <div class="actions">
        @if (ready()) {
          <ui-copy-button [text]="command()" />
          @if (send(); as sender) {
            <ui-button variant="secondary" data-send-to-terminal (click)="sendNow(sender)">Send to terminal</ui-button>
          }
        } @else {
          <span class="waiting">{{ waiting() }}</span>
        }
      </div>
    </div>
    @if (note(); as text) {
      <p class="note" [class.note--problem]="result() !== 'sent'" role="status" data-send-note>{{ text }}</p>
    }`,
})
export class EventFinalCommand {
  readonly command = input.required<string>();
  /** The command is complete and may be copied or sent. */
  readonly ready = input(true);
  readonly waiting = input("");
  /** Types into the terminal; none means the box only offers Copy (a command with placeholders). */
  readonly send = input<TerminalSender | null>(null);

  protected readonly result = signal<SendResult | null>(null);
  protected readonly note = computed(() => {
    const result = this.result();
    return result ? NOTES[result] : null;
  });

  protected sendNow(sender: TerminalSender): void {
    this.result.set(sender(this.command()));
  }
}
