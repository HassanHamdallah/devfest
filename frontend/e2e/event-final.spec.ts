import { test, expect, Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import {
  CORRECT_SUSPECT,
  FinalShortcuts,
  FinalView,
  LAYER_ANSWERS,
  RECOVERY_ANSWER,
  RECOVERY_QUESTION,
  ROOMS,
  ScoreRow,
  UNLOCK_CODES,
  WRONG_SUSPECT,
  collectClues,
  finalContentJson,
  teamName,
} from "./event-final-helper";
import { PHONE, expectFitsPhone, openRolePage, saveScreenshot, screenshotPhone, signIn } from "./event-ui-helper";

const WIDE = { width: 1440, height: 900 };
const docs = path.resolve("..", "docs");
const shot = (name: string) => path.join(docs, `final-${name}.png`);

let accounts: RehearsalAccounts;

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

// The final opens once per event: every test starts from a reset event.
test.beforeEach(() => resetRehearsal());

const keyFor = () => randomUUID();

/** Team plans: Team 01 and 04 hold 4 clues, Team 02 holds 3 (no Hardware), Team 03 holds 2. */
const PLANS = [
  { number: 1, rooms: ROOMS },
  { number: 2, rooms: ["AI", "PROBLEM_SOLVING", "LINUX"] as const },
  { number: 3, rooms: ["AI", "LINUX"] as const },
  { number: 4, rooms: ROOMS },
];

async function prepareFinal(admin: EventApiSession) {
  const control = new EventAdminShortcuts(admin);
  await control.setRoomActivity("AI", null);
  await setPracticeKeys(accounts, ROOMS);
  await admin.send("PUT", "/event/admin/final/content", JSON.parse(finalContentJson()));
  await collectClues(accounts, admin, PLANS);
  await control.openFinal();
  return control;
}

function status(page: Page) {
  return page.locator("[data-final-status]");
}

function screen(page: Page, name: string) {
  return page.locator(`[data-final-screen="${name}"]`);
}

/** The countdown as seconds, from the visible "mm:ss". */
async function visibleSeconds(page: Page): Promise<number> {
  const text = await page.locator("[data-final-timer] .ui-countdown__digits").innerText();
  const [minutes, seconds] = text.trim().split(":").map(Number);
  return minutes * 60 + seconds;
}

async function typeCodes(page: Page, codes: readonly string[]) {
  const names = ["AI", "Problem solving", "Linux", "Hardware"];
  for (let i = 0; i < 4; i++) await page.getByRole("textbox", { name: names[i], exact: true }).fill(codes[i]);
}

async function submitLayer(page: Page, value: string) {
  await page.getByRole("textbox", { name: "Decrypted number M", exact: true }).fill(value);
  await page.getByRole("button", { name: "Try this number", exact: true }).click();
}

async function giveVerdict(page: Page, evidence: "E03" | "E04", verdict: "YES" | "NO") {
  await page.locator(`[data-verdict-for="${evidence}"]`).getByRole("button", { name: verdict, exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("can be given only once");
  await dialog.getByRole("button", { name: "Give verdict", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator(`[data-evidence="${evidence}"] [data-your-verdict]`)).toHaveText(`Your verdict: ${verdict}`);
}

async function submitSuspect(page: Page, name: string) {
  await page.getByLabel(name, { exact: true }).check();
  await page.getByRole("button", { name: "Submit final answer", exact: true }).first().click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(name);
  await expect(dialog).toContainText("only once");
  await dialog.getByRole("button", { name: "Submit final answer", exact: true }).click();
  await expect(screen(page, "SUBMITTED")).toBeVisible();
}

/** Both widths of the current screen into docs/: 1440 wide, then 390 wide. */
async function shootBoth(page: Page, name: string) {
  await saveScreenshot(page, shot(`${name}-1440`));
  await expectFitsPhone(page);
  await screenshotPhone(page, shot(`${name}-390`));
}

async function scores(admin: EventApiSession): Promise<Map<string, ScoreRow["scores"]>> {
  const rows = await admin.send<ScoreRow[]>("GET", "/event/admin/scoreboard");
  return new Map(rows.map((row) => [row.scores.teamName, row.scores]));
}

test("final: a 4-clue team finishes with the exact answers and the full time points; a wrong layer and a wrong verdict do not stop it; a refresh resumes", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const first = await openRolePage(browser, "team 01", WIDE);
  const fourth = await openRolePage(browser, "team 04", WIDE);
  let fourthApi: EventApiSession | null = null;
  try {
    await prepareFinal(admin);

    await test.step("Team 01: unlock refuses wrong outputs with one generic message and no field detail", async () => {
      await signIn(first.page, accounts.members[0], accounts.seedPassword);
      await first.page.goto("/event/team");
      await expect(first.page.locator("[data-hints-used]")).toHaveText("Hints used: 0 of 2");
      await first.page.getByRole("link", { name: "Open final", exact: true }).click();
      await expect(status(first.page)).toHaveText("In final");
      await expect(screen(first.page, "UNLOCK")).toBeVisible();
      await expect(first.page.locator("[data-final-timer]")).toContainText("left");
      expect(await visibleSeconds(first.page)).toBeGreaterThan(2300);
      await shootBoth(first.page, "unlock");
      await typeCodes(first.page, [UNLOCK_CODES[0], UNLOCK_CODES[1], "wrong", UNLOCK_CODES[3]]);
      await first.page.getByRole("button", { name: "Open the case", exact: true }).click();
      const feedback = first.page.locator("[data-final-feedback]");
      await expect(feedback).toHaveText("These room outputs do not open the case.");
      // Not one input is marked wrong.
      await expect(first.page.locator("[aria-invalid='true']")).toHaveCount(0);
    });

    await test.step("Team 01: the right outputs open the mission", async () => {
      await typeCodes(first.page, UNLOCK_CODES);
      await first.page.getByRole("button", { name: "Open the case", exact: true }).click();
      await expect(screen(first.page, "MISSION")).toBeVisible();
      await expect(first.page.locator("[data-final-screen='MISSION']")).toContainText(
        "Rami left four encrypted evidence layers. Solve RSA on paper, examine the clues, and verify the signed reports.",
      );
      await expect(first.page.locator("[data-final-screen='MISSION']")).toContainText(
        "Printed dossiers and four shuffled key cards. A plain calculator is allowed.",
      );
      await shootBoth(first.page, "mission");
    });

    await test.step("Team 01: layer 1 shows only C and n; a wrong integer never reveals the value", async () => {
      await first.page.getByRole("button", { name: "Start case", exact: true }).click();
      await expect(screen(first.page, "LAYER")).toBeVisible();
      await expect(first.page.locator("[data-layer-c]")).toHaveText("26");
      await expect(first.page.locator("[data-layer-n]")).toHaveText("91");
      const wrongResponse = first.page.waitForResponse((response) => response.url().includes("/team/final/layers/1"));
      await submitLayer(first.page, "53");
      const body = await (await wrongResponse).text();
      expect(body).not.toContain("52");
      await expect(first.page.locator("[data-final-feedback]")).toHaveText("That number does not open this layer.");
      await expect(first.page.locator("[data-final-screen='LAYER']")).not.toContainText("52");
      await shootBoth(first.page, "layer");
    });

    await test.step("Team 01: layers 1 and 2 open their evidence; a refresh resumes exactly there", async () => {
      await submitLayer(first.page, String(LAYER_ANSWERS[0]));
      await expect(first.page.locator("[data-layer='2']")).toBeVisible();
      await expect(first.page.locator('[data-evidence="E01"]')).toContainText("someone entered Rami's building");
      await submitLayer(first.page, String(LAYER_ANSWERS[1]));
      await expect(first.page.locator("[data-layer='3']")).toBeVisible();
      const before = await visibleSeconds(first.page);
      await first.page.reload();
      await expect(first.page.locator("[data-layer='3']")).toBeVisible();
      await expect(first.page.locator('[data-evidence="E01"]')).toBeVisible();
      await expect(first.page.locator('[data-evidence="E02"]')).toContainText("Access Card #204");
      expect(await visibleSeconds(first.page)).toBeLessThanOrEqual(before);
      await expect(status(first.page)).toHaveText("In final");
    });

    await test.step("Team 01: the signed report shows S, e, n and H; the right verdicts and layers 3 and 4", async () => {
      await submitLayer(first.page, String(LAYER_ANSWERS[2]));
      const e03 = first.page.locator('[data-evidence="E03"]');
      await expect(e03).toContainText("Kareem Shehab is responsible");
      await expect(e03).toContainText("Claimed signer: Rami Al-Khatib");
      await expect(e03.getByLabel("Signature values")).toContainText(/S\s*=\s*10/);
      await expect(e03.getByLabel("Signature values")).toContainText(/e\s*=\s*7/);
      await expect(e03.getByLabel("Signature values")).toContainText(/n\s*=\s*33/);
      await expect(e03.getByLabel("Signature values")).toContainText(/H\s*=\s*17/);
      await giveVerdict(first.page, "E03", "NO");
      await expect(first.page.locator('[data-verdict-for="E03"]')).toHaveCount(0);
      await submitLayer(first.page, String(LAYER_ANSWERS[3]));
      await expect(screen(first.page, "SUSPECTS")).toBeVisible();
      await giveVerdict(first.page, "E04", "YES");
      await shootBoth(first.page, "notebook-and-suspects");
    });

    await test.step("Team 01: chooses one of six suspects and the submitted screen never says if it was right", async () => {
      await expect(first.page.locator("[data-suspect]")).toHaveCount(6);
      await submitSuspect(first.page, CORRECT_SUSPECT);
      const submitted = screen(first.page, "SUBMITTED");
      await expect(submitted).toContainText("Your final answer is recorded. The result stays confidential until judging.");
      await expect(submitted).toContainText(teamName(1));
      await expect(submitted).toContainText("Completed");
      await expect(status(first.page)).toHaveText("Completed");
      await expect(first.page.locator("[data-suspect]")).toHaveCount(0);
      await expect(submitted).not.toContainText(/correct|right|wrong|score|points/i);
      await shootBoth(first.page, "submitted");
    });

    await test.step("Team 01 scored the exact final: 10 + 5 + 10 + 15 time points = 40", async () => {
      const row = (await scores(admin)).get(teamName(1))!;
      expect(row.finalScore).toMatchObject({ layers: 10, verdicts: 5, suspect: 10, time: 15, total: 40 });
      expect(row.total).toBe(40);
      expect(row.finalScore.status).toBe("COMPLETED");
    });

    await test.step("Team 04: a wrong verdict and a wrong suspect still let it continue to the end", async () => {
      await signIn(fourth.page, accounts.members[3], accounts.seedPassword);
      fourthApi = await EventApiSession.fromPage(fourth.page);
      const shortcuts = new FinalShortcuts(fourthApi, keyFor);
      await shortcuts.unlock();
      await shortcuts.start();
      for (let layer = 1; layer <= 3; layer++) await shortcuts.layer(layer, String(LAYER_ANSWERS[layer - 1]));
      await fourth.page.goto("/event/team/final");
      await expect(fourth.page.locator("[data-layer='4']")).toBeVisible();
      await giveVerdict(fourth.page, "E03", "YES");
      await expect(fourth.page.locator("[data-final-notebook]")).not.toContainText(/wrong|not right|incorrect|correct/i);
      await submitLayer(fourth.page, String(LAYER_ANSWERS[3]));
      await expect(screen(fourth.page, "SUSPECTS")).toBeVisible();
      await giveVerdict(fourth.page, "E04", "YES");
      await submitSuspect(fourth.page, WRONG_SUSPECT);
      const row = (await scores(admin)).get(teamName(4))!;
      expect(row.finalScore).toMatchObject({ layers: 10, verdicts: 2.5, suspect: 0, time: 15, total: 27.5 });
    });
  } finally {
    await Promise.all([admin.dispose(), fourthApi?.dispose()]);
    await Promise.all([first, fourth].map((role) => role.page.context().close()));
  }
  expect([...first.problems, ...fourth.problems].filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

test("final: a 3-clue team passes the Recovery Challenge and continues with less time; a 2-clue team is refused; a shared code opens nothing", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const recovering = await openRolePage(browser, "team 02", WIDE);
  const refused = await openRolePage(browser, "team 03", WIDE);
  let recoveringApi: EventApiSession | null = null;
  let refusedApi: EventApiSession | null = null;
  try {
    await prepareFinal(admin);

    await test.step("Team 02 (3 clues) sees the Recovery Challenge and no unlock", async () => {
      await signIn(recovering.page, accounts.members[1], accounts.seedPassword);
      await recovering.page.goto("/event/team/final");
      await expect(status(recovering.page)).toHaveText("Recovery");
      await expect(screen(recovering.page, "RECOVERY")).toBeVisible();
      await expect(recovering.page.locator("[data-recovery-question]")).toHaveText(RECOVERY_QUESTION);
      await expect(recovering.page.getByRole("textbox", { name: "Hardware", exact: true })).toHaveCount(0);
      await expect(screen(recovering.page, "UNLOCK")).toHaveCount(0);
      await shootBoth(recovering.page, "recovery");
    });

    await test.step("a shared code opens nothing: the other rooms' codes do not unlock a team that is missing a clue", async () => {
      recoveringApi = await EventApiSession.fromPage(recovering.page);
      const attempt = new FinalShortcuts(recoveringApi, keyFor);
      await expect(attempt.unlock()).rejects.toThrow(/409.*Solve the Recovery Challenge first/);
      await expect(attempt.start()).rejects.toThrow(/409/);
      await expect(attempt.layer(1, "52")).rejects.toThrow(/409/);
    });

    await test.step("Team 02: a wrong recovery answer costs nothing; the right one continues with the time that is left", async () => {
      // The server rounds the remaining time up, so within the first second after opening the final it still reads 2400.
      await expect.poll(async () => (await recoveringApi!.send<FinalView>("GET", "/event/team/final")).remainingSeconds).toBeLessThan(2400);
      await recovering.page.getByRole("textbox", { name: "Recovery answer", exact: true }).fill("a wrong answer");
      await recovering.page.getByRole("button", { name: "Submit recovery answer", exact: true }).click();
      await expect(recovering.page.getByText("Not correct — try again.")).toBeVisible();
      await expect(status(recovering.page)).toHaveText("Recovery");
      const openedAt = await visibleSeconds(recovering.page);
      await recovering.page.getByRole("textbox", { name: "Recovery answer", exact: true }).fill(RECOVERY_ANSWER.toLowerCase());
      await recovering.page.getByRole("button", { name: "Submit recovery answer", exact: true }).click();
      await expect(screen(recovering.page, "UNLOCK")).toBeVisible();
      await expect(status(recovering.page)).toHaveText("In final");
      const after = await visibleSeconds(recovering.page);
      expect(after, "the same timer continues; it is not restarted at 40:00").toBeLessThanOrEqual(openedAt);
      expect(after).toBeLessThan(2400);
      const view = await recoveringApi!.send<FinalView>("GET", "/event/team/final");
      expect(view.remainingSeconds).toBeLessThan(2400);
      await expect(recovering.page.getByText("Clue not collected")).toHaveCount(0);
      await shootBoth(recovering.page, "after-recovery");
    });

    await test.step("Team 02 now unlocks with the four outputs and starts the case", async () => {
      await typeCodes(recovering.page, UNLOCK_CODES);
      await recovering.page.getByRole("button", { name: "Open the case", exact: true }).click();
      await expect(screen(recovering.page, "MISSION")).toBeVisible();
    });

    await test.step("Team 03 (2 clues) is refused: no unlock, no recovery", async () => {
      await signIn(refused.page, accounts.members[2], accounts.seedPassword);
      await refused.page.goto("/event/team/final");
      await expect(status(refused.page)).toHaveText("Not qualified");
      await expect(screen(refused.page, "NOT_QUALIFIED")).toContainText("You did not qualify for the final");
      await expect(refused.page.locator("[data-recovery-question]")).toHaveCount(0);
      await expect(refused.page.getByRole("button", { name: "Open the case", exact: true })).toHaveCount(0);
      await expect(refused.page.getByRole("textbox")).toHaveCount(0);
      await shootBoth(refused.page, "not-qualified");
      refusedApi = await EventApiSession.fromPage(refused.page);
      const attempt = new FinalShortcuts(refusedApi, keyFor);
      await expect(attempt.unlock()).rejects.toThrow(/409.*You did not qualify for the final/);
      await expect(attempt.recover(RECOVERY_ANSWER)).rejects.toThrow(/409.*You did not qualify for the final/);
    });

    await test.step("the admin sees each team's status", async () => {
      const board = await admin.send<{ teamName: string; status: string }[]>("GET", "/event/admin/final/board");
      const byName = new Map(board.map((row) => [row.teamName, row.status]));
      expect(byName.get(teamName(1))).toBe("IN_FINAL");
      expect(byName.get(teamName(2))).toBe("IN_FINAL");
      expect(byName.get(teamName(3))).toBe("NOT_QUALIFIED");
    });
  } finally {
    await Promise.all([admin.dispose(), recoveringApi?.dispose(), refusedApi?.dispose()]);
    await Promise.all([recovering, refused].map((role) => role.page.context().close()));
  }
  expect([...recovering.problems, ...refused.problems].filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

test("final: on the phone the case fits 390 px", async ({ browser }) => {
  test.setTimeout(600000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const phone = await openRolePage(browser, "team 01 phone", PHONE);
  try {
    await prepareFinal(admin);
    await signIn(phone.page, accounts.members[0], accounts.seedPassword);
    await phone.page.goto("/event/team/final");
    await expect(screen(phone.page, "UNLOCK")).toBeVisible();
    await expectFitsPhone(phone.page);
    await typeCodes(phone.page, UNLOCK_CODES);
    await phone.page.getByRole("button", { name: "Open the case", exact: true }).click();
    await expect(screen(phone.page, "MISSION")).toBeVisible();
    await phone.page.getByRole("button", { name: "Start case", exact: true }).click();
    await submitLayer(phone.page, "52");
    await expect(phone.page.locator("[data-layer='2']")).toBeVisible();
    await expectFitsPhone(phone.page);
    await expect(phone.page.locator('[data-evidence="E01"]')).toBeVisible();
  } finally {
    await admin.dispose();
    await phone.page.context().close();
  }
  expect(phone.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});
