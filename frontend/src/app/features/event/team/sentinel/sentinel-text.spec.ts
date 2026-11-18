import { describe, expect, it } from "vitest";
import { formatFormula, parseBlocks } from "./sentinel-text";

const kinds = (source: string) => formatFormula(source).map((t) => `${t.kind}:${t.text}`);
const plain = (source: string) =>
  formatFormula(source)
    .map((t) => t.text)
    .join("");

describe("formatFormula", () => {
  it("italicises single-letter variables and puts digits in a subscript", () => {
    expect(kinds("z = w1")).toEqual(["var:z", "text: = ", "var:w", "sub:1"]);
  });

  it("keeps words and functions upright and splits log2 into a function and a subscript", () => {
    expect(kinds("ReLU(z) = max(0, z)")).toEqual([
      "fn:ReLU",
      "text:(",
      "var:z",
      "text:) = ",
      "fn:max",
      "text:(0, ",
      "var:z",
      "text:)",
    ]);
    expect(kinds("log2(0.25)")).toEqual(["fn:log", "sub:2", "text:(0.25)"]);
  });

  it("uses the real minus sign, also in front of a number", () => {
    expect(plain("log2(0.25) = -2")).toBe("log2(0.25) = −2");
    expect(plain("a - b")).toBe("a − b");
    expect(plain("a − b")).toBe("a − b");
  });

  it("turns <= and >= into ≤ and ≥", () => {
    expect(plain("a <= b")).toBe("a ≤ b");
    expect(plain("a >= b")).toBe("a ≥ b");
  });

  it("turns a lone x between two operands into a times sign, and leaves a variable x alone", () => {
    expect(plain("Q1 - k x IQR")).toBe("Q1 − k × IQR");
    expect(plain("Q3 + k x IQR")).toBe("Q3 + k × IQR");
    expect(plain("x + y")).toBe("x + y");
    expect(plain("w1·x1")).toBe("w1·x1");
    expect(formatFormula("k x IQR").filter((t) => t.kind === "var").map((t) => t.text)).toEqual(["k"]);
  });

  it("leaves numbers, Σ, · and ≈ as plain text and keeps every digit", () => {
    expect(plain("Entropy = -Σ p·log2(p)")).toBe("Entropy = −Σ p·log2(p)");
    expect(plain("log2(0.75) ≈ -0.415")).toBe("log2(0.75) ≈ −0.415");
    const digits = (t: string) => t.match(/\d+(?:\.\d+)?/g);
    expect(digits(plain("log2(0.75) ≈ -0.415"))).toEqual(digits("log2(0.75) ≈ -0.415"));
  });

  it("never turns the source into markup: angle brackets and quotes come back as plain text tokens", () => {
    const tokens = formatFormula('<script>alert("x")</script> & a');
    expect(tokens.every((t) => t.kind !== "text" || typeof t.text === "string")).toBe(true);
    expect(tokens.map((t) => t.text).join("")).toContain("<");
    expect(tokens.map((t) => t.text).join("")).toContain("&");
  });
});

describe("parseBlocks", () => {
  it("renders a line that starts with '$$ ' as a formula and the rest as text", () => {
    const blocks = parseBlocks("The neuron computes\n$$ z = w1·x1 + b\nthen outputs\n$$ ReLU(z) = max(0, z)\nWhat does it output?");
    expect(blocks.map((b) => b.kind)).toEqual(["text", "formula", "text", "formula", "text"]);
    const formula = blocks[1];
    expect(formula.kind === "formula" && formula.plain).toBe("z = w1·x1 + b");
  });

  it("does not treat '$$' without the space, or in the middle of a line, as a formula", () => {
    expect(parseBlocks("$$z\nprice is $$ 5").map((b) => b.kind)).toEqual(["text", "text"]);
  });

  it("groups consecutive four-space lines into one data block without the indent", () => {
    const blocks = parseBlocks("Sheet:\n    Parent A:  1 0 1 | 1 0 0\n    Parent B:  0 1 1 | 0 0 1\nDone");
    expect(blocks.map((b) => b.kind)).toEqual(["text", "data", "text"]);
    const data = blocks[1];
    expect(data.kind === "data" && data.text).toBe("Parent A:  1 0 1 | 1 0 0\nParent B:  0 1 1 | 0 0 1");
  });

  it("keeps deeper indentation inside a data block (a tree)", () => {
    const blocks = parseBlocks("    root\n        left\n        right");
    expect(blocks).toHaveLength(1);
    expect(blocks[0].kind === "data" && blocks[0].text).toBe("root\n    left\n    right");
  });

  it("a blank line adds space before the next block and is not a block itself", () => {
    const blocks = parseBlocks("one\n\ntwo");
    expect(blocks).toHaveLength(2);
    expect(blocks.map((b) => b.spaced)).toEqual([false, true]);
  });

  it("handles null, empty text and Windows line breaks", () => {
    expect(parseBlocks(null)).toEqual([]);
    expect(parseBlocks("")).toEqual([]);
    expect(parseBlocks("a\r\n$$ b\r\n").map((b) => b.kind)).toEqual(["text", "formula"]);
  });

  it("keeps every character of the text lines untouched", () => {
    const line = 'Use <b>"quotes"</b> & more';
    const blocks = parseBlocks(line);
    expect(blocks[0].kind === "text" && blocks[0].text).toBe(line);
  });
});
