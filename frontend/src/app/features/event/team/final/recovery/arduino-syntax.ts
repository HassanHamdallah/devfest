/**
 * Basic syntax colouring for the Arduino program, without a library. Pure: a line in, tokens out; the components only
 * print the token text through interpolation, so nothing is ever bound as HTML.
 *
 * Four colours: comments ("//" to the end of the line), strings, keywords (types, control words and the Arduino
 * constants HIGH, LOW, INPUT, OUTPUT) and numbers. Everything else is plain.
 */
export type SyntaxKind = "comment" | "string" | "keyword" | "number" | "plain";

export interface SyntaxToken {
  kind: SyntaxKind;
  text: string;
}

const KEYWORDS: ReadonlySet<string> = new Set([
  "const",
  "int",
  "long",
  "unsigned",
  "bool",
  "boolean",
  "byte",
  "char",
  "float",
  "double",
  "String",
  "void",
  "if",
  "else",
  "for",
  "while",
  "return",
  "true",
  "false",
  "HIGH",
  "LOW",
  "INPUT",
  "OUTPUT",
]);

/** One token at the start of {@code rest}: a string, a word, a number, or the next characters up to the next special one. */
const TOKEN = /^(?:"(?:[^"\\]|\\.)*"?|'(?:[^'\\]|\\.)*'?|[A-Za-z_]\w*|\d+(?:\.\d+)?|\s+|[^\sA-Za-z_\d"'/]+|\/)/;

/** The tokens of one line; adjacent plain tokens are merged, and the text always joins back to the line. */
export function tokenizeLine(line: string): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  const push = (kind: SyntaxKind, text: string) => {
    const last = tokens[tokens.length - 1];
    if (kind === "plain" && last?.kind === "plain") last.text += text;
    else tokens.push({ kind, text });
  };
  let rest = line;
  while (rest.length > 0) {
    if (rest.startsWith("//")) {
      push("comment", rest);
      break;
    }
    const match = TOKEN.exec(rest);
    // Every character is covered by the pattern, so this never stalls; the fallback only guards the loop.
    const text = match ? match[0] : rest[0];
    if (text.startsWith('"') || text.startsWith("'")) push("string", text);
    else if (/^[A-Za-z_]/.test(text)) push(KEYWORDS.has(text) ? "keyword" : "plain", text);
    else if (/^\d/.test(text)) push("number", text);
    else push("plain", text);
    rest = rest.slice(text.length);
  }
  return tokens;
}

/** The longest line, in characters (the editor's width follows it). */
export function longestLine(lines: readonly string[]): number {
  return lines.reduce((longest, line) => Math.max(longest, line.length), 0);
}
