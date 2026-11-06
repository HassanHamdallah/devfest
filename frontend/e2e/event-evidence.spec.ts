import { test, expect, Locator, Page } from "@playwright/test";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  EventTeamShortcuts,
  PRACTICE_KEYS,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { openRolePage, saveScreenshot, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
const MASK = "••••••••";
// Other screens follow an admin action through the pushed revision.
const PUSHED = { timeout: 5000 };

function evidenceCard(page: Page, room: string) {
  return page.locator(`[data-evidence="${room}"]`);
}

function clueValue(scope: Locator) {
  return scope.locator("[data-clue-value]");
}

async function submitInUi(page: Page, technicalOutput: string, clueAnswer: string, result: string) {
  await page.getByRole("textbox", { name: "Technical output", exact: true }).fill(technicalOutput);
  await page.getByRole("textbox", { name: "Clue answer", exact: true }).fill(clueAnswer);
  await page.getByRole("button", { name: "Submit answer", exact: true }).click();
  await expect(page.getByText(result, { exact: true }).first()).toBeVisible();
}

/** Screenshots at both widths, back at the wide viewport afterwards. */
async function shootBothWidths(page: Page, name: string, target: Locator) {
  for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
    await page.setViewportSize(size);
    await target.scrollIntoViewIfNeeded();
    await saveScreenshot(page, path.join(shots, `${name}-${label}.png`), false);
  }
  await page.setViewportSize(WIDE);
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("evidence: finished rooms show the answer and the masked clue; the final lists the collected clues", async ({
  browser,
}) => {
  const member = await openRolePage(browser, "member", WIDE);
  await member.page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const borrowed: EventApiSession[] = [];
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    // The evidence shows the technical output the team typed in the AI room's plain answer form.
    await control.setRoomActivity("AI", null);
    await control.startRound(1);
    await control.openRoom("AI");
    await signIn(member.page, accounts.members[0], accounts.seedPassword);
    const page = member.page;
    const ai = evidenceCard(page, "AI");

    await test.step("while the round runs there is no Evidence card", async () => {
      await submitInUi(page, "Validation accuracy 0.94 after tuning", "white van", "Correct");
      await expect(page.getByRole("heading", { name: "Evidence", exact: true })).toHaveCount(0);
      await expect(page.locator("[data-evidence]")).toHaveCount(0);
    });

    await test.step("the admin ends the round: the AI evidence appears with the clue masked", async () => {
      await control.endRound();
      await expect(ai).toBeVisible(PUSHED);
      await expect(ai.getByText("Validation accuracy 0.94 after tuning", { exact: true })).toBeVisible();
      await expect(ai.getByText("white van", { exact: true })).toBeVisible();
      await expect(ai.getByText("Correct", { exact: true })).toBeVisible();
      await expect(clueValue(ai)).toContainText(MASK);
      await expect(clueValue(ai)).not.toContainText(PRACTICE_KEYS.AI);
      await shootBothWidths(page, "team-evidence-masked", ai);
    });

    await test.step("Copy copies the clue without revealing it", async () => {
      await ai.getByRole("button", { name: "Copy", exact: true }).click();
      await expect(ai.getByRole("button", { name: "Copied", exact: true })).toBeVisible();
      expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(PRACTICE_KEYS.AI);
      await expect(clueValue(ai)).not.toContainText(PRACTICE_KEYS.AI);
    });

    await test.step("the eye shows the clue, and it hides itself again after 15 s", async () => {
      await ai.getByRole("button", { name: "Show clue", exact: true }).click();
      await expect(clueValue(ai)).toHaveText(PRACTICE_KEYS.AI);
      await expect(ai.getByRole("button", { name: "Hide clue", exact: true })).toBeVisible();
      await shootBothWidths(page, "team-evidence-revealed", ai);
      // The screenshots above took a few seconds of the 15; the clue must be masked again well within 17 s.
      await expect(clueValue(ai)).toContainText(MASK, { timeout: 17000 });
      await expect(ai.getByRole("button", { name: "Show clue", exact: true })).toBeVisible();
    });

    await test.step("an incorrect room shows Clue not collected", async () => {
      await control.startRound(2);
      await control.openRoom("PROBLEM_SOLVING");
      await expect(page.getByRole("textbox", { name: "Technical output", exact: true })).toBeVisible(PUSHED);
      await submitInUi(page, "Graph search in 12 ms", "BLUE CAR", "Incorrect — try again");
      await control.endRound();
      const solving = evidenceCard(page, "PROBLEM_SOLVING");
      await expect(solving).toBeVisible(PUSHED);
      await expect(solving.getByText("Incorrect", { exact: true })).toBeVisible();
      await expect(solving.getByText("Clue not collected", { exact: true })).toBeVisible();
      await expect(clueValue(solving)).toHaveCount(0);
    });

    await test.step("in the final, Your evidence lists the collected clues masked", async () => {
      // One device per team: borrow the browser's session instead of signing in again.
      const teamApi = await EventApiSession.fromPage(page);
      borrowed.push(teamApi);
      const team = new EventTeamShortcuts(teamApi);
      for (const visit of [{ round: 3, room: "LINUX" }, { round: 4, room: "HARDWARE" }] as const) {
        await control.startRound(visit.round);
        await control.openRoom(visit.room);
        const receipt = await team.submitClue(await control.roomId(visit.room), PRACTICE_KEYS[visit.room]);
        expect(receipt.clueCorrect, visit.room).toBe(true);
      }
      await control.setRound(5);
      await control.closeAllRooms();
      await control.openFinal();
      await page.getByRole("link", { name: "Open final", exact: true }).click();
      const panel = page.locator("event-final-evidence");
      await expect(page.getByRole("heading", { name: "Your evidence", exact: true })).toBeVisible();
      for (const room of ["AI", "LINUX", "HARDWARE"]) {
        const row = page.locator(`[data-final-evidence="${room}"]`);
        await expect(clueValue(row)).toContainText(MASK);
        await expect(clueValue(row)).not.toContainText(PRACTICE_KEYS[room as keyof typeof PRACTICE_KEYS]);
      }
      const missing = page.locator('[data-final-evidence="PROBLEM_SOLVING"]');
      await expect(missing.getByText("Clue not collected", { exact: true })).toBeVisible();
      await expect(clueValue(missing)).toHaveCount(0);
      await shootBothWidths(page, "final-evidence", panel);
    });
  } finally {
    await Promise.all([adminApi.dispose(), ...borrowed.map((session) => session.dispose())]);
    await member.page.context().close();
  }
  expect(member.problems).toEqual([]);
});
