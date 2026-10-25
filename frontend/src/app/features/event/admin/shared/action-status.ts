import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  untracked,
} from "@angular/core";
import { ToastService } from "../../../../shared/ui/toast/toast.service";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { ActionState } from "./action-state";

/**
 * Saving / saved / error line under a form or a group of buttons. A success notice also appears as a toast;
 * an error stays here (role="alert") until dismissed or replaced by the next attempt.
 */
@Component({
  selector: "app-action-status",
  imports: [UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="action-status__live" role="status">
      @if (state().busy()) {
        Saving…
      } @else if (state().notice(); as notice) {
        {{ notice }}
      }
    </p>
    @if (state().error(); as error) {
      <ui-alert variant="error" [dismissible]="true">
        {{ error }}
        @if (showReload()) {
          <div class="action-status__reload">
            <ui-button variant="secondary" size="sm" (click)="reload.emit()"
              >Reload</ui-button
            >
          </div>
        }
      </ui-alert>
    }`,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    .action-status__live {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .action-status__live:empty {
      display: none;
    }
    .action-status__reload {
      margin-top: var(--space-2);
    }
  `,
})
export class ActionStatus {
  readonly state = input.required<ActionState>();
  /** Offer "Reload" on a 409 (version conflicts, locked content). */
  readonly reloadable = input(false);
  /** The user chose to discard local edits and load the current server state. */
  readonly reload = output<void>();

  protected readonly showReload = computed(
    () => this.reloadable() && this.state().conflict(),
  );

  constructor() {
    const toasts = inject(ToastService);
    // Each run resets the notice first, so a repeated action ("+1 min added.") toasts again.
    effect(() => {
      const notice = this.state().notice();
      if (notice) untracked(() => toasts.show(notice));
    });
  }
}
