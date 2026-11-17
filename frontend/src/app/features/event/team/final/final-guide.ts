import { ChangeDetectionStrategy, Component } from "@angular/core";
import { EventFinalCommand } from "./final-command";

/** The generic OpenSSL commands (N is the clue number, X the key letter), for reading about it; the clue cards fill them in. */
export const GUIDE_STEPS: readonly { title: string; command: string }[] = [
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

/** "How it works": the general commands, each in its own copyable box. */
@Component({
  selector: "event-final-guide",
  imports: [EventFinalCommand],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
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
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    code {
      font-family: var(--font-mono);
      color: var(--color-text);
    }
  `,
  template: `<p>
      Every clue works the same way. Replace <code>N</code> with the clue number, <code>X</code> with the key letter
      from the Key Index and <code>REPORT_NAME</code> with the .enc file name without .enc. The clue cards below fill
      these in for you.
    </p>
    <ol>
      @for (step of steps; track step.title) {
        <li>
          <span>{{ step.title }}</span>
          <event-final-command data-guide-command [command]="step.command" />
        </li>
      }
    </ol>`,
})
export class EventFinalGuide {
  protected readonly steps = GUIDE_STEPS;
}
