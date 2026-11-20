import { describe, expect, it } from "vitest";
import { MAX_LINE_LENGTH, isOriginal, parseDraft } from "./arduino-draft";

const LETTERS = ["A", "B", "C", "D"];
const store = (value: unknown) => JSON.stringify(value);

describe("parseDraft", () => {
  it("accepts a draft that fits the program", () => {
    expect(parseDraft(store({ lines: ["a", "", "c"], option: "B" }), 3, LETTERS)).toEqual({ lines: ["a", "", "c"], option: "B" });
    expect(parseDraft(store({ lines: ["a"], option: null }), 1, LETTERS)).toEqual({ lines: ["a"], option: null });
  });

  it("refuses nothing stored, unreadable text and the wrong shape", () => {
    expect(parseDraft(null, 1, LETTERS)).toBeNull();
    expect(parseDraft("{not json", 1, LETTERS)).toBeNull();
    expect(parseDraft("42", 1, LETTERS)).toBeNull();
    expect(parseDraft("null", 1, LETTERS)).toBeNull();
    expect(parseDraft(store({ option: null }), 1, LETTERS)).toBeNull();
    expect(parseDraft(store({ lines: "abc", option: null }), 3, LETTERS)).toBeNull();
  });

  it("refuses a draft of another program: a different number of lines, a bad line or an unknown option", () => {
    expect(parseDraft(store({ lines: ["a", "b"], option: null }), 3, LETTERS)).toBeNull();
    expect(parseDraft(store({ lines: ["a", 5], option: null }), 2, LETTERS)).toBeNull();
    expect(parseDraft(store({ lines: ["a\nb"], option: null }), 1, LETTERS)).toBeNull();
    expect(parseDraft(store({ lines: ["x".repeat(MAX_LINE_LENGTH + 1)], option: null }), 1, LETTERS)).toBeNull();
    expect(parseDraft(store({ lines: ["a"], option: "Z" }), 1, LETTERS)).toBeNull();
    expect(parseDraft(store({ lines: ["a"], option: 3 }), 1, LETTERS)).toBeNull();
    expect(parseDraft(store({ lines: ["a"] }), 1, LETTERS)).toBeNull();
  });
});

describe("isOriginal", () => {
  it("compares line by line", () => {
    expect(isOriginal(["a", "b"], ["a", "b"])).toBe(true);
    expect(isOriginal(["a", "c"], ["a", "b"])).toBe(false);
    expect(isOriginal(["a"], ["a", "b"])).toBe(false);
  });
});
