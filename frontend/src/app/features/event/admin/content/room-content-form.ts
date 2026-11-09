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
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import { RoomContentView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { fieldError } from "../shared/form-rules";
import { roomContentStatus } from "./content-view";
import { syncEditableForm } from "./editable-form";

type RoomField = "clueKey";

const FIELD_LABELS: Record<RoomField, string> = {
  clueKey: "Clue key",
};

/** Clue key of one room (`data-room="<CODE>"`), PUT /rooms/{id}/content. */
@Component({
  selector: "app-room-content-form",
  imports: [
    ActionStatus,
    ReactiveFormsModule,
    UiButton,
    UiCard,
    UiInput,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[attr.data-room]": "room().code" },
  templateUrl: "./room-content-form.html",
  styleUrl: "./room-content-form.css",
})
export class RoomContentForm {
  private readonly eventApi = inject(EventApi);

  readonly room = input.required<RoomContentView>();
  readonly readOnly = input.required<boolean>();
  readonly changed = output<void>();

  protected readonly labels = FIELD_LABELS;
  protected readonly state = new ActionState();
  protected readonly terminalState = new ActionState();
  protected readonly activityState = new ActionState();
  protected readonly form = new FormGroup({
    clueKey: new FormControl("", {
      nonNullable: true,
      validators: [Validators.maxLength(500)],
    }),
  });
  protected readonly status = computed(() => roomContentStatus(this.room()));
  protected readonly formLabel = computed(
    () => `${this.room().name} room content`,
  );
  private baseVersion = 0;

  constructor() {
    effect(() => {
      const room = this.room();
      const readOnly = this.readOnly();
      untracked(() => this.sync(room, readOnly));
    });
  }

  protected error(field: RoomField): string | null {
    return fieldError(this.form.controls[field], FIELD_LABELS[field]);
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.readOnly()) return;
    const body = { ...this.form.getRawValue(), version: this.baseVersion };
    const saved = await this.state.run(
      () => this.eventApi.saveRoomContent(this.room().roomId, body),
      "Saved.",
    );
    if (!saved) return;
    this.form.markAsPristine();
    this.changed.emit();
  }

  /** PUT /admin/rooms/{id}/terminal right away; a failed save puts the box back. */
  protected async setTerminal(event: Event): Promise<void> {
    const box = event.target as HTMLInputElement;
    const enabled = box.checked;
    const saved = await this.terminalState.run(
      () => this.eventApi.setTerminalEnabled(this.room().roomId, enabled),
      enabled ? "Terminal enabled." : "Terminal disabled.",
    );
    if (!saved) box.checked = !enabled;
    this.changed.emit();
  }

  /** PUT /admin/rooms/{id}/activity right away; a failed save puts the box back. */
  protected async setQuiz(event: Event): Promise<void> {
    const box = event.target as HTMLInputElement;
    const enabled = box.checked;
    const saved = await this.activityState.run(
      () => this.eventApi.setRoomActivity(this.room().roomId, enabled ? "AI_QUIZ" : null),
      enabled ? "AI mission enabled." : "AI mission disabled.",
    );
    if (!saved) box.checked = !enabled;
    this.changed.emit();
  }

  /** Same endpoint as the AI mission switch; a room has one activity, so this replaces any other. */
  protected async setLinuxTerminal(event: Event): Promise<void> {
    const box = event.target as HTMLInputElement;
    const enabled = box.checked;
    const saved = await this.activityState.run(
      () => this.eventApi.setRoomActivity(this.room().roomId, enabled ? "LINUX_TERMINAL" : null),
      enabled ? "Linux terminal activity enabled." : "Linux terminal activity disabled.",
    );
    if (!saved) box.checked = !enabled;
    this.changed.emit();
  }

  /** Same endpoint again: the Problem Solving contest, whose results the room's manager types in. */
  protected async setContestResults(event: Event): Promise<void> {
    const box = event.target as HTMLInputElement;
    const enabled = box.checked;
    const saved = await this.activityState.run(
      () => this.eventApi.setRoomActivity(this.room().roomId, enabled ? "CONTEST_RESULTS" : null),
      enabled ? "Contest results activity enabled." : "Contest results activity disabled.",
    );
    if (!saved) box.checked = !enabled;
    this.changed.emit();
  }

  /** "Reload" after a conflict: drop local edits and take the server's current room content. */
  protected discardEdits(): void {
    this.form.markAsPristine();
    this.state.reset();
    this.changed.emit();
  }

  private sync(room: RoomContentView, readOnly: boolean): void {
    const synced = syncEditableForm(this.form, readOnly, {
      clueKey: room.clueKey,
    });
    if (synced) this.baseVersion = room.version;
  }
}
