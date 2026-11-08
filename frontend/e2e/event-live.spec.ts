import { test, expect, Page, Locator } from "@playwright/test";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { DESKTOP, PHONE, openRolePage, selectOptionContaining, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const TEAM = "Team 01";
// The revision push (SSE) reaches other screens in well under a second; 1.5 s leaves room for the refetch.
const PUSHED = { timeout: 1500 };
// Without the stream the page polls GET /revision every 3 s.
const POLLED = { timeout: 4500 };
const STREAM_PATH = "/api/v1/event/stream?";

function heading(page: Page, name: string) {
  return page.getByRole("heading", { level: 1, name, exact: true });
}

function button(scope: Page | Locator, name: string) {
  return scope.getByRole("button", { name, exact: true });
}

function isOpenStream(url: string, status: number) {
  return url.includes(STREAM_PATH) && status === 200;
}

/** Signs in and waits until this tab's live stream is open, so a later push cannot be missed by timing. */
async function signInLive(page: Page, email: string, password: string) {
  const stream = page.waitForResponse((response) => isOpenStream(response.url(), response.status()));
  await signIn(page, email, password);
  await stream;
}

/** "24:59" → "25:59". */
function plusOneMinute(clock: string): string {
  const [minutes, seconds] = clock.split(":");
  return `${String(Number(minutes) + 1).padStart(2, "0")}:${seconds}`;
}

function managerStatus(page: Page) {
  return page.getByRole("group", { name: "Event status", exact: true });
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("live: admin and manager actions reach the team, manager and admin screens within 1.5 s", async ({ browser }) => {
  const member = await openRolePage(browser, "member", PHONE);
  const manager = await openRolePage(browser, "manager", DESKTOP);
  const admin = await openRolePage(browser, "admin", DESKTOP);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    // The team submits through the AI room's plain answer form; the AI mission (on by default) has its own spec.
    await control.setRoomActivity("AI", null);
    await signInLive(member.page, accounts.members[0], accounts.seedPassword);
    await expect(heading(member.page, TEAM)).toBeVisible();
    await signInLive(manager.page, accounts.managers.AI, accounts.seedPassword);
    await expect(heading(manager.page, "AI")).toBeVisible();
    await signInLive(admin.page, accounts.adminEmail, accounts.adminPassword);
    await expect(heading(admin.page, "Event control")).toBeVisible();
    const teamTimer = member.page.getByRole("timer");
    const managerTimer = managerStatus(manager.page).getByRole("timer");

    await test.step("admin assigns round 1", async () => {
      await control.setRound(1);
      await expect(member.page.getByText("Go to the AI room. The timer starts soon.", { exact: true })).toBeVisible(PUSHED);
      await expect(managerStatus(manager.page).getByText("Assigned", { exact: true })).toBeVisible(PUSHED);
    });
    await test.step("admin starts the timer", async () => {
      await control.startTimer();
      await expect(member.page.getByText(/^The AI room is closed right now\./)).toBeVisible(PUSHED);
      await expect(managerStatus(manager.page).getByText("Running", { exact: true })).toBeVisible(PUSHED);
    });
    await test.step("admin opens the AI room", async () => {
      await control.openRoom("AI");
      await expect(member.page.getByRole("textbox", { name: "Technical output", exact: true })).toBeVisible(PUSHED);
      await expect(manager.page.getByText("Open", { exact: true }).first()).toBeVisible(PUSHED);
    });
    let pausedAt = "";
    await test.step("admin pauses the timer", async () => {
      await control.pauseTimer();
      await expect(member.page.getByText("The round is paused.", { exact: true })).toBeVisible(PUSHED);
      await expect(managerTimer).toHaveAttribute("aria-label", /^\d\d:\d\d · paused$/, PUSHED);
      pausedAt = (await managerTimer.getAttribute("aria-label"))!.slice(0, 5);
    });
    await test.step("admin adds a minute", async () => {
      await control.addTime(60);
      const later = plusOneMinute(pausedAt);
      await expect(teamTimer).toHaveAttribute("aria-label", new RegExp(`^${later}`), PUSHED);
      await expect(managerTimer).toHaveAttribute("aria-label", `${later} · paused`, PUSHED);
    });
    await test.step("admin resumes; the team submits the AI clue", async () => {
      await control.resumeTimer();
      await expect(managerStatus(manager.page).getByText("Running", { exact: true })).toBeVisible(PUSHED);
      const output = member.page.getByRole("textbox", { name: "Technical output", exact: true });
      await expect(output).toBeVisible(PUSHED);
      await output.fill("Validation accuracy 0.94");
      await member.page.getByRole("textbox", { name: "Clue answer", exact: true }).fill("WHITE VAN");
      await button(member.page, "Submit answer").click();
      await expect(member.page.getByText("Correct", { exact: true }).first()).toBeVisible();
      await expect(manager.page.locator(`[data-team="${TEAM}"]`).getByText("Correct", { exact: true })).toBeVisible(PUSHED);
    });
    await test.step("manager passes the team", async () => {
      const row = manager.page.locator(`[data-team="${TEAM}"]`);
      await button(row, "Pass").click();
      const confirm = manager.page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true });
      const passed = row.getByText("Passed", { exact: true });
      await expect(confirm.or(passed).first()).toBeVisible();
      if (await confirm.isVisible()) await confirm.click();
      await expect(passed).toBeVisible();
      await expect(member.page.locator('[data-room="AI"]').getByText("Passed", { exact: true })).toBeVisible(PUSHED);
      await expect(admin.page.locator('[aria-label="AI teams passed"]')).toHaveAttribute("aria-valuenow", "1", PUSHED);
    });
    await test.step("manager adds a hint penalty; the admin ranking follows", async () => {
      const ranking = admin.page.getByRole("table", { name: "Ranking", exact: true });
      const teamRow = ranking.locator("tbody tr").filter({ hasText: TEAM });
      await expect(teamRow).toBeVisible();
      const before = await teamRow.innerText();
      const row = manager.page.locator(`[data-team="${TEAM}"]`);
      await selectOptionContaining(row.getByRole("combobox", { name: "Penalty kind", exact: true }), "Hint");
      await row.getByRole("textbox", { name: "Penalty note", exact: true }).fill("Live push check.");
      await button(row, "Add penalty").click();
      const confirm = manager.page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true });
      const added = row.getByText("Live push check.");
      await expect(confirm.or(added).first()).toBeVisible();
      if (await confirm.isVisible()) await confirm.click();
      await expect(added).toBeVisible();
      await expect(teamRow).not.toHaveText(before, PUSHED);
    });
    await test.step("admin finishes the rooms and opens the final", async () => {
      await control.setRound(5);
      await control.closeAllRooms();
      await expect(member.page.getByText("All room rounds are finished.", { exact: true })).toBeVisible(PUSHED);
      await control.openFinal();
      await expect(member.page.getByRole("link", { name: "Open final", exact: true })).toBeVisible(PUSHED);
      await expect(managerStatus(manager.page).getByText("Final opened", { exact: true })).toBeVisible(PUSHED);
    });
    await test.step("admin publishes the results", async () => {
      // A full page load opens a new stream; wait for it like after sign-in.
      const stream = member.page.waitForResponse((response) => isOpenStream(response.url(), response.status()));
      await member.page.goto("/event/results");
      await stream;
      await expect(member.page.getByText("Results are not published yet.", { exact: true })).toBeVisible();
      await control.setPublication(true);
      const leader = member.page.getByRole("table", { name: "Ranking", exact: true }).locator("tbody tr").first();
      await expect(leader).toBeVisible(PUSHED);
    });
  } finally {
    await adminApi.dispose();
    await Promise.all([member, manager, admin].map((role) => role.page.context().close()));
  }
  // The member's results page reads the expected 403 "Results are not published yet." before publication.
  const memberProblems = member.problems.filter((problem) => !problem.includes("status of 403 (Forbidden)"));
  expect([...memberProblems, ...manager.problems, ...admin.problems]).toEqual([]);
});

