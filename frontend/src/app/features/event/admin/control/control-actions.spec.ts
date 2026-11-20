import { describe, expect, it, vi } from "vitest";
import { EventApi } from "../../../../core/event/event-api";
import { ControlAction, confirmationFor, performControlAction, successNotice } from "./control-actions";

const NEXT: ControlAction = {
  kind: "setRound",
  round: 2,
  secondsLeft: null,
  next: { from: "Round 1 of 4", to: "Round 2 of 4" },
};

describe("the Next round confirmation", () => {
  it("asks before anything is sent, with the agreed texts and both round names", () => {
    const copy = confirmationFor(NEXT);
    expect(copy).not.toBeNull();
    expect(copy!.title).toBe("Move to the next round?");
    expect(copy!.message).toBe(
      "From Round 1 of 4 to Round 2 of 4. This ends the current round for every team and opens the next one. This cannot be undone.",
    );
    expect(copy!.confirmLabel).toBe("Move to next round");
    expect(copy!.variant).toBe("danger");
  });

  it("keeps the time-left warning in front when the round is running", () => {
    const copy = confirmationFor({ ...NEXT, secondsLeft: 754 } as ControlAction);
    expect(copy!.message.startsWith("The current round still has 12:34 left. From Round 1 of 4 to Round 2 of 4.")).toBe(true);
  });

  it("names the step into the finished rooms and out of 'Not started'", () => {
    const finish = confirmationFor({ kind: "setRound", round: 5, next: { from: "Round 4 of 4", to: "Rooms finished" } });
    expect(finish!.message).toContain("From Round 4 of 4 to Rooms finished.");
    const first = confirmationFor({ kind: "setRound", round: 1, next: { from: "Not started", to: "Round 1 of 4" } });
    expect(first!.message).toContain("From Not started to Round 1 of 4.");
  });

  it("leaves the 'Set round' form's own confirmation alone", () => {
    expect(confirmationFor({ kind: "setRound", round: 3 })!.title).toBe("Start round 3 of 4?");
    expect(confirmationFor({ kind: "setRound", round: 0 })!.title).toBe("Reset to not started?");
  });

  it("sends exactly the same request as before once confirmed: setRound(current + 1) and the same notice", async () => {
    const setRound = vi.fn().mockResolvedValue(undefined);
    const api = { setRound } as unknown as EventApi;
    await performControlAction(api, NEXT);
    expect(setRound).toHaveBeenCalledTimes(1);
    expect(setRound).toHaveBeenCalledWith(2);
    expect(successNotice(NEXT)).toBe("Round updated.");
  });

  it("asks nothing for the other round-timer buttons", () => {
    expect(confirmationFor({ kind: "pauseTimer" })).toBeNull();
    expect(confirmationFor({ kind: "resumeTimer" })).toBeNull();
  });
});
