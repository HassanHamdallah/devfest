import { describe, expect, it } from "vitest";
import { COMMAND_REFERENCE } from "./final-command-reference";
import { commandParts } from "./final-command";

const marked = (command: string) => commandParts(command).filter((part) => part.placeholder).map((part) => part.text);

describe("commandParts", () => {
  it("keeps the command text exactly and marks only N, X and REPORT_NAME", () => {
    for (const step of COMMAND_REFERENCE) {
      expect(commandParts(step.command).map((part) => part.text).join("")).toBe(step.command);
    }
  });

  it("marks every placeholder of the RSA command and nothing else", () => {
    const rsa = COMMAND_REFERENCE[2].command;
    expect(marked(rsa)).toEqual(["X", "N", "N", "N"]);
  });

  it("marks REPORT_NAME and the clue number of the AES command", () => {
    expect(marked(COMMAND_REFERENCE[3].command)).toEqual(["N", "REPORT_NAME", "N", "N"]);
  });

  it("marks the clue number of the read command and none in cd or mkdir", () => {
    expect(marked("cat participant_test/report_N.txt")).toEqual(["N"]);
    expect(marked("cd ~/DevFest-Crypto")).toEqual([]);
    expect(marked("mkdir -p participant_test")).toEqual([]);
  });
});
