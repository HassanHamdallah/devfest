import { ChangeDetectionStrategy, Component } from "@angular/core";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { EventFinalCommand } from "./final-command";

/** The generic steps of the participant PDF, placeholders kept literally: N, X and REPORT_NAME. */
export const COMMAND_REFERENCE: readonly { title: string; command: string }[] = [
  { title: "Open the case folder", command: "cd ~/DevFest-Crypto" },
  { title: "Make a folder for your work", command: "mkdir -p participant_test" },
  {
    title: "Recover the clue's passphrase with its RSA key",
    command:
      "openssl pkeyutl -decrypt -inkey participant_package/keys/key_X.pem " +
      "-in participant_package/clues/clueN/clueN_key.rsa -out participant_test/secret_N.txt " +
      "-pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256",
  },
  {
    title: "Decrypt the report with that passphrase",
    command:
      "openssl enc -d -aes-256-cbc -pbkdf2 -in participant_package/clues/clueN/REPORT_NAME.enc " +
      "-out participant_test/report_N.txt -pass file:participant_test/secret_N.txt",
  },
  { title: "Read the report", command: "cat participant_test/report_N.txt" },
];

/**
 * "Command reference": always visible below the clue cards. The same generic commands for every clue; the team types
 * them into the terminal itself, replacing the highlighted N, X and REPORT_NAME. Copy copies the generic text.
 */
@Component({
  selector: "event-final-command-reference",
  imports: [EventFinalCommand, UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    ol {
      display: grid;
      gap: var(--space-3);
      margin: 0;
      padding-inline-start: var(--space-5);
    }
    li {
      display: grid;
      gap: var(--space-1);
    }
    p {
      margin: 0 0 var(--space-3);
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    p.last {
      margin: var(--space-3) 0 0;
    }
    mark {
      padding: 0 2px;
      font-family: var(--font-mono);
      font-weight: var(--font-weight-bold);
      color: var(--color-primary-text);
      background: var(--color-surface-subtle);
      border: var(--border-outline-thin);
      border-radius: var(--radius-sm);
    }
  `,
  template: `<ui-card data-command-reference>
    <h2 ui-card-header>Command reference</h2>
    <p data-reference-note>
      Replace ALL <mark>N</mark> with the same clue number (1-4). Replace <mark>X</mark> with your key letter from the
      Key Index. Replace <mark>REPORT_NAME</mark> with the exact .enc file name (without .enc).
    </p>
    <ol>
      @for (step of steps; track step.title) {
        <li>
          <span>{{ step.title }}</span>
          <event-final-command data-reference-command [command]="step.command" />
        </li>
      }
    </ol>
    <p class="last" data-reference-prove>
      Prove a clue: run <code>cat participant_test/secret_N.txt</code> and paste it in the clue's box.
    </p>
  </ui-card>`,
})
export class EventFinalCommandReference {
  protected readonly steps = COMMAND_REFERENCE;
}
