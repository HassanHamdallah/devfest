import { test, expect, Page, Locator } from "@playwright/test";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { DESKTOP, PHONE, openRolePage, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const REASON = "Rehearsal: testing the override";
// Team screens follow an override when the pushed revision changes; generous timeout.
const NEXT_POLL = { timeout: 25000 };

function button(scope: Page | Locator, name: string) {
  return scope.getByRole("button", { name, exact: true });
}

function section(page: Page, name: string) {
  return page.getByRole("region", { name, exact: true });
}

async function choose(scope: Locator, label: string, option: string) {
  await scope.getByRole("combobox", { name: label, exact: true }).selectOption({ label: option });
}

/** Fills the reason in the open override dialog and confirms it. */
async function confirmWithReason(page: Page) {
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("textbox", { name: "Reason", exact: true }).fill(REASON);
  await button(dialog, "Confirm").click();
  await expect(dialog).toBeHidden();
}

async function swapTeams(page: Page) {
  const swap = section(page, "Swap teams");
  await choose(swap, "First team", "Team 01 (group A)");
  await choose(swap, "Second team", "Team 05 (group B)");
  await button(swap, "Preview swap").click();
  await expect(swap.getByRole("table", { name: "Swap preview" })).toContainText("Team 05");
  await button(swap, "Swap teams…").click();
  await expect(page.getByRole("dialog")).toContainText("Team 01 moves from group A to group B");
  await confirmWithReason(page);
  await expect(swap.getByText("Team 01 moved to group B and Team 05 to group A.")).toBeVisible();
}

async function sendTeamToLinux(page: Page) {
  const exception = section(page, "Room exception (current round)");
  await choose(exception, "Team", "Team 02 (group A)");
  await choose(exception, "Room", "Linux");
  await button(exception, "Review exception…").click();
  await expect(page.getByRole("dialog")).toContainText("This round Team 02 goes to the Linux room");
  await confirmWithReason(page);
  await expect(exception.getByText("This round Team 02 goes to the Linux room.")).toBeVisible();
}

async function grantClue(page: Page) {
  const clue = section(page, "Manual clue");
  await choose(clue, "Team", "Team 03 (group A)");
  await choose(clue, "Room", "AI");
  await button(clue, "Review clue change…").click();
  await confirmWithReason(page);
  await expect(clue.getByText("Team 03 now holds the AI clue.")).toBeVisible();
}

async function disqualifyAndReinstate(page: Page) {
  const status = section(page, "Disqualify or reinstate");
  await choose(status, "Team", "Team 04 (group A)");
  await button(status, "Disqualify…").click();
  await confirmWithReason(page);
  await expect(status.getByText("Team 04 is disqualified.")).toBeVisible();
  await button(status, "Reinstate…").click();
  await confirmWithReason(page);
  await expect(status.getByText("Team 04 is reinstated.")).toBeVisible();
}

async function takeAndDownloadSnapshot(page: Page) {
  const snapshots = section(page, "Snapshots");
  await button(snapshots, "Take snapshot now").click();
  await confirmWithReason(page);
  const row = snapshots.locator("[data-snapshot]").first();
  await expect(row).toContainText("Manual");
  const download = page.waitForEvent("download");
  await button(row, "Download").click();
  expect((await download).suggestedFilename()).toMatch(/^event-snapshot-\d+\.json$/);
}

async function resetOneTeam(page: Page) {
  const danger = section(page, "Danger zone");
  await choose(danger, "Team to reset", "Team 02 (group A)");
  await button(danger, "Reset team…").click();
  await confirmWithReason(page);
  await expect(danger.getByText("Team 02 was reset; its device was signed out.")).toBeVisible();
}

async function resetWholeEvent(page: Page) {
  const danger = section(page, "Danger zone");
  await button(danger, "Reset whole event…").click();
  const dialog = page.getByRole("dialog");
  const confirm = button(dialog, "Confirm");
  await expect(confirm).toBeDisabled();
  await dialog.getByRole("textbox", { name: "Type RESET to confirm", exact: true }).fill("reset");
  await dialog.getByLabel("Your password", { exact: true }).fill(accounts.adminPassword);
  await expect(confirm).toBeDisabled();
  await dialog.getByRole("textbox", { name: "Type RESET to confirm", exact: true }).fill("RESET");
  await expect(confirm).toBeEnabled();
  await confirmWithReason(page);
  await expect(danger.getByText("The event was reset to not started.")).toBeVisible();
  await page.goto("/event/admin");
  await expect(page.getByText("Not started").first()).toBeVisible();
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("overrides: swap, room exception, manual clue, disqualify, snapshot, team reset and event reset", async ({ browser }) => {
  const admin = await openRolePage(browser, "admin", DESKTOP);
  const team02 = await openRolePage(browser, "Team 02", PHONE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    await new EventAdminShortcuts(adminApi).startRound(1);
    await signIn(admin.page, accounts.adminEmail, accounts.adminPassword);
    await admin.page.goto("/event/admin/overrides");
    await expect(admin.page.getByRole("heading", { level: 1, name: "Overrides" })).toBeVisible();

    await test.step("admin swaps two teams after the preview", () => swapTeams(admin.page));
    await test.step("admin sends Team 02 to the Linux room for this round", () => sendTeamToLinux(admin.page));
    await test.step("Team 02 sees where it goes this round", async () => {
      await signIn(team02.page, accounts.members[1], accounts.seedPassword);
      await expect(team02.page.getByText("This round your team goes to the Linux room.", { exact: false })).toBeVisible();
    });
    await test.step("admin grants a manual clue", () => grantClue(admin.page));
    await test.step("admin disqualifies and reinstates a team", () => disqualifyAndReinstate(admin.page));
    await test.step("admin takes a snapshot and downloads it", () => takeAndDownloadSnapshot(admin.page));
    await test.step("admin resets Team 02; its device is signed out", async () => {
      await resetOneTeam(admin.page);
      await expect(team02.page).toHaveURL(/\/login$/, NEXT_POLL);
      await expect(team02.page.getByRole("alert")).toHaveText("An organizer signed your team out.");
    });
    await test.step("admin resets the whole event with RESET and the password", () => resetWholeEvent(admin.page));
  } finally {
    await adminApi.dispose();
    await Promise.all([admin, team02].map((role) => role.page.context().close()));
  }
  // The signed-out team device gets expected 401 responses.
  const unexpected = [...admin.problems, ...team02.problems].filter((problem) => !problem.includes("status of 401"));
  expect(unexpected).toEqual([]);
});
