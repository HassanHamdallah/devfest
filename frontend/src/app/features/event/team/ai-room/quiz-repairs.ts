import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { Api, message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { ActivityRepair, AiRoomActivity } from "../../../../core/event/event-models";
import { newIdempotencyKey } from "../../../../core/event/pending-request";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { moveItem, samePieces } from "./quiz-view";

/**
 * The code repairs: one tab per repair; its snippets are reordered by drag and drop or the ▲/▼ buttons, and
 * "Submit order" (asks first) locks the order; the last one records the team's result. An unsubmitted order is kept
 * in this browser tab (sessionStorage), so a reload keeps the team's work.
 */
@Component({
  selector: "event-quiz-repairs",
  imports: [UiAlert, UiButton, UiConfirmDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./quiz-repairs.css",
  templateUrl: "./quiz-repairs.html",
})
export class EventQuizRepairs {
  private readonly eventApi = inject(EventApi);
  private readonly userId = inject(Api).user()?.id ?? 0;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly activity = input.required<AiRoomActivity>();
  readonly readOnly = input.required<boolean>();
  /** A repair was submitted: the parent fetches the activity again. */
  readonly submitted = output<void>();

  private readonly repairKey = computed(() => this.activity().repairs.map((r) => r.id).join(","));
  /** The open tab: the first repair not submitted yet, kept across refreshes. */
  protected readonly selected = linkedSignal<string, number>({
    source: this.repairKey,
    computation: (_, previous) => {
      if (previous) return previous.value;
      const open = this.activity().repairs.findIndex((r) => !r.submitted);
      return open < 0 ? 0 : open;
    },
  });
  protected readonly repair = computed<ActivityRepair | null>(() => this.activity().repairs[this.selected()] ?? null);
  /** The team's working order (snippet ids) of the open repair. */
  protected readonly order = linkedSignal<ActivityRepair | null, number[]>({
    source: this.repair,
    computation: (repair) => (repair ? this.restore(repair) : []),
  });
  protected readonly snippets = computed(() => {
    const repair = this.repair();
    if (!repair) return [];
    const code = new Map(repair.snippets.map((s) => [s.id, s.code]));
    return this.order().map((id) => ({ id, code: code.get(id) ?? "" }));
  });
  protected readonly dragging = signal<number | null>(null);
  protected readonly confirming = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly keys = new Map<number, { order: string; key: string }>();

  protected choose(index: number): void {
    this.selected.set(index);
    this.error.set(null);
  }

  protected move(index: number, delta: number): void {
    const repair = this.repair();
    if (!repair || repair.submitted || this.readOnly()) return;
    const next = moveItem(this.order(), index, delta);
    this.setOrder(repair, next);
    const id = this.order()[Math.min(next.length - 1, Math.max(0, index + delta))];
    // Keep the keyboard on the moved line.
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>(`[data-snippet-id="${id}"] [data-move="${delta < 0 ? "up" : "down"}"]:not(:disabled)`)?.focus(),
      { injector: this.injector },
    );
  }

  protected dragStart(event: DragEvent, index: number): void {
    this.dragging.set(index);
    event.dataTransfer?.setData("text/plain", String(index));
    if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
  }

  protected dragOver(event: DragEvent): void {
    if (this.dragging() === null) return;
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
  }

  protected drop(event: DragEvent, index: number): void {
    event.preventDefault();
    const from = this.dragging();
    this.dragging.set(null);
    if (from === null || from === index) return;
    this.move(from, index - from);
  }

  protected async submit(): Promise<void> {
    const repair = this.repair();
    if (!repair || this.sending()) return;
    const order = this.order();
    const signature = order.join(",");
    const stored = this.keys.get(repair.id);
    const key = stored?.order === signature ? stored.key : newIdempotencyKey();
    this.keys.set(repair.id, { order: signature, key });
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.eventApi.submitQuizRepair(this.activity().roomId, repair.id, order, key);
      this.keys.delete(repair.id);
      this.forget(repair.id);
      this.submitted.emit();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 0
          ? "The server did not answer. Your order is kept; press Submit order again."
          : message(e),
      );
      if (e instanceof HttpErrorResponse && e.status >= 400 && e.status < 500) this.submitted.emit();
    } finally {
      this.sending.set(false);
      this.confirming.set(false);
    }
  }

  private setOrder(repair: ActivityRepair, ids: number[]): void {
    this.order.set(ids);
    try {
      sessionStorage.setItem(this.storageKey(repair.id), JSON.stringify(ids));
    } catch {
      // Storage may be blocked; the order still works until the page is left.
    }
  }

  /** A submitted repair shows the server's order; else this tab's saved order if it still fits. */
  private restore(repair: ActivityRepair): number[] {
    const server = repair.snippets.map((s) => s.id);
    if (repair.submitted) return server;
    try {
      const raw = sessionStorage.getItem(this.storageKey(repair.id));
      const stored: unknown = raw ? JSON.parse(raw) : null;
      return samePieces(stored, server) ? stored : server;
    } catch {
      return server;
    }
  }

  private forget(repairId: number): void {
    try {
      sessionStorage.removeItem(this.storageKey(repairId));
    } catch {
      // Nothing stored.
    }
  }

  private storageKey(repairId: number): string {
    return `devfest.quiz.order.${this.userId}.${this.activity().roomId}.${repairId}`;
  }
}
