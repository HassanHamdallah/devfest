import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { UiCopyButton } from "../../../../shared/ui/ui-copy-button/ui-copy-button";

/** The placeholders of the generic commands: REPORT_NAME, the clue number N and the key letter X. */
const PLACEHOLDER = /REPORT_NAME|(?<=clue)N|(?<=_)[NX](?=[._])/g;

/** One piece of a command: plain text or a placeholder the team must replace. */
export interface CommandPart {
  readonly text: string;
  readonly placeholder: boolean;
}

/** Splits a command at its placeholders, which are kept literally. */
export function commandParts(command: string): readonly CommandPart[] {
  const parts: CommandPart[] = [];
  let from = 0;
  for (const match of command.matchAll(PLACEHOLDER)) {
    if (match.index > from) parts.push({ text: command.slice(from, match.index), placeholder: false });
    parts.push({ text: match[0], placeholder: true });
    from = match.index + match[0].length;
  }
  if (from < command.length) parts.push({ text: command.slice(from), placeholder: false });
  return parts;
}

/**
 * One generic command in a light box: long lines wrap (no sideways scroll), its placeholders are highlighted and a
 * Copy button copies the text exactly as shown. Nothing here sends anything to the terminal.
 */
@Component({
  selector: "event-final-command",
  imports: [UiCopyButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
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
    .placeholder {
      padding: 0 2px;
      font-weight: var(--font-weight-bold);
      color: var(--color-primary-text);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-sm);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
  `,
  template: `<div class="command" data-final-command>
    <code>@for (part of parts(); track $index) {<span [class.placeholder]="part.placeholder" [attr.data-placeholder]="part.placeholder ? '' : null">{{ part.text }}</span>}</code>
    <div class="actions"><ui-copy-button [text]="command()" /></div>
  </div>`,
})
export class EventFinalCommand {
  readonly command = input.required<string>();

  protected readonly parts = computed(() => commandParts(this.command()));
}
