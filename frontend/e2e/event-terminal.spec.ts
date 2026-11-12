import { test, expect, Locator, Page } from "@playwright/test";
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

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
const PUSHED = { timeout: 5000 };
// Cold boot in headless Edge: download (cached after the first run), WebAssembly compile and Linux boot.
const BOOT = { timeout: 90000 };
const LOCK_LOG = [
  "09:58:02 door=WEST action=OPEN user=guard2",
  "10:01:17 door=EAST action=OVERRIDE user=tech9",
  "10:03:40 door=WEST action=CLOSE user=guard2",
  "10:07:05 door=NORTH action=OPEN user=guard4",
].join("\n");
const OVERRIDE_LINE = "10:01:17 door=EAST action=OVERRIDE user=tech9";

/** Team 09–12 are group C: the Linux room in round 1. Team 01 is group A: the AI room. */
const LINUX_TEAM = 8;
const AI_TEAM = 0;

function terminal(page: Page) {
  return page.locator("event-linux-terminal section.linux-terminal");
}

function terminalRow(page: Page, text: string): Locator {
  return terminal(page).locator(".xterm-rows > div", { hasText: text });
}

/** Drags across one terminal row, the way a person selects an output line. */
async function selectRow(page: Page, row: Locator) {
  const box = await row.boundingBox();
  if (!box) throw new Error("The terminal row is not on screen.");
  await page.mouse.move(box.x + 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 2, box.y + box.height / 2, { steps: 8 });
  await page.mouse.up();
}

