import { test, expect, Page, Locator } from "@playwright/test";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
} from "./event-api-helper";
import { DESKTOP, PHONE, openRolePage, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
// Round 1 sends group C (seeded Team 09–12) to the Linux room.
const TYPO_KEY = "WEST TOWR";
const FIXED_KEY = "WEST TOWER";
// Teams refresh when the pushed revision changes (event-live.spec.ts measures the latency); generous timeout.
const NEXT_POLL = { timeout: 25000 };

function heading(page: Page, name: string) {
  return page.getByRole("heading", { level: 1, name, exact: true });
}

function button(scope: Page | Locator, name: string) {
  return scope.getByRole("button", { name, exact: true });
}

function textbox(scope: Page | Locator, name: string) {
  return scope.getByRole("textbox", { name, exact: true });
}

async function saveKey(page: Page, key: string, editLabel: string, notice: string) {
  const card = page.locator("[data-clue-key-card]");
  await button(card, editLabel).click();
  await textbox(card, "Clue key").fill(key);
  await button(card, "Save key").click();
  await expect(card.getByText(notice, { exact: true })).toBeVisible();
}

async function submitAnswer(page: Page, clueAnswer: string) {
  await textbox(page, "Technical output").fill("ls -la shows the hidden file");
  await textbox(page, "Clue answer").fill(clueAnswer);
  await button(page, "Submit answer").click();
  await expect(page.getByText("Receipt").first()).toBeVisible();
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("clues: a typo in the key is fixed and re-checked; a near miss is accepted", async ({ browser }) => {
  const manager = await openRolePage(browser, "manager", DESKTOP);
  const fixedTeam = await openRolePage(browser, "Team 09", PHONE);
  const nearMissTeam = await openRolePage(browser, "Team 10", PHONE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  try {
    await test.step("the Linux manager saves a key with a typo", async () => {
      await signIn(manager.page, accounts.managers.LINUX, accounts.seedPassword);
      await expect(heading(manager.page, "Linux")).toBeVisible();
      await saveKey(manager.page, TYPO_KEY, "Set clue key", "Saved. Re-checked 0 answers.");
      const control = new EventAdminShortcuts(adminApi);
      // The empty AI quiz (no key, no questions) would block the timer; this spec is about clue keys only.
      await control.setRoomActivity("AI", null);
      await control.startRound(1);
      await control.openRoom("LINUX");
    });
    await test.step("the right answer is marked Incorrect against the typo", async () => {
      await signIn(fixedTeam.page, accounts.members[8], accounts.seedPassword);
      await expect(heading(fixedTeam.page, "Team 09")).toBeVisible();
      await submitAnswer(fixedTeam.page, "west tower");
      await expect(fixedTeam.page.getByText("Incorrect — try again", { exact: true })).toBeVisible();
      await expect(fixedTeam.page.getByText("0 of 4 clues collected")).toBeVisible();
    });
    await test.step("the manager fixes the key and the answer is re-checked", async () => {
      await manager.page.reload();
      await saveKey(manager.page, FIXED_KEY, "Edit", "Saved. Re-checked 1 answer.");
    });
    await test.step("the team sees Correct, updated after review", async () => {
      const receipt = fixedTeam.page.getByRole("region", { name: "Receipt" });
      await expect(receipt.getByText("Correct", { exact: true })).toBeVisible(NEXT_POLL);
      await expect(receipt.getByText("Updated after review", { exact: true })).toBeVisible();
      await expect(fixedTeam.page.getByText("1 of 4 clues collected")).toBeVisible();
    });
    await test.step("the manager accepts another team's near miss", async () => {
      await signIn(nearMissTeam.page, accounts.members[9], accounts.seedPassword);
      await submitAnswer(nearMissTeam.page, "WEST TOWE");
      await expect(nearMissTeam.page.getByText("Incorrect — try again", { exact: true })).toBeVisible();
      await manager.page.reload();
      const row = manager.page.locator('[data-team="Team 10"]');
      await button(row.getByRole("group", { name: "Clue verdict" }), "Accept").click();
      await expect(row.getByText(/^Accepted by Linux manager at /)).toBeVisible();
      await expect(nearMissTeam.page.getByText("1 of 4 clues collected")).toBeVisible(NEXT_POLL);
    });
  } finally {
    await adminApi.dispose();
    await Promise.all([manager, fixedTeam, nearMissTeam].map((role) => role.page.context().close()));
  }
  expect([...manager.problems, ...fixedTeam.problems, ...nearMissTeam.problems]).toEqual([]);
});
