import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { EventApi } from "../../../../core/event/event-api";
import { AdminContent, SuspectView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import {
  addSuspectBlockReason,
  deleteSuspectBlockReason,
  suspectCount,
} from "./content-view";
import { SuspectForm } from "./suspect-form";

/** The six suspects (`data-suspect="<name>"` items): add, edit in place, delete with confirmation. */
@Component({
  selector: "app-suspects-section",
  imports: [
    ActionStatus,
    SuspectForm,
    UiButton,
    UiCard,
    UiConfirmDialog,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./suspects-section.html",
  styleUrl: "./suspects-section.css",
})
export class SuspectsSection {
  private readonly eventApi = inject(EventApi);

  readonly content = input.required<AdminContent>();
  readonly changed = output<void>();

  protected readonly state = new ActionState();
  protected readonly editingId = signal<number | null>(null);
  protected readonly deleting = signal<SuspectView | null>(null);

  protected readonly count = computed(() => suspectCount(this.content()));
  protected readonly addReason = computed(() =>
    addSuspectBlockReason(this.content()),
  );
  protected readonly rows = computed(() => {
    const content = this.content();
    return content.suspects.map((suspect) => ({
      suspect,
      correct: suspect.id === content.correctSuspectId,
      deleteReason: deleteSuspectBlockReason(suspect, content),
    }));
  });
  protected readonly deleteTitle = computed(() => {
    const suspect = this.deleting();
    return suspect ? `Delete ${suspect.name}?` : "";
  });

  protected edit(suspect: SuspectView): void {
    this.state.reset();
    this.editingId.set(suspect.id);
  }

  protected finishEditing(): void {
    this.editingId.set(null);
    this.changed.emit();
  }

  protected cancelDelete(): void {
    if (!this.state.busy()) this.deleting.set(null);
  }

  protected async confirmDelete(): Promise<void> {
    const suspect = this.deleting();
    if (!suspect) return;
    const deleted = await this.state.run(
      () => this.eventApi.deleteSuspect(suspect.id),
      `${suspect.name} was deleted.`,
    );
    this.deleting.set(null);
    if (deleted) this.changed.emit();
  }
}
