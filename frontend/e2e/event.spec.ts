import { test, expect, Page, Locator } from "@playwright/test";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { FinalShortcuts, RECOVERY_ANSWER, RECOVERY_QUESTION, finalContentJson } from "./event-final-helper";
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
import {
  DESKTOP,
  PHONE,
  clickAndConfirm,
  clickConfirmingIfAsked,
  expectFitsPhone,
  openRolePage,
  saveScreenshot,
  screenshotPhone,
  selectOptionContaining,
  signIn,
} from "./event-ui-helper";

let accounts: RehearsalAccounts;
const docs = path.resolve("..", "docs");
const TEAM = "Team 01";
// Other screens follow an admin or manager action when the pushed revision changes; generous timeout.
const NEXT_POLL = { timeout: 25000 };

function heading(page: Page, name: string) {
  return page.getByRole("heading", { level: 1, name, exact: true });
}

function button(scope: Page | Locator, name: string) {
  return scope.getByRole("button", { name, exact: true });
}

// Role locators use the accessible name, which excludes the aria-hidden "*" of required fields.
function textbox(scope: Page | Locator, name: string) {
  return scope.getByRole("textbox", { name, exact: true });
}

function combobox(scope: Page | Locator, name: string) {
  return scope.getByRole("combobox", { name, exact: true });
}

async function setAiKeyInUi(page: Page) {
  await signIn(page, accounts.managers.AI, accounts.seedPassword);
  await expect(heading(page, "AI")).toBeVisible();
  const card = page.locator("[data-clue-key-card]");
  await expect(card.getByText("Not set — the room cannot open", { exact: true })).toBeVisible();
  await button(card, "Set clue key").click();
  await textbox(card, "Clue key").fill(PRACTICE_KEYS.AI);
  await button(card, "Save key").click();
  await expect(card.getByText("Saved. Re-checked 0 answers.", { exact: true })).toBeVisible();
  await expect(card.getByText("Not set — the room cannot open", { exact: true })).toHaveCount(0);
  await expect(card.locator("[data-clue-key-value]")).not.toContainText(PRACTICE_KEYS.AI);
  await button(card, "Show").click();
  await expect(card.locator("[data-clue-key-value]")).toHaveText(PRACTICE_KEYS.AI);
}

async function startFirstRound(page: Page) {
  await signIn(page, accounts.adminEmail, accounts.adminPassword);
  await expect(heading(page, "Event control")).toBeVisible();
  await expect(page.getByText("Not started").first()).toBeVisible();
  await clickAndConfirm(page, button(page, "Next round"));
  await expect(page.getByText("Round 1 of 4").first()).toBeVisible();
  await expect(roundTimer(page).getByText("Assigned", { exact: true })).toBeVisible();
  const aiCard = page.locator('[data-room="AI"]');
  await clickConfirmingIfAsked(page, button(aiCard, "Open room"), button(aiCard, "Close room"));
  await expect(aiCard.getByText("Open", { exact: true })).toBeVisible();
}

function roundTimer(page: Page) {
  return page.locator("[data-round-timer]");
}

async function teamWaitsForTheTimer(page: Page) {
  await signIn(page, accounts.members[0], accounts.seedPassword);
  await expect(heading(page, TEAM)).toBeVisible();
  await expect(page.getByText("Go to the AI room. The timer starts soon.", { exact: true })).toBeVisible();
  await expect(textbox(page, "Technical output")).toHaveCount(0);
}

async function confirmAiArrival(page: Page) {
  await page.reload();
  const card = page.locator("[data-arrival-card]");
  await expect(card.getByText(TEAM, { exact: false })).toBeVisible();
  await button(card, "All teams are here").click();
  await expect(card.getByText("Ready ✓", { exact: true })).toBeVisible();
}

async function startTimerWithRoomsWaiting(page: Page) {
  const timer = roundTimer(page);
  await expect(timer.locator('[data-ready-room="AI"]').getByText("Ready ✓", { exact: true })).toBeVisible(NEXT_POLL);
  await button(timer, "Start timer").click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("3 rooms are not ready. Start anyway?");
  await expect(dialog).toContainText("Waiting: Problem solving, Linux, Hardware.");
  await button(dialog, "Confirm").click();
  await expect(timer.getByText("Running", { exact: true })).toBeVisible();
}

async function pauseRound(page: Page) {
  const timer = roundTimer(page);
  await button(timer, "Pause").click();
  await expect(timer.getByText("Paused", { exact: true })).toBeVisible();
}

