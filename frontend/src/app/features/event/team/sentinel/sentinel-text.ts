/**
 * How SENTINEL shows the text of a brief or a field note. Pure functions, no DOM: the components only print what
 * these return through Angular interpolation, so the text is always escaped and nothing is ever bound as HTML.
 *
 * Three kinds of line:
 *  - a line that starts with "$$ " is a display formula (the rest of the line is its source);
 *  - lines that start with four spaces are data (a table, a bit pattern, a tree) and are grouped into one block;
 *  - everything else is a paragraph line. A blank line only adds a little space before the next block.
 */

/** One piece of a formula: an italic variable, an upright function or word, a subscript, or plain text. */
export interface FormulaToken {
  kind: "var" | "fn" | "sub" | "text";
  text: string;
}

export type TextBlock =
  | { kind: "text"; text: string; spaced: boolean }
  | { kind: "formula"; tokens: FormulaToken[]; plain: string; spaced: boolean }
  | { kind: "data"; text: string; spaced: boolean };

const FORMULA_PREFIX = "$$ ";
const DATA_INDENT = "    ";
const MINUS = "−";

/** Splits a text into its blocks. Pure. */
export function parseBlocks(text: string | null): TextBlock[] {
  const blocks: TextBlock[] = [];
  let spaced = false;
  let data: string[] = [];
  const flushData = () => {
    if (data.length > 0) {
      blocks.push({ kind: "data", text: data.join("\n"), spaced });
      spaced = false;
      data = [];
    }
  };
  for (const line of (text ?? "").replace(/\r\n?/g, "\n").split("\n")) {
    if (line.startsWith(DATA_INDENT) && line.trim() !== "") {
      data.push(line.slice(DATA_INDENT.length));
      continue;
    }
    flushData();
    if (line.trim() === "") {
      spaced = blocks.length > 0;
    } else if (line.startsWith(FORMULA_PREFIX)) {
      const tokens = formatFormula(line.slice(FORMULA_PREFIX.length));
      blocks.push({ kind: "formula", tokens, plain: tokens.map((t) => t.text).join(""), spaced });
      spaced = false;
    } else {
      blocks.push({ kind: "text", text: line, spaced });
      spaced = false;
    }
  }
  flushData();
  return blocks;
}

const WORD = /^[A-Za-z]+\d*/;
const NUMBER = /^\d+(?:\.\d+)?/;

/**
 * The source of one formula as tokens. The rules, all on the characters of the source:
 *  - "<=" and ">=" become ≤ and ≥; "-" becomes the minus sign; a lone "x" between two operands becomes ×;
 *  - one letter is a variable (italic), letters followed by digits put the digits in a subscript (w1, x2, log2);
 *  - a word of several letters (max, ReLU, Entropy, IQR) is a function or name, upright;
 *  - numbers and every other character (= + · Σ ≈ , ( ) |) stay as they are.
 */
export function formatFormula(source: string): FormulaToken[] {
  const tokens: FormulaToken[] = [];
  const push = (kind: FormulaToken["kind"], text: string) => {
    const last = tokens[tokens.length - 1];
    if (kind === "text" && last?.kind === "text") last.text += text;
    else tokens.push({ kind, text });
  };
  let rest = source.trim();
  // The last thing pushed that is an operand (a word, a number or a closing bracket), to tell a times sign from a variable x.
  let operandBefore = false;
  while (rest.length > 0) {
    let m: RegExpExecArray | null;
    if ((m = /^\s+/.exec(rest))) {
      push("text", " ");
      rest = rest.slice(m[0].length);
      continue;
    } else if (rest.startsWith("<=")) {
      push("text", "≤");
      rest = rest.slice(2);
      operandBefore = false;
      continue;
    } else if (rest.startsWith(">=")) {
      push("text", "≥");
      rest = rest.slice(2);
      operandBefore = false;
      continue;
    } else if ((m = WORD.exec(rest))) {
      const word = m[0];
      const letters = word.replace(/\d+$/, "");
      const digits = word.slice(letters.length);
      const next = rest.slice(word.length);
      if (word === "x" && operandBefore && /^\s+[A-Za-z0-9(]/.test(next) && /\s$/.test(sourceBefore(source, rest))) {
        push("text", "×");
      } else {
        push(letters.length === 1 ? "var" : "fn", letters);
        if (digits !== "") push("sub", digits);
      }
      rest = next;
      operandBefore = true;
      continue;
    } else if ((m = NUMBER.exec(rest))) {
      push("text", m[0]);
      rest = rest.slice(m[0].length);
      operandBefore = true;
      continue;
    } else if (rest.startsWith("-")) {
      push("text", MINUS);
      rest = rest.slice(1);
      operandBefore = false;
      continue;
    } else {
      const ch = [...rest][0];
      push("text", ch);
      rest = rest.slice(ch.length);
      operandBefore = ch === ")";
      continue;
    }
  }
  return tokens;
}

/** The part of the source before `rest` (what has already been read). */
function sourceBefore(source: string, rest: string): string {
  const trimmed = source.trim();
  return trimmed.slice(0, trimmed.length - rest.length);
}