test("live: without the stream the team page polls, and the stream comes back", async ({ browser }) => {
  const member = await openRolePage(browser, "member", PHONE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const context = member.page.context();
  try {
    await context.route(`**${STREAM_PATH}**`, (route) => route.abort());
    await signIn(member.page, accounts.members[0], accounts.seedPassword);
    await expect(heading(member.page, TEAM)).toBeVisible();

    // Like the test above, this follows the AI room's plain answer form, so the AI mission is off.
    await control.setRoomActivity("AI", null);
    await control.setRound(1);
    await expect(member.page.getByText(/^Go to the \w+ room\. The timer starts soon\.$/)).toBeVisible(POLLED);

    const reopened = member.page.waitForResponse((response) => isOpenStream(response.url(), response.status()), {
      timeout: 15000,
    });
    await context.unroute(`**${STREAM_PATH}**`);
    await reopened;
    await control.startTimer();
    await expect(member.page.getByText(/^The \w+ room is closed right now\./)).toBeVisible(PUSHED);
  } finally {
    await adminApi.dispose();
    await context.close();
  }
});

test("live: a second sign-in of the same team signs the first device out within 2 s", async ({ browser }) => {
  const first = await openRolePage(browser, "first device", PHONE);
  const second = await openRolePage(browser, "second device", PHONE);
  try {
    await signInLive(first.page, accounts.members[0], accounts.seedPassword);
    await expect(heading(first.page, TEAM)).toBeVisible();

    await signIn(second.page, accounts.members[0], accounts.seedPassword);
    await expect(
      first.page.getByText("Your team signed in on another device. Only one device per team can be signed in."),
    ).toBeVisible({ timeout: 2000 });
    await expect(first.page).toHaveURL(/\/login$/);
    await expect(heading(second.page, TEAM)).toBeVisible();
  } finally {
    await Promise.all([first, second].map((role) => role.page.context().close()));
  }
});
