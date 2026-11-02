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
import { FormControl, ReactiveFormsModule } from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import {
  Organization,
  OrganizationRoom,
} from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { NO_SELECTION, managerOptions } from "./teams-view";

/** Manager select of one room (`data-room="<CODE>"`). A manager holds at most one room. */
@Component({
  selector: "app-manager-assignment",
  imports: [ActionStatus, ReactiveFormsModule, UiButton, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[attr.data-room]": "room().code" },
  template: `<form [attr.aria-label]="formLabel()" (submit)="save($event)">
      <ui-select
        [label]="selectLabel()"
        [options]="options()"
        [formControl]="manager"
      />
      <ui-button type="submit" variant="secondary" [loading]="state.busy()"
        >Save manager</ui-button
      >
    </form>
    <app-action-status [state]="state" />`,
  styles: `
    :host {
      display: grid;
      align-content: start;
      gap: var(--space-2);
      padding: var(--space-4);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
    }
    form {
      display: grid;
      justify-items: start;
      gap: var(--space-2);
    }
    ui-select {
      justify-self: stretch;
    }
  `,
})
export class ManagerAssignment {
  private readonly eventApi = inject(EventApi);

  readonly room = input.required<OrganizationRoom>();
  readonly organization = input.required<Organization>();
  readonly changed = output<void>();

  protected readonly state = new ActionState();
  protected readonly manager = new FormControl(NO_SELECTION, {
    nonNullable: true,
  });
  protected readonly selectLabel = computed(() => `${this.room().name} room`);
  protected readonly formLabel = computed(
    () => `${this.room().name} room manager`,
  );
  protected readonly options = computed(() =>
    managerOptions(this.organization(), this.room().id),
  );

  constructor() {
    effect(() => {
      const managerId = this.room().managerId;
      untracked(() => {
        if (!this.manager.dirty)
          this.manager.setValue(
            managerId === null ? NO_SELECTION : String(managerId),
          );
      });
    });
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const value = this.manager.value;
    const managerId = value === NO_SELECTION ? null : Number(value);
    const saved = await this.state.run(
      () => this.eventApi.assignRoomManager(this.room().id, managerId),
      managerId === null ? "Manager removed." : "Manager assigned.",
    );
    if (saved) this.manager.markAsPristine();
    this.changed.emit();
  }
}