/** One device per team: the check borrows the browser's session instead of signing in again. */
async function teamFilesStatus(page: Page, roomId: number) {
  const session = await EventApiSession.fromPage(page);
  try {
    return await session.status(`/event/team/rooms/${roomId}/terminal-files`);
  } finally {
    await session.dispose();
  }
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("terminal: the Linux team boots Linux in the browser, greps the room's file and submits the line", async ({
  browser,
}) => {
  test.setTimeout(420000);
  const manager = await openRolePage(browser, "manager", WIDE);
  const linuxTeam = await openRolePage(browser, "linux team", WIDE);
  const aiTeam = await openRolePage(browser, "ai team", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    // An empty AI mission would block the timer; the AI team here only checks that it never gets the terminal.
    await control.setRoomActivity("AI", null);
    const linuxRoom = await control.roomId("LINUX");

    await test.step("the Linux manager adds logs/lock.log on the room board", async () => {
      const page = manager.page;
      await signIn(page, accounts.managers.LINUX, accounts.seedPassword);
      await page.goto(`/event/room/${linuxRoom}`);
      const card = page.locator("event-terminal-files-card");
      await expect(card.getByRole("heading", { name: "Terminal files", exact: true })).toBeVisible();
      await card.getByRole("button", { name: "Add file", exact: true }).click();
      await card.getByRole("textbox", { name: "Path", exact: true }).fill("logs/lock.log");
      await card.getByRole("textbox", { name: "Content", exact: true }).fill(LOCK_LOG);
      await card.getByRole("button", { name: "Save file", exact: true }).click();
      await expect(card.locator('[data-path="logs/lock.log"]')).toBeVisible();
      await expect(card.getByText("1 of 20 files", { exact: false })).toBeVisible();
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await page.setViewportSize(size);
        await card.scrollIntoViewIfNeeded();
        await saveScreenshot(page, path.join(shots, `manager-terminal-files-${label}.png`), false);
      }
      await expectFitsPhone(page);
      // The manager's part is done; its page would otherwise keep refetching while the team downloads the terminal.
      await page.context().close();
    });

    await control.setRound(1);
    await signIn(linuxTeam.page, accounts.members[LINUX_TEAM], accounts.seedPassword);
    await signIn(aiTeam.page, accounts.members[AI_TEAM], accounts.seedPassword);

    await test.step("on a phone the form stays as it is, with a note and a way to open the terminal", async () => {
      const page = linuxTeam.page;
      await page.setViewportSize(NARROW);
      await expect(page.getByText("The terminal works best on a laptop.", { exact: true })).toBeVisible();
      await expect(page.getByRole("button", { name: "Open the terminal anyway", exact: true })).toBeVisible();
      await expectFitsPhone(page);
      await saveScreenshot(page, path.join(shots, "team-terminal-phone-390.png"), false);
      await page.setViewportSize(WIDE);
    });

    await test.step("the AI team never gets the terminal and gets 404 for the Linux files", async () => {
      await expect(aiTeam.page.getByText("Current room: AI", { exact: false })).toBeVisible();
      await expect(aiTeam.page.getByRole("button", { name: "Start terminal", exact: true })).toHaveCount(0);
      expect(await teamFilesStatus(aiTeam.page, linuxRoom)).toBe(404);
      expect(await teamFilesStatus(linuxTeam.page, linuxRoom)).toBe(200);
    });

    const page = linuxTeam.page;
    await control.startTimer();
    await control.openRoom("LINUX");

    await test.step("Start terminal boots Linux with the room's file in ~/logs", async () => {
      await page.getByRole("button", { name: "Start terminal", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      await expect(terminal(page)).toContainText("Your files are in ~/logs. There is no internet in this terminal.");
      const status = await terminal(page).locator(".linux-terminal__status").textContent();
      test.info().annotations.push({ type: "boot", description: status?.trim() ?? "" });
    });

    await test.step("grep finds the OVERRIDE line", async () => {
      await terminal(page).locator(".xterm").click();
      await page.keyboard.type("grep OVERRIDE ~/logs/lock.log");
      await page.keyboard.press("Enter");
      await expect(terminalRow(page, OVERRIDE_LINE)).toBeVisible({ timeout: 20000 });
      await saveScreenshot(page, path.join(shots, "team-terminal-split-1440.png"), false);
      // A running terminal stays when the window narrows: it moves under the form at full width.
      await page.setViewportSize(NARROW);
      await terminal(page).scrollIntoViewIfNeeded();
      await expect(terminalRow(page, OVERRIDE_LINE)).toBeVisible();
      await expectFitsPhone(page);
      await saveScreenshot(page, path.join(shots, "team-terminal-split-390.png"), false);
      await page.setViewportSize(WIDE);
    });

    await test.step("the selected line becomes the technical output and the team submits as usual", async () => {
      await selectRow(page, terminalRow(page, OVERRIDE_LINE));
      await terminal(page).getByRole("button", { name: "Use selection as technical output", exact: true }).click();
      const output = page.getByRole("textbox", { name: "Technical output", exact: true });
      await expect(output).toHaveValue(OVERRIDE_LINE);
      await page.getByRole("textbox", { name: "Clue answer", exact: true }).fill(PRACTICE_KEYS.LINUX.toLowerCase());
      await page.getByRole("button", { name: "Submit answer", exact: true }).click();
      await expect(page.getByText("Correct", { exact: true }).first()).toBeVisible();
      await expect(page.getByRole("heading", { name: "Receipt", exact: true })).toBeVisible();
    });

    await test.step("when the admin ends the round the terminal is gone without any click", async () => {
      await control.endRound();
      // No click anywhere: the terminal, its "Time is up" overlay and the old "Close terminal" button are all removed.
      await expect(terminal(page)).toHaveCount(0, PUSHED);
      await expect(page.getByRole("button", { name: "Close terminal", exact: true })).toHaveCount(0);
      await expect(page.getByRole("button", { name: "Start terminal", exact: true })).toHaveCount(0);
      expect(await teamFilesStatus(page, linuxRoom)).toBe(404);
    });
  } finally {
    await adminApi.dispose();
    for (const role of [manager, linuxTeam, aiTeam]) await role.page.context().close().catch(() => {});
  }
  expect([...manager.problems, ...linuxTeam.problems, ...aiTeam.problems]).toEqual([]);
});
