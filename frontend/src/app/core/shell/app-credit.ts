import { ChangeDetectionStrategy, Component } from "@angular/core";

/**
 * The developer credit. One component, one text, rendered at the bottom of each page layout (event shell,
 * participant shell, sign-in). In normal flow, so it never covers content, the terminal or the final's timer.
 */
@Component({
  selector: "app-credit",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `Built by Hassan Khaled`,
  styles: `
    :host {
      display: block;
      flex-shrink: 0;
      padding: var(--space-4) var(--space-4) var(--space-5);
      font-size: var(--font-size-s);
      line-height: var(--line-height-tight);
      text-align: center;
      color: var(--color-text-muted);
    }
  `,
})
export class AppCredit {}
