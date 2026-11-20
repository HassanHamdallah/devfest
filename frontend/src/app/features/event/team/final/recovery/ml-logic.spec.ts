import { describe, expect, it } from "vitest";
import { hundredths, metricsAt, movedOrder, stepLabel, twoDecimals } from "./ml-logic";

const ORDER = ["load", "fit_scaler", "split", "transform", "train", "evaluate"];
const RELABEL = { item: "fit_scaler", after: "split", label: "Fit Scaler on Training Data" };
const FIT = { id: "fit_scaler", label: "Fit Scaler on Full Dataset" };

describe("movedOrder", () => {
  it("swaps a step with its neighbour and leaves the original alone", () => {
    expect(movedOrder(ORDER, 1, 1)).toEqual(["load", "split", "fit_scaler", "transform", "train", "evaluate"]);
    expect(movedOrder(ORDER, 2, -1)).toEqual(["load", "split", "fit_scaler", "transform", "train", "evaluate"]);
    expect(ORDER[1]).toBe("fit_scaler");
  });

  it("does nothing at the edges or for an index that does not exist", () => {
    expect(movedOrder(ORDER, 0, -1)).toEqual(ORDER);
    expect(movedOrder(ORDER, 5, 1)).toEqual(ORDER);
    expect(movedOrder(ORDER, 9, 1)).toEqual(ORDER);
  });
});

describe("stepLabel", () => {
  it("relabels the scaler once it is after the split, and only then", () => {
    expect(stepLabel(FIT, ORDER, RELABEL)).toBe("Fit Scaler on Full Dataset");
    expect(stepLabel(FIT, movedOrder(ORDER, 1, 1), RELABEL)).toBe("Fit Scaler on Training Data");
    expect(stepLabel({ id: "split", label: "Split Train/Test" }, movedOrder(ORDER, 1, 1), RELABEL)).toBe("Split Train/Test");
    expect(stepLabel(FIT, movedOrder(ORDER, 1, 1), null)).toBe("Fit Scaler on Full Dataset");
  });
});

describe("metricsAt", () => {
  const metrics = [0.41, 0.42, 0.43].map((threshold) => ({ threshold, precision: 0.75, recall: 0.93, f1: 0.83, falseNegatives: 1 }));

  it("finds the step by whole hundredths", () => {
    expect(metricsAt(metrics, 0.42)?.threshold).toBe(0.42);
    expect(metricsAt(metrics, 0.4200000001)?.threshold).toBe(0.42);
    expect(metricsAt(metrics, 0.5)).toBeNull();
  });

  it("formats numbers the way the screen shows them", () => {
    expect(hundredths(0.29)).toBe(29);
    expect(twoDecimals(0.9)).toBe("0.90");
    expect(twoDecimals(0.84)).toBe("0.84");
  });
});
