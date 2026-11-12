import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SAVE_AFTER_IDLE_MS, SAVE_MIN_GAP_MS, SnapshotScheduler } from "./snapshot-scheduler";

describe("SnapshotScheduler", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const make = (save: () => Promise<void> = async () => {}) => {
    const saves = vi.fn(save);
    const failures = vi.fn();
    return { saves, failures, scheduler: new SnapshotScheduler(saves, failures, () => Date.now()) };
  };

  it("never saves an idle terminal", async () => {
    const { saves } = make();
    await vi.advanceTimersByTimeAsync(10 * SAVE_MIN_GAP_MS);
    expect(saves).not.toHaveBeenCalled();
  });

  it("saves 5 seconds after the last keystroke, and keeps waiting while typing continues", async () => {
    const { saves, scheduler } = make();
    scheduler.touch();
    await vi.advanceTimersByTimeAsync(SAVE_AFTER_IDLE_MS - 1);
    scheduler.touch();
    await vi.advanceTimersByTimeAsync(SAVE_AFTER_IDLE_MS - 1);
    expect(saves).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(saves).toHaveBeenCalledTimes(1);
  });

  it("saves at most once every 15 seconds, and only again after new input", async () => {
    const { saves, scheduler } = make();
    scheduler.touch();
    await vi.advanceTimersByTimeAsync(SAVE_AFTER_IDLE_MS);
    expect(saves).toHaveBeenCalledTimes(1);
    scheduler.touch();
    await vi.advanceTimersByTimeAsync(SAVE_AFTER_IDLE_MS);
    expect(saves).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(SAVE_MIN_GAP_MS - SAVE_AFTER_IDLE_MS);
    expect(saves).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(10 * SAVE_MIN_GAP_MS);
    expect(saves).toHaveBeenCalledTimes(2);
  });

  it("saves a changed terminal at once when the page goes to the background, and an unchanged one never", async () => {
    const { saves, scheduler } = make();
    scheduler.flush();
    expect(saves).not.toHaveBeenCalled();
    scheduler.touch();
    scheduler.flush();
    expect(saves).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(10 * SAVE_MIN_GAP_MS);
    expect(saves).toHaveBeenCalledTimes(1);
  });

  it("input during a save is saved afterwards, never in parallel", async () => {
    let release: () => void = () => {};
    let running = 0;
    let overlap = false;
    const { saves, scheduler } = make(
      () =>
        new Promise<void>((resolve) => {
          if (++running > 1) overlap = true;
          release = () => {
            running--;
            resolve();
          };
        }),
    );
    scheduler.touch();
    await vi.advanceTimersByTimeAsync(SAVE_AFTER_IDLE_MS);
    scheduler.touch();
    await vi.advanceTimersByTimeAsync(SAVE_MIN_GAP_MS);
    expect(saves).toHaveBeenCalledTimes(1);
    release();
    await vi.advanceTimersByTimeAsync(SAVE_MIN_GAP_MS);
    expect(saves).toHaveBeenCalledTimes(2);
    release();
    expect(overlap).toBe(false);
  });

  it("a failing save stops all saving and reports once", async () => {
    const { saves, failures, scheduler } = make(async () => {
      throw new Error("quota");
    });
    scheduler.touch();
    await vi.advanceTimersByTimeAsync(SAVE_AFTER_IDLE_MS);
    expect(failures).toHaveBeenCalledTimes(1);
    scheduler.touch();
    scheduler.flush();
    await vi.advanceTimersByTimeAsync(10 * SAVE_MIN_GAP_MS);
    expect(saves).toHaveBeenCalledTimes(1);
    expect(failures).toHaveBeenCalledTimes(1);
  });

  it("stop cancels a save that is waiting", async () => {
    const { saves, scheduler } = make();
    scheduler.touch();
    scheduler.stop();
    await vi.advanceTimersByTimeAsync(10 * SAVE_MIN_GAP_MS);
    expect(saves).not.toHaveBeenCalled();
  });
});
