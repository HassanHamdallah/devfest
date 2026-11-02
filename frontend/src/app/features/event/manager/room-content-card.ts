import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
} from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import {
  RoomBoardRoom,
  RoomContentRequest,
} from "../../../core/event/event-models";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import { UiTextarea } from "../../../shared/ui/ui-textarea/ui-textarea";

type ContentField = "clueKey" | "recoveryPrompt" | "recoveryAnswer";

const toDraft = (room: RoomBoardRoom): RoomContentRequest => ({
  clueKey: room.clueKey,
  recoveryPrompt: room.recoveryPrompt,
  recoveryAnswer: room.recoveryAnswer,
  version: room.version,
});

/** The room's clue key and recovery challenge; editable until the final opens. */
@Component({
  selector: "event-room-content-card",
  imports: [FormsModule, UiAlert, UiButton, UiCard, UiInput, UiTextarea],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form,
    dl {
      display: grid;
      gap: var(--space-3);
      margin: 0;
    }
    dd {
      margin: 0;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    dt {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
  `,
  templateUrl: "./room-content-card.html",
})
export class EventRoomContentCard {
  private readonly eventApi = inject(EventApi);

  readonly room = input.required<RoomBoardRoom>();
  readonly changed = output<void>();

  private readonly dirty = signal(false);
  /** Follows the server while the manager has not typed; keeps their edits across background reloads. */
  protected readonly draft = linkedSignal<RoomBoardRoom, RoomContentRequest>({
    source: this.room,
    computation: (room, previous) =>
      previous && untracked(this.dirty) ? previous.value : toDraft(room),
  });
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

  protected setField(field: ContentField, value: string): void {
    this.draft.update((draft) => ({ ...draft, [field]: value }));
    this.dirty.set(true);
    this.saved.set(false);
  }

  protected async save(): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      const content = await this.eventApi.saveRoomContent(
        this.room().id,
        this.draft(),
      );
      this.draft.set({
        clueKey: content.clueKey,
        recoveryPrompt: content.recoveryPrompt,
        recoveryAnswer: content.recoveryAnswer,
        version: content.version,
      });
      this.dirty.set(false);
      this.saved.set(true);
    } catch (e) {
      this.handleSaveError(e);
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }

  /** On a version conflict the draft is dropped so the reload shows what the other person saved. */
  private handleSaveError(error: unknown): void {
    const conflict = error instanceof HttpErrorResponse && error.status === 409;
    if (conflict) this.dirty.set(false);
    this.error.set(message(error));
  }
}
