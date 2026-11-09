import { DestroyRef, signal } from "@angular/core";

/**
 * The "wait N seconds" after five wrong answers in a row. The server decides and enforces the wait; this only
 * counts it down on screen so the button comes back when the wait is over. Create it in an injection context.
 */
export class FinalWait {
  /** Whole seconds still to wait; 0 when the team may answer. */
  readonly left = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(destroyRef: DestroyRef) {
    destroyRef.onDestroy(() => this.stop());
  }

  set(seconds: number): void {
    this.stop();
    this.left.set(Math.max(0, Math.ceil(seconds)));
    if (this.left() > 0) {
      this.timer = setInterval(() => {
        this.left.update((value) => Math.max(0, value - 1));
        if (this.left() === 0) this.stop();
      }, 1000);
    }
  }

  private stop(): void {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
  }
}
