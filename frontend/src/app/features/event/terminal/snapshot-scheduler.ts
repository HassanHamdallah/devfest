/** Saves 5 s after the last keystroke, but at most once every 15 s. */
export const SAVE_AFTER_IDLE_MS = 5_000;
export const SAVE_MIN_GAP_MS = 15_000;

/**
 * Decides when the terminal is saved (never how). Only terminal input marks the terminal as changed: the save runs
 * `SAVE_AFTER_IDLE_MS` after the last input, never sooner than `SAVE_MIN_GAP_MS` after the previous save, and an
 * idle, unchanged terminal is never saved. `flush()` (the page went to the background) saves a changed terminal at
 * once. One save runs at a time; input that arrives during a save schedules the next one. When a save fails, nothing
 * is saved again and `onFailure` is told once.
 */
export class SnapshotScheduler {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private dirty = false;
  private saving = false;
  private flushAfterSave = false;
  private stopped = false;
  private lastStart = Number.NEGATIVE_INFINITY;

  constructor(
    private readonly save: () => Promise<void>,
    private readonly onFailure: (error: unknown) => void,
    private readonly now: () => number = Date.now,
  ) {}

  /** The team typed into the terminal. */
  touch(): void {
    if (this.stopped) return;
    this.dirty = true;
    this.schedule();
  }

  /** Saves a changed terminal now (visibilitychange to hidden). */
  flush(): void {
    if (this.stopped || !this.dirty) return;
    if (this.saving) {
      this.flushAfterSave = true;
      return;
    }
    void this.run();
  }

  /** No more saves (restart, end, destroy). A save already running finishes. */
  stop(): void {
    this.stopped = true;
    this.clearTimer();
  }

  private schedule(): void {
    if (this.saving) return;
    this.clearTimer();
    const wait = Math.max(SAVE_AFTER_IDLE_MS, this.lastStart + SAVE_MIN_GAP_MS - this.now());
    this.timer = setTimeout(() => void this.run(), wait);
  }

  private async run(): Promise<void> {
    this.clearTimer();
    if (this.stopped || this.saving || !this.dirty) return;
    this.dirty = false;
    this.saving = true;
    this.lastStart = this.now();
    try {
      await this.save();
    } catch (error) {
      this.stopped = true;
      this.onFailure(error);
      return;
    } finally {
      this.saving = false;
    }
    if (this.stopped || !this.dirty) return;
    if (this.flushAfterSave) {
      this.flushAfterSave = false;
      void this.run();
    } else {
      this.schedule();
    }
  }

  private clearTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}
