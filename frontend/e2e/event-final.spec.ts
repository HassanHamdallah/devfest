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
  PASSPHRASES,
  RECOVERY_ANSWER,
  RECOVERY_QUESTION,
  ROOMS,
  SHIPMENT_CONTENT,
  ScoreRow,
  UNLOCK_CODES,
  collectClues,
  finalContentJson,
  finalPackageZip,
  keyLetter,
  suspectName,
  teamName,
} from "./event-final-helper";
import { PHONE, expectFitsPhone, openRolePage, saveScreenshot, screenshotPhone, signIn } from "./event-ui-helper";
import { ask, terminal } from "./linux-terminal-helper";

const WIDE = { width: 1440, height: 900 };
const BOOT = { timeout: 180000 };
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

/** Content, the final's terminal package (unless `withPackage` is false), the clues, then the final opens. */
async function prepareFinal(admin: EventApiSession, withPackage = true) {
  const control = new EventAdminShortcuts(admin);
  await control.setRoomActivity("AI", null);
  await setPracticeKeys(accounts, ROOMS);
  await admin.send("PUT", "/event/admin/final/content", JSON.parse(finalContentJson()));
  if (withPackage) await admin.upload("/event/admin/final/terminal-bundle", "final-package.zip", finalPackageZip());
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

function clueRow(page: Page, n: number) {
  return page.locator(`[data-clue="${n}"]`);
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

async function checkPassphrase(page: Page, n: number, value: string) {
  const row = clueRow(page, n);
  await row.getByRole("textbox", { name: "Recovered passphrase", exact: true }).fill(value);
  await row.locator("[data-passphrase-check]").getByRole("button").click();
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

test("final: a 4-clue team unlocks, decrypts a clue in the terminal, solves the rest, investigates and submits S11", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const first = await openRolePage(browser, "team 01", WIDE);
  let firstApi: EventApiSession | null = null;
  try {
    await prepareFinal(admin);

    await test.step("unlock: a wrong output gets one generic message; the right ones open the mission", async () => {
      await signIn(first.page, accounts.members[0], accounts.seedPassword);
      await first.page.goto("/event/team");
      await first.page.getByRole("link", { name: "Open final", exact: true }).click();
      await expect(status(first.page)).toHaveText("In final");
      await expect(screen(first.page, "UNLOCK")).toBeVisible();
      expect(await visibleSeconds(first.page)).toBeGreaterThan(2300);
      await typeCodes(first.page, [UNLOCK_CODES[0], UNLOCK_CODES[1], "wrong", UNLOCK_CODES[3]]);
      await first.page.getByRole("button", { name: "Open the case", exact: true }).click();
      await expect(first.page.locator("[data-final-feedback]")).toHaveText("These room outputs do not open the case.");
      await expect(first.page.locator("[aria-invalid='true']")).toHaveCount(0);
      await typeCodes(first.page, UNLOCK_CODES);
      await first.page.getByRole("button", { name: "Open the case", exact: true }).click();
    });

    await test.step("mission: clues 4/4, the content's title, intro and two phases", async () => {
      const mission = screen(first.page, "MISSION");
      await expect(mission).toBeVisible();
      await expect(mission.locator("[data-mission-clues]")).toHaveText("CLUES COLLECTED 4/4");
      await expect(mission).toContainText("MISSION UNLOCKED");
      await expect(mission.locator("[data-mission-title]")).toHaveText(SHIPMENT_CONTENT.mission.title);
      await expect(mission).toContainText("Congratulations, Investigators");
      await expect(mission.locator("[data-mission-intro]")).toHaveText(SHIPMENT_CONTENT.mission.intro);
      await expect(mission).toContainText("40:00");
      await expect(mission).toContainText("Encrypted clues");
      await expect(mission.locator('[data-mission-phase="1"]')).toContainText(SHIPMENT_CONTENT.mission.phase1);
      await expect(mission.locator('[data-mission-phase="2"]')).toContainText(SHIPMENT_CONTENT.mission.phase2);
      await shootBoth(first.page, "mission");
    });

    await test.step("Start opens the case on Phase 1 with the files, key index, RSA keys and guide", async () => {
      await first.page.getByRole("button", { name: "START FINAL INVESTIGATION", exact: true }).click();
      await expect(screen(first.page, "CASE")).toBeVisible();
      await expect(first.page.getByRole("tab", { name: "Phase 1 — Decryption" })).toHaveAttribute("aria-selected", "true");
      await expect(first.page.getByText("Suggested: Phase 1 about 15 minutes, Phase 2 about 25 minutes.")).toBeVisible();
      for (let n = 1; n <= 4; n++) {
        const clue = SHIPMENT_CONTENT.clues[n - 1];
        await expect(clueRow(first.page, n)).toContainText(clue.encFile);
        await expect(clueRow(first.page, n)).toContainText(clue.rsaFile);
        await expect(clueRow(first.page, n).locator("[data-key-reference]")).toHaveText(clue.keyReference);
        await expect(clueRow(first.page, n).locator("[data-clue-status]")).toHaveText("Locked");
      }
      await expect(first.page.locator("[data-key-index] tbody tr")).toHaveCount(4);
      await expect(first.page.locator("[data-rsa-keys]")).toContainText("key_D.pem");
      // The first step is always in view; "How it works" is collapsed, with the general commands inside.
      await expect(first.page.locator("[data-first-step]")).toContainText("cd ~/DevFest-Crypto && mkdir -p participant_test");
      const how = first.page.locator("[data-how-it-works]");
      await expect(how).not.toHaveAttribute("open", "");
      await how.locator("summary").click();
      await expect(how.locator("[data-guide-command]").first()).toContainText("key_X.pem");
      await how.locator("summary").click();
      await expect(first.page.locator(`[data-final-phase="1"] [data-decrypted-count]`)).toHaveText("0 / 4");
      // Each clue is a card with five numbered steps; the RSA command waits for the key.
      await expect(clueRow(first.page, 1).locator("li.step")).toHaveCount(5);
      await expect(clueRow(first.page, 1).locator('[data-step="rsa"]')).toContainText("key_?.pem");
      await expect(clueRow(first.page, 1).locator('[data-step="rsa"] [data-send-to-terminal]')).toHaveCount(0);
      // No report reaches the page before its file is decrypted.
      await expect(first.page.locator("body")).not.toContainText("Forklift FL-3");
    });

    await test.step("in the terminal, the guide's commands decrypt clue 3 and show the warehouse report", async () => {
      await expect(terminal(first.page)).toHaveAttribute("data-state", "ready", BOOT);
      const key = keyLetter(3);
      const report = SHIPMENT_CONTENT.clues[2].encFile;
      // "Send to terminal" types the command without pressing Enter; the test adds its marker and runs it.
      await first.page.locator("[data-first-step] [data-send-to-terminal]").getByRole("button").click();
      await expect(first.page.locator("[data-first-step] [data-send-note]")).toContainText("Typed in the terminal");
      await ask(first.page, '; echo "DIR=$(pwd)"', "DIR");
      // Pick the key for clue 3: the RSA command is filled in and may now be sent.
      await clueRow(first.page, 3).getByRole("combobox", { name: "Key letter" }).selectOption(key);
      const rsa = clueRow(first.page, 3).locator('[data-step="rsa"]');
      await expect(rsa).toContainText(`-inkey participant_package/keys/key_${key}.pem`);
      await expect(rsa).toContainText("participant_package/clues/clue3/clue3_key.rsa");
      await expect(rsa).toContainText("-out participant_test/secret_3.txt");
      await expect(clueRow(first.page, 3).locator('[data-step="aes"]')).toContainText(`clues/clue3/${report}`);
      await expect(clueRow(first.page, 3).locator('[data-step="prove"]')).toContainText("cat participant_test/secret_3.txt");
      await rsa.locator("[data-send-to-terminal]").getByRole("button").click();
      await expect(rsa.locator("[data-send-note]")).toContainText("Typed in the terminal");
      await expect(terminal(first.page).locator(".xterm-rows")).toContainText(`key_${key}.pem`);
      await shootBoth(first.page, "phase1-clue");
      await ask(
        first.page,
        '; echo "RSA=$?"',
        "RSA",
        // The setup seeds the kernel's random pool (haveged), so even the first RSA decryption takes about a second.
        15000,
      );
      await ask(
        first.page,
        `openssl enc -d -aes-256-cbc -pbkdf2 -in participant_package/clues/clue3/${report} -out participant_test/report_3.txt ` +
          `-pass file:participant_test/secret_3.txt; echo "AES=$?"`,
        "AES",
      );
      const heading = await ask(first.page, 'echo "HEAD=$(sed -n 2p participant_test/report_3.txt)"', "HEAD");
      expect(heading).toMatch(/WAREHOUSE MOVEMENT REPORT/i);
      const secret = await ask(first.page, 'echo "SECRET=$(cat participant_test/secret_3.txt)"', "SECRET");
      expect(secret).toBe(PASSPHRASES[2].trim());
      await checkPassphrase(first.page, 3, secret);
      await expect(clueRow(first.page, 3).locator("[data-clue-status]")).toHaveText("Decrypted");
      await expect(first.page.locator(`[data-final-phase="1"] [data-decrypted-count]`)).toHaveText("1 / 4");
      // The solved clue collapses and focus lands on its View report button.
      await expect(clueRow(first.page, 3).locator("li.step")).toHaveCount(0);
      await expect(clueRow(first.page, 3).locator("[data-view-report] button")).toBeFocused();
      await shootBoth(first.page, "phase1");
      // The phone-width check above narrowed the window: the same Linux keeps running (nothing was restarted).
      await expect(terminal(first.page)).toHaveAttribute("data-state", "ready");
      await expect(terminal(first.page).locator(".xterm-rows")).toContainText("SECRET=");
    });

    await test.step("View report opens the recovered report in place and keeps its lines", async () => {
      const card = clueRow(first.page, 3);
      await expect(card).toContainText("WAREHOUSE_MOVEMENT_REPORT.txt");
      await expect(clueRow(first.page, 1).locator("[data-clue-status]")).toHaveText("Locked");
      await card.getByRole("button", { name: "View report", exact: true }).click();
      const viewer = card.locator("[data-report-viewer]");
      await expect(viewer).toBeVisible();
      await expect(viewer.locator("[data-report-text]")).toContainText("Warehouse Movement Report");
      await expect(viewer.locator("[data-report-text]")).toContainText("Forklift FL-3");
      await saveScreenshot(first.page, shot("report-viewer-1440"), false);
      await card.getByRole("button", { name: "Hide report", exact: true }).click();
      await expect(viewer).toBeHidden();
    });

    await test.step("five wrong passphrases on clue 4 show the 30-second wait for that clue only", async () => {
      for (let i = 1; i <= 5; i++) {
        if (i === 5) {
          // Enter in the passphrase box is Check.
          const box = clueRow(first.page, 4).getByRole("textbox", { name: "Recovered passphrase", exact: true });
          await box.fill("not-the-passphrase-5");
          await box.press("Enter");
        } else await checkPassphrase(first.page, 4, `not-the-passphrase-${i}`);
        await expect(clueRow(first.page, 4).locator("[data-passphrase-feedback]")).toHaveText(
          "That passphrase does not open this clue.",
        );
        if (i < 5) await expect(clueRow(first.page, 4).locator("[data-passphrase-check]")).toHaveText("Check");
      }
      await expect(clueRow(first.page, 4).locator("[data-passphrase-check]")).toContainText(/Wait \d+ s/);
      await expect(clueRow(first.page, 1).locator("[data-passphrase-check]")).toHaveText("Check");
    });

    await test.step("clues 1, 2 and 4 are solved through the API; the page follows", async () => {
      firstApi = await EventApiSession.fromPage(first.page);
      const shortcuts = new FinalShortcuts(firstApi, keyFor);
      await shortcuts.passphrase(1, PASSPHRASES[0]);
      await shortcuts.passphrase(2, PASSPHRASES[1]);
      await expect
        .poll(async () => (await shortcuts.view()).clues[3].waitSeconds, { timeout: 40000 })
        .toBe(0);
      await shortcuts.passphrase(4, PASSPHRASES[3]);
      await expect(first.page.locator(`[data-final-phase="1"] [data-decrypted-count]`)).toHaveText("4 / 4");
    });

    await test.step("Phase 2: recovered evidence, the suspects sheet, and one suspect after a confirmation", async () => {
      await first.page.getByRole("tab", { name: "Phase 2 — Investigation" }).click();
      const phase2 = first.page.locator('[data-final-phase="2"]');
      await expect(phase2.locator("[data-decrypted-clue][data-solved='true']")).toHaveCount(4);
      await expect(phase2.locator("[data-suspect-count]")).toHaveText(String(SHIPMENT_CONTENT.suspects.length));
      const rows = phase2.locator("[data-suspects-sheet] [data-suspect]");
      await expect(rows).toHaveCount(SHIPMENT_CONTENT.suspects.length);
      await expect(phase2.locator('[data-suspect="S11"]')).toContainText("Vendor Relations Officer");
      await expect(phase2.locator('[data-suspect="S11"]')).toContainText("S11");
      // Search by name or ID.
      const search = phase2.getByRole("searchbox", { name: "Search suspects" });
      await search.fill("samer");
      await expect(rows).toHaveCount(1);
      await search.fill("s1");
      await expect(rows).toHaveCount(10);
      await search.fill("nobody-by-this-name");
      await expect(phase2.locator("[data-suspects-empty]")).toContainText("No suspect matches");
      await phase2.getByRole("button", { name: "Clear search", exact: true }).click();
      await expect(rows).toHaveCount(SHIPMENT_CONTENT.suspects.length);
      // A recovered report opens right under its row.
      await phase2.locator('[data-decrypted-clue="3"]').getByRole("button", { name: "View", exact: true }).click();
      await expect(phase2.locator('[data-decrypted-clue="3"] [data-report-text]')).toContainText("Forklift FL-3");
      // The notes are kept in this browser only.
      await expect(phase2.getByText("Only on this device.")).toBeVisible();
      await phase2.getByRole("textbox", { name: "My notes" }).fill("Samer left at 9:00");
      expect(
        await first.page.evaluate(() =>
          Object.entries(localStorage)
            .filter(([k]) => k.startsWith("devfest.final.notes"))
            .map(([, v]) => v),
        ),
      ).toEqual(["Samer left at 9:00"]);
      await expect(phase2.locator("[data-time-points-note]")).toHaveCount(0);
      await phase2.getByRole("combobox", { name: "Select one suspect" }).selectOption(CORRECT_SUSPECT);
      await shootBoth(first.page, "phase2");
      await phase2.getByRole("button", { name: "Submit", exact: true }).click();
      const dialog = first.page.getByRole("dialog");
      await expect(dialog).toContainText(`Submit ${suspectName(CORRECT_SUSPECT)} as the kidnapper? This cannot be changed.`);
      await dialog.getByRole("button", { name: "Submit", exact: true }).click();
      const submitted = screen(first.page, "SUBMITTED");
      await expect(submitted).toContainText("Your final answer is recorded. The result stays confidential until judging.");
      await expect(submitted).toContainText(teamName(1));
      await expect(status(first.page)).toHaveText("Completed");
      await expect(submitted).not.toContainText(/correct|right|wrong|score|points/i);
      await shootBoth(first.page, "submitted");
    });

    await test.step("Team 01 scored the full final: 15 + 10 + 15 = 40", async () => {
      const row = (await scores(admin)).get(teamName(1))!;
      expect(row.finalScore).toMatchObject({ cluesSolved: 4, clues: 15, suspect: 10, time: 15, total: 40 });
      expect(row.finalScore.status).toBe("COMPLETED");
    });
  } finally {
    await Promise.all([admin.dispose(), firstApi?.dispose()]);
    await first.page.context().close();
  }
  expect(first.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

test("final: a 3-clue team passes the Recovery Challenge and reaches the mission; a 2-clue team is refused", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const recovering = await openRolePage(browser, "team 02", WIDE);
  const refused = await openRolePage(browser, "team 03", WIDE);
  let recoveringApi: EventApiSession | null = null;
  try {
    await prepareFinal(admin);

    await test.step("Team 02 (3 clues) sees the Recovery Challenge and no unlock", async () => {
      await signIn(recovering.page, accounts.members[1], accounts.seedPassword);
      await recovering.page.goto("/event/team/final");
      await expect(status(recovering.page)).toHaveText("Recovery");
      await expect(screen(recovering.page, "RECOVERY")).toBeVisible();
      await expect(recovering.page.locator("[data-recovery-question]")).toHaveText(RECOVERY_QUESTION);
      await expect(screen(recovering.page, "UNLOCK")).toHaveCount(0);
      recoveringApi = await EventApiSession.fromPage(recovering.page);
      await expect(new FinalShortcuts(recoveringApi, keyFor).unlock()).rejects.toThrow(/409.*Solve the Recovery Challenge first/);
    });

    await test.step("a wrong answer costs nothing; the right one leads to the unlock, then the mission", async () => {
      await recovering.page.getByRole("textbox", { name: "Recovery answer", exact: true }).fill("a wrong answer");
      await recovering.page.getByRole("button", { name: "Submit recovery answer", exact: true }).click();
      await expect(recovering.page.getByText("Not correct — try again.")).toBeVisible();
      await recovering.page.getByRole("textbox", { name: "Recovery answer", exact: true }).fill(RECOVERY_ANSWER.toLowerCase());
      await recovering.page.getByRole("button", { name: "Submit recovery answer", exact: true }).click();
      await expect(screen(recovering.page, "UNLOCK")).toBeVisible();
      await expect(status(recovering.page)).toHaveText("In final");
      expect((await recoveringApi!.send<FinalView>("GET", "/event/team/final")).remainingSeconds).toBeLessThan(2400);
      await typeCodes(recovering.page, UNLOCK_CODES);
      await recovering.page.getByRole("button", { name: "Open the case", exact: true }).click();
      await expect(screen(recovering.page, "MISSION")).toBeVisible();
      await expect(recovering.page.locator("[data-mission-clues]")).toHaveText("CLUES COLLECTED 4/4");
    });

    await test.step("Team 03 (2 clues) is refused: no unlock, no recovery", async () => {
      await signIn(refused.page, accounts.members[2], accounts.seedPassword);
      await refused.page.goto("/event/team/final");
      await expect(status(refused.page)).toHaveText("Not qualified");
      await expect(screen(refused.page, "NOT_QUALIFIED")).toContainText("You did not qualify for the final");
      await expect(refused.page.getByRole("textbox")).toHaveCount(0);
    });
  } finally {
    await Promise.all([admin.dispose(), recoveringApi?.dispose()]);
    await Promise.all([recovering, refused].map((role) => role.page.context().close()));
  }
  expect([...recovering.problems, ...refused.problems].filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

test("final: on the phone the mission and both phases fit 390 px; without a package the terminal says it is not ready", async ({
  browser,
}) => {
  test.setTimeout(600000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const phone = await openRolePage(browser, "team 01 phone", PHONE);
  try {
    await prepareFinal(admin, false);
    await signIn(phone.page, accounts.members[0], accounts.seedPassword);
    await phone.page.goto("/event/team/final");
    await expect(screen(phone.page, "UNLOCK")).toBeVisible();
    await typeCodes(phone.page, UNLOCK_CODES);
    await phone.page.getByRole("button", { name: "Open the case", exact: true }).click();
    await expect(screen(phone.page, "MISSION")).toBeVisible();
    await expectFitsPhone(phone.page);
    await phone.page.getByRole("button", { name: "START FINAL INVESTIGATION", exact: true }).click();
    await expect(screen(phone.page, "CASE")).toBeVisible();
    await expect(phone.page.locator("[data-final-terminal-missing]")).toHaveText(
      "The terminal package is not ready yet. Ask the organizer.",
    );
    await phone.page.locator("[data-first-step] [data-send-to-terminal]").getByRole("button").click();
    await expect(phone.page.locator("[data-first-step] [data-send-note]")).toHaveText(
      "The terminal package is not ready yet. Ask the organizer.",
    );
    await expectFitsPhone(phone.page);
    await saveScreenshot(phone.page, shot("phase1-390-no-package"));
    await phone.page.getByRole("tab", { name: "Phase 2 — Investigation" }).click();
    await expect(phone.page.locator("[data-time-points-note]")).toHaveText(
      "You can still submit; time points need all four clues decrypted.",
    );
    await expectFitsPhone(phone.page);
  } finally {
    await admin.dispose();
    await phone.page.context().close();
  }
  expect(phone.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});
