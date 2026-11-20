import { describe, expect, it } from "vitest";
import { longestLine, tokenizeLine } from "./arduino-syntax";

const kinds = (line: string) => tokenizeLine(line).map((t) => `${t.kind}:${t.text}`);
const joined = (line: string) => tokenizeLine(line).map((t) => t.text).join("");

describe("tokenizeLine", () => {
  it("colours keywords, numbers and constants", () => {
    expect(kinds("const int doorPin = 2;")).toEqual(["keyword:const", "plain: ", "keyword:int", "plain: doorPin = ", "number:2", "plain:;"]);
    expect(kinds("pinMode(doorPin, INPUT);")).toEqual(["plain:pinMode(doorPin, ", "keyword:INPUT", "plain:);"]);
    expect(kinds("if (millis() - authStart > 5000) {")).toEqual(["keyword:if", "plain: (millis() - authStart > ", "number:5000", "plain:) {"]);
  });

  it("colours a comment to the end of the line, also after code", () => {
    expect(kinds("  // MISSING BEHAVIOR: if (x) 5")).toEqual(["plain:  ", "comment:// MISSING BEHAVIOR: if (x) 5"]);
    expect(kinds("x = 1; // one")).toEqual(["plain:x = ", "number:1", "plain:; ", "comment:// one"]);
  });

  it("colours strings and keeps a keyword inside one as text", () => {
    expect(kinds('Serial.println("ACCESS if 5");')).toEqual(["plain:Serial.println(", 'string:"ACCESS if 5"', "plain:);"]);
    expect(kinds('Serial.print("open')).toEqual(["plain:Serial.print(", 'string:"open']);
  });

  it("does not take a digit inside a word for a number, nor a word for a keyword", () => {
    expect(kinds("int value2 = 10;")).toEqual(["keyword:int", "plain: value2 = ", "number:10", "plain:;"]);
    expect(kinds("integer = 3.5")).toEqual(["plain:integer = ", "number:3.5"]);
    expect(kinds("a / b")).toEqual(["plain:a / b"]);
  });

  it("always joins back to the line, whatever it holds", () => {
    for (const line of ["", "   ", "if(doorOpen&&!authorized){", "\tx\t=\t'a' + \"b\\\"c\" // d", "α β ≥ 3", '"unterminated \\']) {
      expect(joined(line)).toBe(line);
    }
    expect(tokenizeLine("")).toEqual([]);
  });
});

describe("longestLine", () => {
  it("is the length of the longest line", () => {
    expect(longestLine(["ab", "abcde", ""])).toBe(5);
    expect(longestLine([])).toBe(0);
  });
});
