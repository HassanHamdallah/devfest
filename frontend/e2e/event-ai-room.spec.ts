import { test, expect, Locator, Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
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
import { clickAndConfirm, expectFitsPhone, openRolePage, saveScreenshot, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
/** Teams 01 and 02 are both in group A: the AI room in round 1. */
const PASSING_TEAM = { index: 0, name: "Team 01" };
const FAILING_TEAM = { index: 1, name: "Team 02" };
const PASS_SCORE = 50;
const NOT_REACHED = "You did not reach the required score for this room's clue.";

type Section = "EASY" | "MEDIUM" | "ADVANCED" | "LANDMARK" | "RECENT";
/** The planned 20/15/6/3/1 questions; their points add up to 80, the three repairs to 20. */
const PLAN: { section: Section; count: number; points: number }[] = [
  { section: "EASY", count: 20, points: 1 },
  { section: "MEDIUM", count: 15, points: 2 },
  { section: "ADVANCED", count: 6, points: 3 },
  { section: "LANDMARK", count: 3, points: 3 },
  { section: "RECENT", count: 1, points: 3 },
];
const REPAIRS = [
  { title: "Fix the training loop", points: 7, snippets: ["for batch in loader:", "    optimizer.zero_grad()", "    loss = model(batch)", "    loss.backward()"] },
  { title: "Fix the data split", points: 7, snippets: ["rows = load_rows()", "random.shuffle(rows)", "cut = int(len(rows) * 0.8)", "train = rows[:cut]", "test = rows[cut:]"] },
  {
    title: "Fix the precision metric",
    points: 6,
    snippets: ["tp = 0", "for y, p in pairs:", "    if y == p == 1:", "        tp += 1", "precision = tp / max(1, predicted)", "print(precision)"],
  },
];

interface Question {
  section: Section;
  prompt: string;
  options: string[];
  correctOption: number;
  points: number;
}

/** What the team API sends for the MCQ part (never which option is correct). */
interface TeamActivity {
  questions: { id: number; prompt: string; options: { id: number; text: string }[]; selectedOptionId: number | null }[];
}

function quizDocument(): { format: number; mcqMinutes: number; questions: Question[]; repairs: unknown[] } {
  const questions: Question[] = [];
  for (const part of PLAN) {
    for (let i = 1; i <= part.count; i++) {
      const tag = `${part.section.toLowerCase()}-${i}`;
      questions.push({
        section: part.section,
        prompt: `Which label belongs to item ${tag}?`,
        options: [`Alpha ${tag}`, `Beta ${tag}`, `Gamma ${tag}`, `Delta ${tag}`],
        correctOption: i % 4,
        points: part.points,
      });
    }
  }
  return {
    format: 1,
    mcqMinutes: 14,
    questions,
    repairs: REPAIRS.map((r) => ({ ...r, instructions: "Put the lines in the order that makes the code work." })),
  };
}

function activityCard(page: Page): Locator {
  return page.locator("event-ai-room-activity");
}

function snippetTexts(page: Page): Promise<string[]> {
  return activityCard(page).locator(".repairs__code").allInnerTexts();
}

/** Puts the open repair into `target` order with the ▲ buttons, from the first line down. */
async function orderWithButtons(page: Page, target: string[]) {
  for (let i = 0; i < target.length; i++) {
    const current = await snippetTexts(page);
    let at = current.indexOf(target[i]);
    while (at > i) {
      await activityCard(page).getByRole("button", { name: `Move line ${at + 1} up`, exact: true }).click();
      await expect(activityCard(page).locator(".repairs__code").nth(at - 1)).toHaveText(target[i]);
      at--;
    }
  }
  expect(await snippetTexts(page)).toEqual(target);
}

async function submitOrder(page: Page) {
  await clickAndConfirm(page, activityCard(page).getByRole("button", { name: "Submit order", exact: true }));
}

/**
 * Answers every question still open through the team's own API session (as the page would), each with its correct
 * option found by text; returns the points earned.
 */
async function answerTheRestCorrectly(page: Page, roomId: number, byPrompt: Map<string, Question>): Promise<number> {
  const api = await EventApiSession.fromPage(page);
  let points = 0;
  try {
    const view = await api.send<TeamActivity>("GET", `/event/team/rooms/${roomId}/activity`);
    for (const shown of view.questions) {
      if (shown.selectedOptionId !== null) continue;
      const question = byPrompt.get(shown.prompt);
      if (!question) throw new Error(`Unknown question: ${shown.prompt}`);
      const option = shown.options.find((o) => o.text === question.options[question.correctOption]);
      if (!option) throw new Error(`No correct option on screen for ${shown.prompt}`);
      await api.send("POST", `/event/team/rooms/${roomId}/activity/answers`, { questionId: shown.id, optionId: option.id }, randomUUID());
      points += question.points;
    }
  } finally {
    await api.dispose();
  }
  return points;
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("ai room: one team reaches the pass score and sees the clue, another does not", async ({ browser }) => {
  test.setTimeout(300000);
  const manager = await openRolePage(browser, "manager", WIDE);
  const passing = await openRolePage(browser, "team", WIDE);
  const failing = await openRolePage(browser, "team-2", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const quiz = quizDocument();
  const byPrompt = new Map(quiz.questions.map((q) => [q.prompt, q]));
  let expectedMcq = 0;
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    const aiRoom = await control.roomId("AI");

    await test.step("the AI manager imports the JSON file and sets the pass score", async () => {
      const page = manager.page;
      await signIn(page, accounts.managers.AI, accounts.seedPassword);
      await page.getByRole("link", { name: "Questions and repairs", exact: true }).click();
      await expect(page.getByRole("heading", { name: "AI quiz · AI", exact: true })).toBeVisible();
      await page.locator('input[type="file"]').setInputFiles({
        name: "ai-quiz.json",
        mimeType: "application/json",
        buffer: Buffer.from(JSON.stringify(quiz)),
      });
      const dialog = page.getByRole("dialog");
      await expect(dialog.getByText("Import 45 questions and 3 repairs from ai-quiz.json.", { exact: false })).toBeVisible();
      await dialog.getByRole("button", { name: "Confirm", exact: true }).click();
      await expect(page.getByText("Imported 45 questions and 3 repairs from ai-quiz.json.", { exact: true })).toBeVisible();
      await expect(page.locator("[data-quiz-total]")).toHaveText("100 / 100");
      await expect(page.locator(".quiz-editor__warnings")).toHaveCount(0);
      const passScore = page.getByRole("spinbutton", { name: "Pass score", exact: true });
      await expect(passScore).toHaveValue("60");
      await passScore.fill(String(PASS_SCORE));
      await page.getByRole("button", { name: "Save settings", exact: true }).click();
      await expect(page.getByText("Quiz settings saved.", { exact: true })).toBeVisible();
      await expect(passScore).toHaveValue(String(PASS_SCORE));
      await saveScreenshot(page, path.join(shots, "manager-ai-quiz-editor-1440.png"), false);
      await expectFitsPhone(page);
    });

    await control.startRound(1);
    await control.openRoom("AI");
    const page = passing.page;
    await signIn(page, accounts.members[PASSING_TEAM.index], accounts.seedPassword);
    const card = activityCard(page);

    await test.step("Team 01 answers questions and only ever sees Response saved", async () => {
      await expect(card.getByText("0 of 45 answered", { exact: true })).toBeVisible();
      await expect(card.locator("[data-mcq-time]")).toHaveText(/^1[34]:\d\d$/);
      await expect(page.getByRole("textbox", { name: "Clue answer", exact: true })).toHaveCount(0);
      for (let n = 0; n < 6; n++) {
        const prompt = (await card.locator("#mcq-prompt").innerText()).trim();
        const question = byPrompt.get(prompt);
        if (!question) throw new Error(`Unknown question on screen: ${prompt}`);
        const right = n % 3 !== 2;
        const option = question.options[right ? question.correctOption : (question.correctOption + 1) % 4];
        await card.getByRole("radio", { name: option, exact: true }).check();
        await card.getByRole("button", { name: "Save answer", exact: true }).click();
        await expect(card.getByText(`${n + 1} of 45 answered`, { exact: true })).toBeVisible();
        await expect(card.getByRole("status").filter({ hasText: "Response saved" })).toBeVisible();
        if (right) expectedMcq += question.points;
      }
      const text = (await card.innerText()).toLowerCase();
      for (const word of ["correct", "incorrect", "points", "score", "pass"]) expect(text).not.toContain(word);
      await expect(card.locator(".mcq__dot--answered")).toHaveCount(6);
      await saveScreenshot(page, path.join(shots, "ai-room-mcq-1440.png"), false);
      await page.setViewportSize(NARROW);
      await expectFitsPhone(page);
      await saveScreenshot(page, path.join(shots, "ai-room-mcq-390.png"), false);
      await page.setViewportSize(WIDE);
    });

    await test.step("a reload resumes at the first unanswered question with the answers kept", async () => {
      const answered = await card.locator(".mcq__dot--answered").allInnerTexts();
      await page.reload();
      await expect(card.getByText("6 of 45 answered", { exact: true })).toBeVisible();
      await expect(card.locator(".mcq__dot--answered")).toHaveCount(6);
      const current = (await card.locator(".mcq__dot--current").innerText()).trim();
      expect(answered.map((t) => t.trim())).not.toContain(current);
    });

    await test.step("the other 39 questions are answered and Finish MCQs (confirmed) opens the repairs", async () => {
      expectedMcq += await answerTheRestCorrectly(page, aiRoom, byPrompt);
      await page.reload();
      await expect(card.getByText("45 of 45 answered", { exact: true })).toBeVisible();
      await clickAndConfirm(page, card.getByRole("button", { name: "Finish MCQs", exact: true }));
      await expect(card.getByText("Multiple choice closed · 45 of 45 answered", { exact: false })).toBeVisible();
      await expect(card.getByRole("tab")).toHaveCount(3);
    });

    await test.step("repair 1 is put in order with the ▲/▼ buttons", async () => {
      expect(await snippetTexts(page)).not.toEqual(REPAIRS[0].snippets);
      await orderWithButtons(page, REPAIRS[0].snippets);
      await saveScreenshot(page, path.join(shots, "ai-room-repair-1440.png"), false);
      await page.setViewportSize(NARROW);
      await expectFitsPhone(page);
      await saveScreenshot(page, path.join(shots, "ai-room-repair-390.png"), false);
      await page.setViewportSize(WIDE);
      await submitOrder(page);
      await expect(card.locator("[data-repair-saved]")).toHaveText("Response saved");
    });

    await test.step("a reload opens repair 2 and keeps repair 1 locked", async () => {
      await page.reload();
      await expect(card.getByRole("tab", { selected: true })).toContainText("Fix the data split");
      await card.getByRole("tab", { name: /Fix the training loop/ }).click();
      await expect(card.locator("[data-repair-saved]")).toBeVisible();
      await expect(card.getByRole("button", { name: "Submit order", exact: true })).toHaveCount(0);
      await card.getByRole("tab", { name: /Fix the data split/ }).click();
    });

    await test.step("repair 2 is ordered by drag and drop", async () => {
      const target = REPAIRS[1].snippets;
      const before = await snippetTexts(page);
      const from = before.indexOf(target[0]);
      const to = from === 0 ? 1 : 0;
      const items = card.locator("li.repairs__snippet");
      await items.nth(from).dragTo(items.nth(to));
      const after = await snippetTexts(page);
      expect(after).not.toEqual(before);
      expect(after.indexOf(target[0])).toBe(to);
      await orderWithButtons(page, target);
      await submitOrder(page);
    });

    let repair3Points = 0;
    await test.step("the last repair (as shown, partial credit) records the result: Clue unlocked", async () => {
      await card.getByRole("tab", { name: /Fix the precision metric/ }).click();
      const shown = await snippetTexts(page);
      const target = REPAIRS[2].snippets;
      expect(shown).not.toEqual(target);
      const inPlace = shown.filter((code, i) => code === target[i]).length;
      repair3Points = Math.round((REPAIRS[2].points * inPlace * 100) / target.length) / 100;
      await submitOrder(page);
      const result = card.locator('[data-quiz-result="unlocked"]');
      await expect(result).toBeVisible();
      await expect(result.getByRole("heading", { name: "Clue unlocked", exact: true })).toBeVisible();
      await expect(result.locator("[data-clue-value]")).not.toContainText(PRACTICE_KEYS.AI);
      await result.getByRole("button", { name: "Show clue", exact: true }).click();
      await expect(result.locator("[data-clue-value]")).toHaveText(PRACTICE_KEYS.AI);
      const text = (await card.innerText()).toLowerCase();
      for (const word of ["points", "score", "total", "correct"]) expect(text).not.toContain(word);
      await expect(page.locator('[data-evidence="AI"] [data-quiz-outcome]')).toContainText("Clue unlocked");
      await saveScreenshot(page, path.join(shots, "ai-room-clue-unlocked-1440.png"), false);
      await page.setViewportSize(NARROW);
      await expectFitsPhone(page);
      await result.scrollIntoViewIfNeeded();
      await saveScreenshot(page, path.join(shots, "ai-room-clue-unlocked-390.png"), false);
      await page.setViewportSize(WIDE);
    });

    await test.step("Team 02 submits its repairs without answering: the required score is not reached", async () => {
      const other = failing.page;
      await signIn(other, accounts.members[FAILING_TEAM.index], accounts.seedPassword);
      const otherCard = activityCard(other);
      await clickAndConfirm(other, otherCard.getByRole("button", { name: "Finish MCQs", exact: true }));
      for (const repair of REPAIRS) {
        await otherCard.getByRole("tab", { name: new RegExp(repair.title) }).click();
        await submitOrder(other);
      }
      await expect(otherCard.locator('[data-quiz-result="not-reached"]')).toHaveText(NOT_REACHED);
      await expect(otherCard.locator("[data-clue-value]")).toHaveCount(0);
      await expect(other.locator('[data-evidence="AI"] [data-quiz-outcome]')).toContainText(NOT_REACHED);
      await other.evaluate(() => window.scrollTo(0, 0));
      await saveScreenshot(other, path.join(shots, "ai-room-not-reached-1440.png"), false);
    });

    await test.step("the manager's results show the pass score, each team's status and clue", async () => {
      const expected = expectedMcq + REPAIRS[0].points + REPAIRS[1].points + repair3Points;
      expect(expected).toBeGreaterThanOrEqual(PASS_SCORE);
      const results = manager.page;
      await results.goto(`/event/room/${aiRoom}/quiz/results`);
      await expect(results.locator("[data-pass-score]")).toContainText(`Pass score ${PASS_SCORE} / 100`);
      await expect(results.locator("[data-pass-score]")).toContainText("Locked");
      const passed = results.locator(`[data-team="${PASSING_TEAM.name}"]`);
      await expect(passed.locator("[data-total]")).toHaveText(expected.toFixed(2));
      await expect(passed.locator("[data-status]")).toHaveText("Passed");
      await expect(passed.locator("[data-clue-granted]")).toHaveText("Granted");
      const failed = results.locator(`[data-team="${FAILING_TEAM.name}"]`);
      await expect(failed.locator("[data-status]")).toHaveText("Not passed");
      await expect(failed.locator("[data-clue-granted]")).toHaveText("—");
      await saveScreenshot(results, path.join(shots, "manager-ai-results-1440.png"), false);
      await expectFitsPhone(results);
      await results.setViewportSize(NARROW);
      await saveScreenshot(results, path.join(shots, "manager-ai-results-390.png"), false);
    });
  } finally {
    await adminApi.dispose();
    for (const role of [manager, passing, failing]) await role.page.context().close().catch(() => {});
  }
  expect([...manager.problems, ...passing.problems, ...failing.problems]).toEqual([]);
});

test("ai room: an empty quiz blocks the timer until the admin presses Start anyway", async ({ browser }) => {
  test.setTimeout(180000);
  const admin = await openRolePage(browser, "admin", WIDE);
  const team = await openRolePage(browser, "team", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    await control.setRound(1);
    await control.openRoom("AI");

    await test.step("the admin sees the blocking alert and must confirm Start anyway", async () => {
      const page = admin.page;
      await signIn(page, accounts.adminEmail, accounts.adminPassword);
      const blocking = page.locator('[data-alert="QUIZ_WITHOUT_QUESTIONS"]');
      await expect(blocking).toContainText("Room AI runs the AI quiz but has no questions.");
      await expect(blocking).toContainText("Blocks the timer");
      await saveScreenshot(page, path.join(shots, "admin-ai-quiz-blocked-1440.png"), true);
      await page.getByRole("button", { name: "Start timer", exact: true }).click();
      const dialog = page.getByRole("dialog");
      await expect(dialog.getByRole("heading", { name: "The AI quiz is not ready. Start anyway?", exact: true })).toBeVisible();
      await dialog.getByRole("button", { name: "Start anyway", exact: true }).click();
      await expect(page.getByText("Timer started.", { exact: true })).toBeVisible();
    });

    await test.step("the AI room's team sees that the questions are not ready", async () => {
      const page = team.page;
      await signIn(page, accounts.members[PASSING_TEAM.index], accounts.seedPassword);
      await expect(activityCard(page).locator("[data-quiz-not-ready]")).toHaveText("Questions are not ready yet. Wait for the organizer.");
      await expect(activityCard(page).getByText("0 of 0", { exact: false })).toHaveCount(0);
    });
  } finally {
    await adminApi.dispose();
    for (const role of [admin, team]) await role.page.context().close().catch(() => {});
  }
  expect([...admin.problems, ...team.problems]).toEqual([]);
});
