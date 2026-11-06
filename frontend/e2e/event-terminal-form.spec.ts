import { test, expect, Page } from "@playwright/test";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  PRACTICE_KEYS,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { expectFitsPhone, openRolePage, saveScreenshot, signIn } from "./event-ui-helper";
import { buildLinuxRoomFixture } from "./linux-room-fixture";
import { expectFindChallenge, terminal } from "./linux-terminal-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
const BOOT = { timeout: 120000 };
const PUSHED = { timeout: 8000 };
/** Teams 09–12 are group C: the Linux room in round 1. */
const TEAM_ONE = 8;
const TEAM_TWO = 9;
const ALLOWED_PROPERTIES = [
  "encryptedMessage", "fileSize", "finalClue", "path", "pattern", "roomId", "savedAt", "status", "submittedAt", "username",
];

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

function field(page: Page, label: string) {
  return page.getByRole("textbox", { name: label, exact: true });
}

function chip(page: Page) {
  return page.locator("event-terminal-form ui-status-chip");
}

test("linux form: the team finds the file in the terminal, saves, refreshes, submits once and the clue is collected", async ({
  browser,
}) => {
  test.setTimeout(480000);
  const fixture = buildLinuxRoomFixture();
  const teamOne = await openRolePage(browser, "team one", WIDE);
  const teamTwo = await openRolePage(browser, "team two", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  const formResponses: unknown[] = [];
  try {
    await setPracticeKeys(accounts, ["LINUX"]);
    await control.setRoomActivity("AI", null);
    await control.setRoomActivity("LINUX", "LINUX_TERMINAL");
    const linuxRoom = await control.roomId("LINUX");
    await managerApi.upload(`/event/rooms/${linuxRoom}/terminal-bundle`, "rami-server.zip", fixture.zip);
    await control.setRound(1);
    await signIn(teamOne.page, accounts.members[TEAM_ONE], accounts.seedPassword);
    await signIn(teamTwo.page, accounts.members[TEAM_TWO], accounts.seedPassword);
    await control.startTimer();
    await control.openRoom("LINUX");
    const page = teamOne.page;
    page.on("response", async (response) => {
      if (response.url().includes("/terminal-form")) formResponses.push(await response.json().catch(() => null));
    });

    await test.step("the room shows the six-field form instead of the plain answer form", async () => {
      await expect(page.getByRole("heading", { name: "Your answers", exact: true })).toBeVisible();
      await expect(chip(page)).toHaveText("Not saved yet");
      for (const label of ["PATH", "USERNAME", "FILE_SIZE", "PATTERN", "Encrypted message", "Final clue"]) {
        await expect(field(page, label)).toBeVisible();
      }
      await expect(page.getByRole("textbox", { name: "Technical output", exact: true })).toHaveCount(0);
      await expect(page.getByRole("button", { name: "Submit answer", exact: true })).toHaveCount(0);
    });

    await test.step("the terminal installs the server; each parameter alone returns several files, all four exactly the target", async () => {
      await page.getByRole("button", { name: "Start terminal", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      await expect(terminal(page)).toContainText("Rami's server is mounted.");
      await expectFindChallenge(page, fixture);
      await terminal(page).locator(".xterm").click();
      await page.keyboard.type(`head -n 2 /${fixture.target.file}`);
      await page.keyboard.press("Enter");
      await expect(terminal(page)).toContainText(fixture.message.slice(0, 40));
      await expect(terminal(page)).toContainText(`Final clue: ${fixture.clue}`);
    });

    await test.step("there is no 'use selection' button, only a copy button", async () => {
      await expect(terminal(page).getByRole("button", { name: "Use selection as technical output", exact: true })).toHaveCount(0);
      await expect(terminal(page).getByRole("button", { name: "Copy selection", exact: true })).toBeVisible();
    });

    const answers = {
      PATH: fixture.target.path,
      USERNAME: fixture.target.user,
      FILE_SIZE: fixture.target.size,
      PATTERN: fixture.target.pattern,
      "Encrypted message": fixture.message.replace("Encrypted message: ", ""),
      "Final clue": PRACTICE_KEYS.LINUX.toLowerCase(),
    };

    await test.step("Save stores the values; a refresh and a terminal restart show them again", async () => {
      for (const [label, value] of Object.entries(answers)) await field(page, label).fill(value);
      await expect(page.getByText("Unsaved changes", { exact: true })).toBeVisible();
      await page.getByRole("button", { name: "Save", exact: true }).click();
      await expect(chip(page)).toHaveText("Saved");
      await expect(page.getByText("Unsaved changes", { exact: true })).toHaveCount(0);
      // A second save with a blank field is fine and repeatable.
      await field(page, "Encrypted message").fill("");
      await page.getByRole("button", { name: "Save", exact: true }).click();
      await field(page, "Encrypted message").fill(answers["Encrypted message"]);
      await page.getByRole("button", { name: "Save", exact: true }).click();
      await expect(page.getByText("Unsaved changes", { exact: true })).toHaveCount(0);

      await terminal(page).getByRole("button", { name: "Restart terminal", exact: true }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true }).click();
      await expect(terminal(page)).not.toHaveAttribute("data-state", "ready");
      await expect(field(page, "PATH")).toHaveValue(answers.PATH);

      await page.reload();
      await expect(chip(page)).toHaveText("Saved");
      for (const [label, value] of Object.entries(answers)) await expect(field(page, label)).toHaveValue(value);
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await page.setViewportSize(size);
        await page.locator("event-terminal-form").scrollIntoViewIfNeeded();
        await saveScreenshot(page, path.join(shots, `linux-form-${label}.png`), false);
      }
      await expectFitsPhone(page);
      await page.setViewportSize(WIDE);
    });

    await test.step("Final submit asks first, then locks the form for good", async () => {
      await page.getByRole("button", { name: "Final submit", exact: true }).click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toContainText("This cannot be undone");
      await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
      await expect(chip(page)).toHaveText("Saved");
      await page.getByRole("button", { name: "Final submit", exact: true }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Final submit", exact: true }).click();
      await expect(chip(page)).toHaveText("Submitted");
      await expect(page.getByText("Submitted. Your answers are locked.", { exact: true })).toBeVisible();
      await expect(field(page, "PATH")).toBeDisabled();
      await expect(page.getByRole("button", { name: "Save", exact: true })).toHaveCount(0);
      await page.reload();
      await expect(chip(page)).toHaveText("Submitted");
      await expect(field(page, "Final clue")).toHaveValue(answers["Final clue"]);
    });

    await test.step("the room counts as finished: the correct clue is collected and shown in the evidence", async () => {
      const session = await EventApiSession.fromPage(page);
      try {
        const board = await session.send<{
          cluesCollected: number;
          evidence: { roomCode: string; finished: boolean; clueValue: string | null; technicalOutput: string | null; clueCorrect: boolean | null }[];
        }>("GET", "/event/team/board");
        expect(board.cluesCollected).toBe(1);
        const linux = board.evidence.find((room) => room.roomCode === "LINUX");
        expect(linux).toMatchObject({ finished: true, clueValue: PRACTICE_KEYS.LINUX, technicalOutput: null, clueCorrect: null });
      } finally {
        await session.dispose();
      }
      await expect(page.locator("[data-form-outcome]")).toContainText("Submitted");
    });

    await test.step("a team that only saved is submitted by the round's end, once", async () => {
      const second = teamTwo.page;
      await expect(second.getByRole("heading", { name: "Your answers", exact: true })).toBeVisible();
      await field(second, "PATH").fill(fixture.target.path);
      await field(second, "Final clue").fill(PRACTICE_KEYS.LINUX);
      await second.getByRole("button", { name: "Save", exact: true }).click();
      await expect(chip(second)).toHaveText("Saved");
      await control.endRound();
      await expect(chip(second)).toHaveText("Submitted", PUSHED);
      await expect(field(second, "PATH")).toBeDisabled();
      const session = await EventApiSession.fromPage(second);
      try {
        const board = await session.send<{ cluesCollected: number }>("GET", "/event/team/board");
        expect(board.cluesCollected).toBe(1);
      } finally {
        await session.dispose();
      }
      await control.endRound();
    });

    await test.step("a team never receives anything but its own values and a status", async () => {
      // Every answered request of the form (a body is missing only when a reload cut its response off).
      const bodies = formResponses.filter((body): body is object => body !== null);
      expect(bodies.length).toBeGreaterThan(4);
      for (const body of bodies) expect(Object.keys(body).sort()).toEqual(ALLOWED_PROPERTIES);
    });
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    for (const role of [teamOne, teamTwo]) await role.page.context().close().catch(() => {});
  }
  expect([...teamOne.problems, ...teamTwo.problems].filter((problem) => !problem.includes("409"))).toEqual([]);
});
