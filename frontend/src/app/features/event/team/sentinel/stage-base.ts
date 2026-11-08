import { HttpErrorResponse } from "@angular/common/http";
import { Directive, inject, input, output, signal } from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { SentinelStage } from "../../../../core/event/event-models";
import { newIdempotencyKey } from "../../../../core/event/pending-request";

/**
 * What every stage screen does with the server: experiments (stages 1, 3 and 10) and the one submission. Each
 * request keeps its Idempotency-Key until it succeeds, so pressing the button again after a lost response replays
 * the same request instead of making a new one.
 */
@Directive()
export abstract class SentinelStageBase {
  protected readonly eventApi = inject(EventApi);

  readonly stage = input.required<SentinelStage>();
  readonly roomId = input.required<number>();
  /** Paused, closed room or disqualified team: nothing can be sent. */
  readonly readOnly = input(false);
  /** The server accepted a run or the submission: the page fetches the activity again. */
  readonly changed = output<void>();

  protected readonly running = signal<string | null>(null);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly keys = new Map<string, string>();

  private keyFor(what: string): string {
    let key = this.keys.get(what);
    if (!key) {
      key = newIdempotencyKey();
      this.keys.set(what, key);
    }
    return key;
  }

  /** Runs a configuration; returns whether the server accepted it. */
  protected async run(config: string): Promise<boolean> {
    if (this.running() !== null || this.readOnly()) return false;
    this.running.set(config);
    this.error.set(null);
    try {
      await this.eventApi.runSentinelStage(this.roomId(), this.stage().number, config, this.keyFor(`run:${config}`));
      this.keys.delete(`run:${config}`);
      this.changed.emit();
      return true;
    } catch (e) {
      this.fail(e);
      return false;
    } finally {
      this.running.set(null);
    }
  }

  /** Submits and locks the stage with `answer`. */
  protected async submit(answer: object): Promise<void> {
    if (this.submitting() || this.readOnly()) return;
    const what = `submit:${JSON.stringify(answer)}`;
    this.submitting.set(true);
    this.error.set(null);
    try {
      await this.eventApi.submitSentinelStage(this.roomId(), this.stage().number, answer, this.keyFor(what));
      this.keys.delete(what);
      this.changed.emit();
    } catch (e) {
      this.fail(e);
    } finally {
      this.submitting.set(false);
    }
  }

  private fail(e: unknown): void {
    if (e instanceof HttpErrorResponse && e.status === 0) {
      this.error.set("The server did not answer. Nothing was lost on this screen; press the button again.");
      return;
    }
    this.error.set(message(e));
    // A refused request (stage saved, time up) shows the server's current state.
    if (e instanceof HttpErrorResponse && e.status >= 400 && e.status < 500) this.changed.emit();
  }
}