async function addMinuteAndResume(page: Page) {
  const timer = roundTimer(page);
  await button(timer, "+1 min").click();
  await expect(page.getByText("+1 min added.", { exact: true })).toBeVisible();
  await button(timer, "Resume").click();
  await expect(timer.getByText("Running", { exact: true })).toBeVisible();
}

async function endRound(page: Page) {
  await clickAndConfirm(page, button(roundTimer(page), "End round"));
  await expect(roundTimer(page).getByText("Time's up", { exact: true })).toBeVisible();
}

async function submitAnswer(page: Page, technicalOutput: string, clueAnswer: string) {
  await textbox(page, "Technical output").fill(technicalOutput);
  await textbox(page, "Clue answer").fill(clueAnswer);
  await button(page, "Submit answer").click();
  await expect(page.getByText("Receipt").first()).toBeVisible();
}

async function submitAiClues(page: Page) {
  await expect(textbox(page, "Technical output")).toBeVisible(NEXT_POLL);
  await expect(page.locator('[data-room="AI"]').getByText("Now", { exact: true })).toBeVisible();
  await submitAnswer(page, "Validation accuracy 0.91", "BLUE CAR");
  await expect(page.getByText("Incorrect — try again", { exact: true }).first()).toBeVisible();
  await submitAnswer(page, "Validation accuracy 0.94 after tuning", PRACTICE_KEYS.AI);
  await expect(page.getByText("Correct", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("1 of 4 clues collected").first()).toBeVisible();
  await expectFitsPhone(page);
  await saveScreenshot(page, path.join(docs, "event-team-home.png"));
}

async function judgeAiRoom(page: Page) {
  await page.reload();
  await expect(heading(page, "AI")).toBeVisible();
  const row = page.locator(`[data-team="${TEAM}"]`);
  await expect(row.getByText("Correct", { exact: true })).toBeVisible();
  await clickConfirmingIfAsked(page, button(row, "Pass"), row.getByText("Passed", { exact: true }));
  await button(row, "Hint given (−2)").click();
  await expect(row.locator("[data-hints-count]")).toHaveText("Hints: 1 of 2");
  await expect(row.locator('[data-deduction="HINT"]')).toBeVisible();
  await saveScreenshot(page, path.join(docs, "event-manager-board.png"));
  await expectFitsPhone(page);
  await screenshotPhone(page, path.join(docs, "event-manager-board-phone.png"));
}

async function playRemainingRooms(admin: EventApiSession, team: EventApiSession) {
  const control = new EventAdminShortcuts(admin);
  const member = new EventTeamShortcuts(team);
  const visits = [
    { round: 2, room: "PROBLEM_SOLVING", clue: PRACTICE_KEYS.PROBLEM_SOLVING },
    { round: 3, room: "LINUX", clue: PRACTICE_KEYS.LINUX },
  ] as const;
  for (const visit of visits) {
    await control.startRound(visit.round);
    await control.openRoom(visit.room);
    const receipt = await member.submitClue(await control.roomId(visit.room), visit.clue);
    expect(receipt.clueCorrect, `${visit.room} clue`).toBe(true);
  }
  const board = await member.board();
  expect(board.cluesCollected).toBe(3);
  expect(board.rooms.find((room) => room.code === "HARDWARE")?.clueCollected).toBe(false);
  await control.setRound(5);
  await control.closeAllRooms();
}

async function confirmThreeClues(page: Page) {
  await page.reload();
  await expect(page.getByText("3 of 4 clues collected").first()).toBeVisible();
  await expect(page.getByText("Final locked").first()).toBeVisible();
}

async function openFinal(page: Page) {
  await page.reload();
  await expect(page.getByText("Rooms finished").first()).toBeVisible();
  await clickAndConfirm(page, button(page, "Open final"));
  await expect(button(page, "Close final")).toBeVisible();
}

async function recoverMissingClue(page: Page) {
  await page.reload();
  await page.getByRole("link", { name: "Open final", exact: true }).click();
  await expect(heading(page, "Final")).toBeVisible();
  await expect(page.locator("[data-final-status]")).toHaveText("Recovery");
  await expect(page.locator("[data-recovery-question]")).toHaveText(RECOVERY_QUESTION);
  await textbox(page, "Recovery answer").fill(RECOVERY_ANSWER);
  await button(page, "Submit recovery answer").click();
  await expect(page.locator("[data-final-status]")).toHaveText("In final");
  await expect(page.locator('[data-final-screen="UNLOCK"]')).toBeVisible();
  await expectFitsPhone(page);
  await saveScreenshot(page, path.join(docs, "event-team-final.png"));
}

async function solveCase(page: Page) {
  const team = await EventApiSession.fromPage(page);
  try {
    await new FinalShortcuts(team, () => randomUUID()).playAll();
  } finally {
    await team.dispose();
  }
  await page.reload();
  await expect(page.locator('[data-final-screen="SUBMITTED"]')).toContainText(
    "Your final answer is recorded. The result stays confidential until judging.",
  );
  await expect(page.getByRole("link", { name: "View ranking", exact: true })).toHaveCount(0);
}

async function publishResults(page: Page) {
  await page.reload();
  const leader = page.getByRole("table", { name: "Ranking", exact: true }).locator("tbody tr").first();
  await expect(leader).toContainText(TEAM);
  await expect(leader.locator("td, th").first()).toHaveText(/^\s*#?1\s*$/);
  // No room score was typed in, so the whole total is the final's 40 points.
  await expect(leader).toContainText("40");
  await saveScreenshot(page, path.join(docs, "event-admin-control.png"));
  await expectFitsPhone(page);
  await screenshotPhone(page, path.join(docs, "event-admin-control-phone.png"));
  await clickAndConfirm(page, button(page, "Publish results"));
  await expect(button(page, "Unpublish results")).toBeVisible();
}

async function viewResults(page: Page) {
  await page.reload();
  await page.getByRole("link", { name: "View ranking", exact: true }).click();
  await expect(heading(page, "Results")).toBeVisible();
  const leader = page.getByRole("table", { name: "Ranking", exact: true }).locator("tbody tr").first();
  await expect(leader).toContainText(TEAM);
  await expectFitsPhone(page);
}

// Read lazily so a missing rehearsal file fails only this test, not test collection for other configs.
test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

// The journey opens the final, which happens once per event: every run starts from a reset event.
test.beforeEach(() => resetRehearsal());

test("event: rounds, room clues, judging, hint, recovery, the case, ranking and publication", async ({ browser }) => {
  const admin = await openRolePage(browser, "admin", DESKTOP);
  const member = await openRolePage(browser, "member", PHONE);
  const manager = await openRolePage(browser, "manager", DESKTOP);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  let teamApi: EventApiSession | null = null;
  try {
    // This journey uses the AI room's plain answer form; the AI mission (on by default) has its own spec.
    await new EventAdminShortcuts(adminApi).setRoomActivity("AI", null);
    await test.step("AI manager sets the room's clue key on the board", () => setAiKeyInUi(manager.page));
    await test.step("the other managers set their keys; the admin imports the final content", async () => {
      await setPracticeKeys(accounts, ["PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
      await adminApi.send("PUT", "/event/admin/final/content", JSON.parse(finalContentJson(1)));
    });
    await test.step("admin moves to round 1 (assigned) and opens the AI room", () => startFirstRound(admin.page));
    await test.step("team is sent to the AI room; the form waits for the timer", () => teamWaitsForTheTimer(member.page));
    await test.step("AI manager confirms all teams are here", () => confirmAiArrival(manager.page));
    await test.step("admin starts the timer although 3 rooms are not ready", () => startTimerWithRoomsWaiting(admin.page));
    await test.step("team submits a wrong, then the correct AI clue", () => submitAiClues(member.page));
    await test.step("admin pauses; the team sees the round is paused", async () => {
      await pauseRound(admin.page);
      await expect(member.page.getByText("The round is paused.", { exact: true })).toBeVisible(NEXT_POLL);
    });
    await test.step("admin adds a minute and resumes", () => addMinuteAndResume(admin.page));
    await test.step("AI manager passes the team and gives a hint", () => judgeAiRoom(manager.page));
    await test.step("admin ends the round; the team sees time is up", async () => {
      await endRound(admin.page);
      await expect(member.page.getByText("Time is up for this round.", { exact: true })).toBeVisible(NEXT_POLL);
    });
    // One device per team: the helper borrows the browser's session instead of signing in again.
    teamApi = await EventApiSession.fromPage(member.page);
    const team = teamApi;
    await test.step("helper plays rounds 2-3 and finishes the rooms", () => playRemainingRooms(adminApi, team));
    await test.step("team home shows three collected clues", () => confirmThreeClues(member.page));
    await test.step("admin opens the final", () => openFinal(admin.page));
    await test.step("team recovers the missing Hardware clue", () => recoverMissingClue(member.page));
    await test.step("team plays the case to the end", () => solveCase(member.page));
    await test.step("admin sees Team 01 first and publishes", () => publishResults(admin.page));
    await test.step("team opens the published ranking", () => viewResults(member.page));
  } finally {
    await Promise.all([adminApi.dispose(), teamApi?.dispose()]);
    await Promise.all([admin, member, manager].map((role) => role.page.context().close()));
  }
  expect([...admin.problems, ...member.problems, ...manager.problems]).toEqual([]);
});
