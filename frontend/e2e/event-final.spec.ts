import { test, expect, Page } from "@playwright/test";
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
import {
  CORRECT_SUSPECT,
  FinalShortcuts,
  FinalView,
  PASSPHRASES,
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

/** The report the main spec writes. It avoids the words the SUBMITTED screen must never say (correct, right, score...). */
const EVIDENCE_1 = "Security log: temporary card used at 9:17 PM";
const EVIDENCE_2 = "Exit record: S11 left at 9:31 PM, not at 9:00 PM";

/** The main spec decrypts in the terminal, so its decryption phase is long enough for that; the others use 1 minute. */
const MAIN_PHASE1_MINUTES = 3;

/** Team plans: Team 01 and 04 hold 4 clues, Team 02 holds 3 (no Hardware), Team 03 holds 2. */
const PLANS = [
  { number: 1, rooms: ROOMS },
  { number: 2, rooms: ["AI", "PROBLEM_SOLVING", "LINUX"] as const },
  { number: 3, rooms: ["AI", "LINUX"] as const },
  { number: 4, rooms: ROOMS },
];

/**
 * Content (with the given decryption phase length in minutes, or the file's own), the final's terminal package (unless
 * `withPackage` is false), the clues, then the final opens.
 */
async function prepareFinal(
  admin: EventApiSession,
  withPackage = true,
  phase1Minutes?: number,
  plans: readonly { number: number; rooms: readonly (typeof ROOMS)[number][] }[] = PLANS,
) {
  const control = new EventAdminShortcuts(admin);
  await control.setRoomActivity("AI", null);
  await setPracticeKeys(accounts, ROOMS);
  await admin.send("PUT", "/event/admin/final/content", JSON.parse(finalContentJson(phase1Minutes)));
  if (withPackage) await admin.upload("/event/admin/final/terminal-bundle", "final-package.zip", finalPackageZip());
  await collectClues(accounts, admin, plans);
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
    await prepareFinal(admin, true, MAIN_PHASE1_MINUTES);

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
      // The briefing is the content's intro, one paragraph per blank line, with the sentence about Noor emphasised.
      const briefing = mission.locator("[data-mission-intro]");
      const paragraphs = SHIPMENT_CONTENT.mission.intro.split("\n\n");
      await expect(briefing.locator("p")).toHaveCount(paragraphs.length);
      for (const paragraph of paragraphs) await expect(briefing).toContainText(paragraph);
      await expect(briefing.locator("strong")).toHaveText("NOOR DISAPPEARED.");
      await expect(mission.locator("[data-mission-compare] li")).toHaveText([
        "Times",
        "Access permissions",
        "Statements",
        "System records",
        "Information across all four files",
      ]);
      await expect(mission).toContainText("Trust the evidence. Question your assumptions.");
      await expect(mission).toContainText("Good luck, investigators.");
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
      // Phase 2 is locked with a live countdown; the sticky bar names the phase.
      const tab2 = first.page.getByRole("tab", { name: "Phase 2 — Investigation" });
      await expect(tab2).toHaveAttribute("aria-disabled", "true");
      await expect(first.page.locator("[data-phase-locked]")).toContainText(`Opens at ${MAIN_PHASE1_MINUTES}:00 · `);
      await expect(first.page.locator("[data-phase-locked]")).toContainText(/\d\d:\d\d left/);
      await tab2.click({ force: true });
      await expect(first.page.getByRole("tab", { name: "Phase 1 — Decryption" })).toHaveAttribute("aria-selected", "true");
      await expect(first.page.locator("[data-final-phase-label]")).toContainText("Phase 1 — Decryption · ends in ");
      await expect(first.page.locator("[data-phase-hint]")).toHaveText(
        `Decrypt all four files. Phase 2 opens at ${MAIN_PHASE1_MINUTES}:00 of the final's time, or at once when the fourth file is decrypted.`,
      );
      for (let n = 1; n <= 4; n++) {
        const clue = SHIPMENT_CONTENT.clues[n - 1];
        await expect(clueRow(first.page, n)).toContainText(clue.encFile);
        await expect(clueRow(first.page, n)).toContainText(clue.rsaFile);
        await expect(clueRow(first.page, n).locator("[data-key-reference]")).toHaveText(clue.keyReference);
        await expect(clueRow(first.page, n).locator("[data-clue-status]")).toHaveText("Locked");
      }
      // The Key Index keeps the content's order.
      const indexRows = first.page.locator("[data-key-index] tbody tr");
      await expect(indexRows).toHaveCount(4);
      for (let i = 0; i < 4; i++) {
        const entry = SHIPMENT_CONTENT.keyIndex[i];
        await expect(indexRows.nth(i).locator("td").nth(0)).toHaveText(entry.reference);
        await expect(indexRows.nth(i).locator("td").nth(1)).toHaveText(entry.key);
      }
      await expect(first.page.locator("[data-rsa-keys]")).toContainText("key_D.pem");
      // No ready-made commands anywhere: no "Start here", no key picker, no "Send to terminal", no filled commands.
      await expect(first.page.locator("[data-first-step], [data-key-picker], [data-send-to-terminal], [data-how-it-works]")).toHaveCount(0);
      await expect(first.page.getByRole("button", { name: "Send to terminal" })).toHaveCount(0);
      for (let n = 1; n <= 4; n++) {
        await expect(clueRow(first.page, n).locator("[data-final-command], li.step")).toHaveCount(0);
        await expect(clueRow(first.page, n).locator("[data-passphrase-form]")).toBeVisible();
        await expect(clueRow(first.page, n).getByRole("textbox", { name: "Recovered passphrase", exact: true })).toBeVisible();
      }
      // The Command reference sits below the cards, always open, with the generic commands and their placeholders.
      const reference = first.page.locator("[data-command-reference]");
      await expect(reference).toBeVisible();
      await expect(reference.locator("[data-reference-command] code")).toHaveText([
        "cd ~/DevFest-Crypto",
        "mkdir -p participant_test",
        "openssl pkeyutl -decrypt -inkey participant_package/keys/key_X.pem -in participant_package/clues/clueN/clueN_key.rsa -out participant_test/secret_N.txt -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256",
        "openssl enc -d -aes-256-cbc -pbkdf2 -in participant_package/clues/clueN/REPORT_NAME.enc -out participant_test/report_N.txt -pass file:participant_test/secret_N.txt",
        "cat participant_test/report_N.txt",
      ]);
      await expect(reference.locator("[data-reference-command] [data-placeholder]").first()).toBeVisible();
      await expect(reference.locator("[data-reference-note]")).toHaveText(
        "Replace ALL N with the same clue number (1-4). Replace X with your key letter from the Key Index. Replace REPORT_NAME with the exact .enc file name (without .enc).",
      );
      await expect(reference.locator("[data-reference-prove]")).toHaveText(
        "Prove a clue: run cat participant_test/secret_N.txt and paste it in the clue's box.",
      );
      await expect(reference.locator("[data-reference-command] ui-copy-button")).toHaveCount(5);
      await expect(first.page.locator(`[data-final-phase="1"] [data-decrypted-count]`)).toHaveText("0 / 4");
      // No report reaches the page before its file is decrypted.
      await expect(first.page.locator("body")).not.toContainText("Forklift FL-3");
    });

    await test.step("in the terminal, the typed commands decrypt clue 3 with key D and show the warehouse report", async () => {
      await expect(terminal(first.page)).toHaveAttribute("data-state", "ready", BOOT);
      const key = keyLetter(3);
      expect(key, "clue 3 opens with key D").toBe("D");
      const report = SHIPMENT_CONTENT.clues[2].encFile.replace(/\.enc$/, "");
      // The test types what the Command reference shows, with N = 3, X = D and REPORT_NAME replaced.
      await ask(first.page, 'cd ~/DevFest-Crypto && mkdir -p participant_test; echo "DIR=$(pwd)"', "DIR");
      await shootBoth(first.page, "phase1-clue");
      await ask(
        first.page,
        `openssl pkeyutl -decrypt -inkey participant_package/keys/key_${key}.pem -in participant_package/clues/clue3/clue3_key.rsa ` +
          "-out participant_test/secret_3.txt -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256; " +
          'echo "RSA=$?"',
        "RSA",
        // The setup seeds the kernel's random pool (haveged), so even the first RSA decryption takes about a second.
        15000,
      );
      await ask(
        first.page,
        `openssl enc -d -aes-256-cbc -pbkdf2 -in participant_package/clues/clue3/${report}.enc -out participant_test/report_3.txt ` +
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
      await expect(clueRow(first.page, 3).locator("[data-passphrase-form]")).toHaveCount(0);
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

    await test.step("clues 1 and 2 are solved through the API; with three files the suspect is still refused", async () => {
      firstApi = await EventApiSession.fromPage(first.page);
      const shortcuts = new FinalShortcuts(firstApi, keyFor);
      await shortcuts.passphrase(1, PASSPHRASES[0]);
      await shortcuts.passphrase(2, PASSPHRASES[1]);
      await expect(first.page.locator(`[data-final-phase="1"] [data-decrypted-count]`)).toHaveText("3 / 4");
      await expect(shortcuts.suspect(CORRECT_SUSPECT)).rejects.toThrow(
        new RegExp(`409.*The investigation phase starts at ${MAIN_PHASE1_MINUTES}:00\\.`),
      );
      await expect(first.page.getByRole("tab", { name: "Phase 2 — Investigation" })).toHaveAttribute("aria-disabled", "true");
      await expect
        .poll(async () => (await shortcuts.view()).clues[3].waitSeconds, { timeout: 40000 })
        .toBe(0);
    });

    await test.step("the fourth file opens the investigation at once: the page moves to Phase 2 and Phase 1 turns read-only", async () => {
      const shortcuts = new FinalShortcuts(firstApi, keyFor);
      await shortcuts.passphrase(4, PASSPHRASES[3]);
      await expect(first.page.getByRole("tab", { name: "Phase 2 — Investigation" })).toHaveAttribute("aria-selected", "true", {
        timeout: 30000,
      });
      await expect(first.page.locator('[data-final-phase="2"] [data-all-decrypted]')).toHaveText(
        "All four files decrypted: the investigation is open.",
      );
      await expect(first.page.locator("[data-final-phase-label]")).toHaveText("Phase 2 — Investigation");
      await expect(first.page.locator("[data-phase-locked]")).toHaveCount(0);
      await first.page.getByRole("tab", { name: "Phase 1 — Decryption" }).click();
      const one = first.page.locator('[data-final-phase="1"]');
      await expect(one.locator("[data-phase-over]")).toHaveText("The decryption phase is over.");
      await expect(one.locator("[data-final-terminal]")).toHaveCount(0);
      await expect(one.getByRole("textbox")).toHaveCount(0);
      await expect(clueRow(first.page, 3).locator("[data-view-report]")).toBeVisible();
      await shootBoth(first.page, "phase1-over");
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
      // Every row shows the initial statement in quotes under the name.
      await expect(phase2.locator('[data-suspect="S11"]')).toContainText("“I left the building at 9:00 PM.”");
      await expect(phase2.locator('[data-suspect="S09"]')).toContainText("“I didn't know there was an incident until Noor disappeared.”");
      // Search by name, ID, position or statement.
      const search = phase2.getByRole("searchbox", { name: "Search suspects" });
      await search.fill("samer");
      await expect(rows).toHaveCount(1);
      await search.fill("s11");
      await expect(rows).toHaveCount(1);
      await search.fill("cctv");
      await expect(rows).toHaveCount(1);
      await expect(phase2.locator('[data-suspect="S15"]')).toBeVisible();
      await search.fill("invoices");
      await expect(rows).toHaveCount(1);
      await expect(phase2.locator('[data-suspect="S05"]')).toBeVisible();
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
      await shootBoth(first.page, "phase2");
      // A row opens the full card; Close leaves the form untouched.
      const select = phase2.getByRole("combobox", { name: "Select one suspect" });
      await phase2.locator(`[data-suspect="${CORRECT_SUSPECT}"] button`).click();
      const card = first.page.locator("[data-suspect-detail]");
      await expect(card).toBeVisible();
      await expect(card.locator("[data-detail-id]")).toHaveText(CORRECT_SUSPECT);
      await expect(card.locator("[data-detail-name]")).toHaveText(suspectName(CORRECT_SUSPECT));
      await expect(card.locator("[data-detail-position]")).toHaveText("Vendor Relations Officer");
      await expect(card.locator("[data-detail-statement]")).toHaveText("“I left the building at 9:00 PM.”");
      await shootBoth(first.page, "suspect-detail");
      await card.getByRole("button", { name: "Close", exact: true }).click();
      await expect(card).toBeHidden();
      await expect(select).toHaveValue("");
      // Escape closes it too; "Select as the kidnapper" only fills the select (Submit and its confirmation follow).
      await phase2.locator(`[data-suspect="${CORRECT_SUSPECT}"] button`).press("Enter");
      await expect(card).toBeVisible();
      await first.page.keyboard.press("Escape");
      await expect(card).toBeHidden();
      await phase2.locator(`[data-suspect="${CORRECT_SUSPECT}"] button`).click();
      await card.getByRole("button", { name: "Select as the kidnapper", exact: true }).click();
      await expect(card).toBeHidden();
      await expect(select).toHaveValue(CORRECT_SUSPECT);
      await expect(phase2.locator(`[data-suspect="${CORRECT_SUSPECT}"] [data-suspect-chosen]`)).toHaveText("Selected");
      await expect(phase2.getByRole("button", { name: "Submit", exact: true })).toBeEnabled();
      // The Final Investigation Report: two evidence boxes with counters, kept on this device across a refresh.
      const evidence1 = phase2.getByRole("textbox", { name: "Evidence 1", exact: true });
      const evidence2 = phase2.getByRole("textbox", { name: "Evidence 2", exact: true });
      await expect(phase2.locator("[data-report-form]")).toContainText(
        "Your answer must include a brief explanation of the evidence supporting your conclusion.",
      );
      await expect(evidence1).toHaveAttribute("placeholder", "Which record, time or contradiction supports your answer?");
      await expect(phase2.getByRole("textbox", { name: "Evidence 4", exact: true })).toBeVisible();
      await expect(phase2.getByRole("textbox", { name: "Explanation (optional)", exact: true })).toBeVisible();
      await expect(phase2.locator("[data-report-form]")).toContainText("0 / 600");
      await evidence1.fill(EVIDENCE_1);
      await evidence2.fill(EVIDENCE_2);
      await expect(phase2.locator("[data-report-form]")).toContainText(`${EVIDENCE_1.length} / 600`);
      await expect(phase2.locator("[data-report-note]")).toHaveText("Saved on this device until you submit.");
      await shootBoth(first.page, "phase2-report");
      await first.page.reload();
      const afterReload = first.page.locator('[data-final-phase="2"]');
      await expect(afterReload.getByRole("textbox", { name: "Evidence 1", exact: true })).toHaveValue(EVIDENCE_1);
      await expect(afterReload.getByRole("textbox", { name: "Evidence 2", exact: true })).toHaveValue(EVIDENCE_2);
      await afterReload.locator(`[data-suspect="${CORRECT_SUSPECT}"] button`).click();
      await first.page
        .locator("[data-suspect-detail]")
        .getByRole("button", { name: "Select as the kidnapper", exact: true })
        .click();
      await phase2.getByRole("button", { name: "Submit", exact: true }).click();
      const dialog = first.page.getByRole("dialog");
      await expect(dialog).toContainText(`Submit ${suspectName(CORRECT_SUSPECT)} as the kidnapper? This cannot be changed.`);
      await expect(dialog).not.toContainText("Your report is empty.");
      await dialog.getByRole("button", { name: "Submit", exact: true }).click();
      const submitted = screen(first.page, "SUBMITTED");
      await expect(submitted).toContainText("Your final answer is recorded. The result stays confidential until judging.");
      await expect(submitted).toContainText(teamName(1));
      await expect(status(first.page)).toHaveText("Completed");
      await expect(submitted).not.toContainText(/correct|right|wrong|score|points/i);
      // The chosen suspect and the report come back read-only; the draft is gone from this device.
      await expect(submitted.locator("[data-submitted-suspect]")).toHaveText(`${CORRECT_SUSPECT} — ${suspectName(CORRECT_SUSPECT)}`);
      await expect(submitted.locator("[data-submitted-report]")).toContainText(EVIDENCE_1);
      await expect(submitted.locator("[data-submitted-report]")).toContainText(EVIDENCE_2);
      await expect(submitted.getByRole("textbox")).toHaveCount(0);
      expect(
        await first.page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith("devfest.final.report"))),
      ).toEqual([]);
      await shootBoth(first.page, "submitted");
    });

    await test.step("Team 01 scored the full final: 15 + 10 + 15 = 40", async () => {
      const row = (await scores(admin)).get(teamName(1))!;
      expect(row.finalScore).toMatchObject({ cluesSolved: 4, clues: 15, suspect: 10, time: 15, total: 40 });
      expect(row.finalScore.status).toBe("COMPLETED");
    });

    await test.step("the organizer's Final reports page shows the suspect, Correct and the written report", async () => {
      const organizer = await openRolePage(browser, "admin reports", WIDE);
      try {
        await signIn(organizer.page, accounts.adminEmail, accounts.adminPassword);
        await organizer.page.getByRole("link", { name: "Final reports", exact: true }).click();
        await expect(organizer.page.getByRole("heading", { name: "Final reports", exact: true })).toBeVisible();
        const card = organizer.page.locator(`[data-team="${teamName(1)}"]`);
        await expect(card.locator("[data-verdict]")).toHaveText("Correct");
        await expect(card.locator("[data-suspect]")).toHaveText(`${CORRECT_SUSPECT} — ${suspectName(CORRECT_SUSPECT)}`);
        await expect(card.locator("[data-submitted-at]")).toContainText("Submitted at minute");
        await expect(card.locator("[data-evidence]")).toHaveCount(2);
        await expect(card).toContainText(EVIDENCE_1);
        await expect(card).toContainText(EVIDENCE_2);
        // The others have not submitted: the filters tell them apart.
        await organizer.page.locator('[data-filter="NOT_SUBMITTED"]').click();
        await expect(organizer.page.locator(`[data-team="${teamName(1)}"]`)).toHaveCount(0);
        await expect(organizer.page.locator(`[data-team="${teamName(2)}"]`)).toBeVisible();
        await organizer.page.locator('[data-filter="WRONG"]').click();
        await expect(organizer.page.locator("[data-no-reports]")).toBeVisible();
        await organizer.page.locator('[data-filter="CORRECT"]').click();
        await expect(organizer.page.locator("[data-final-reports] [data-team]")).toHaveCount(1);
        await saveScreenshot(organizer.page, shot("admin-reports-1440"));
      } finally {
        await organizer.page.context().close();
      }
    });
  } finally {
    await Promise.all([admin.dispose(), firstApi?.dispose()]);
    await first.page.context().close();
  }
  expect(first.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

/** The ML recovery strip and stage list, read from the page (state comes from the server). */
function mlStage(page: Page, n: number) {
  return page.locator(`[data-ml-stages] [data-stage="${n}"]`);
}

/** Sets the threshold slider the way a pointer would: the value, then an input event. */
async function setThreshold(page: Page, value: string) {
  await page.locator("[data-ml-threshold]").evaluate((element, v) => {
    const input = element as HTMLInputElement;
    input.value = v;
    input.dispatchEvent(new Event("input", { bubbles: true }));
  }, value);
}

function checkButton(page: Page, name: string) {
  return page.getByRole("button", { name, exact: true });
}

test("final: a 3-key team solves the six ML recovery stages and reaches the unlock with its recovered key", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const recovering = await openRolePage(browser, "team 02", WIDE);
  const page = recovering.page;
  let recoveringApi: EventApiSession | null = null;
  try {
    await prepareFinal(admin);

    await test.step("Team 02 (3 keys) sees the ML level: its path, six stages, the first one current, and no unlock", async () => {
      await signIn(page, accounts.members[1], accounts.seedPassword);
      await page.goto("/event/team/final");
      await expect(status(page)).toHaveText("Recovery");
      await expect(screen(page, "RECOVERY")).toBeVisible();
      await expect(screen(page, "UNLOCK")).toHaveCount(0);
      const strip = page.locator("[data-recovery-strip]");
      await expect(strip.locator("[data-step]")).toHaveText([/ML Recovery/, /Crypto/, /Final Investigation/]);
      await expect(strip.locator('[data-step="ml"]')).toHaveAttribute("data-state", "current");
      await expect(strip.locator('[data-step="crypto"]')).toHaveAttribute("data-state", "locked");
      await expect(page.locator('[data-step="arduino"]')).toHaveCount(0);
      await expect(page.locator("[data-ml-stages] li")).toHaveCount(6);
      await expect(mlStage(page, 1)).toHaveAttribute("data-state", "current");
      await expect(mlStage(page, 2)).toHaveAttribute("data-state", "locked");
      await expect(page.locator("[data-ml-title]")).toHaveText("Stage 1 — Why is accuracy 96%?");
      await expect(page.locator("[data-ml-note]")).toHaveText("Reported Accuracy: 96%");
      recoveringApi = await EventApiSession.fromPage(page);
      await expect(new FinalShortcuts(recoveringApi, keyFor).unlock()).rejects.toThrow(/409.*Finish your recovery levels first/);
      await shootBoth(page, "recovery-ml-stage1");
    });

    await test.step("the quick reference opens and shows the formulas", async () => {
      const reference = page.locator("[data-recovery-reference]");
      await expect(reference.locator("[data-formula]").first()).toBeHidden();
      await reference.locator("summary").click();
      await expect(reference.locator("[data-formula]").first()).toBeVisible();
      await expect(reference.getByRole("math", { name: "Precision = TP / (TP + FP)" })).toBeVisible();
      await expect(reference.getByRole("math", { name: "F1 = 2 × Precision × Recall / (Precision + Recall)" })).toBeVisible();
      await expect(reference.getByRole("math", { name: "Probability ≥ Threshold → Positive" })).toBeVisible();
      await reference.locator("summary").click();
    });

    await test.step("stage 1: every feature starts kept; dropping investigation_result passes", async () => {
      for (const box of await page.locator("[data-ml-features] input").all()) await expect(box).toBeChecked();
      await page.locator('[data-feature="investigation_result"]').uncheck();
      await checkButton(page, "Validate Feature Set").click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Leakage removed. Accuracy drops to 76%, exposing the real problem.");
      await expect(page.locator("[data-recovery-result]")).toHaveAttribute("data-passed", "true");
      await expect(mlStage(page, 1)).toHaveAttribute("data-state", "done");
      await expect(mlStage(page, 2)).toHaveAttribute("data-state", "current");
    });

    await test.step("a refresh keeps the state: stage 2 is still the current one", async () => {
      await page.reload();
      await expect(mlStage(page, 2)).toHaveAttribute("data-state", "current");
      await expect(mlStage(page, 1)).toHaveAttribute("data-state", "done");
      await expect(page.locator("[data-ml-title]")).toHaveText("Stage 2 — Repair the training pipeline");
    });

    await test.step("stage 2: the steps move with real buttons (also by keyboard) and the scaler is relabelled after the split", async () => {
      const steps = page.locator("[data-ml-pipeline] li");
      await expect(steps).toHaveText([/Load Dataset/, /Fit Scaler on Full Dataset/, /Split Train\/Test/, /Transform Train\/Test/, /Train Model/, /Evaluate/]);
      const down = page.getByRole("button", { name: "Move down: Fit Scaler on Full Dataset", exact: true });
      await down.focus();
      await page.keyboard.press("Enter");
      await expect(steps).toHaveText([/Load Dataset/, /Split Train\/Test/, /Fit Scaler on Training Data/, /Transform Train\/Test/, /Train Model/, /Evaluate/]);
      await expect(page.getByRole("button", { name: "Move down: Fit Scaler on Training Data", exact: true })).toBeFocused();
      await checkButton(page, "Validate Pipeline").click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Pipeline valid.");
      await expect(mlStage(page, 3)).toHaveAttribute("data-state", "current");
    });

    await test.step("stage 3: the table and two selects; median and flag pass", async () => {
      await expect(page.locator("[data-ml-table] tbody tr")).toHaveCount(5);
      await expect(page.locator("[data-ml-table] tbody tr").nth(2)).toContainText("NULL");
      await page.getByLabel("Missing values", { exact: true }).selectOption("median");
      await page.getByLabel("Outliers", { exact: true }).selectOption("flag");
      await checkButton(page, "Validate Cleaning").click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Cleaning accepted.");
      await expect(mlStage(page, 4)).toHaveAttribute("data-state", "current");
    });

    await test.step("stage 4: a wrong model gets the neutral red message and stays; Logistic Regression passes", async () => {
      await expect(page.locator("[data-ml-models] [data-model]")).toHaveText([
        /KNN\s*Precision 0\.84 • Recall 0\.71/,
        /Logistic Regression\s*Precision 0\.79 • Recall 0\.89/,
        /Decision Tree\s*Precision 0\.68 • Recall 0\.94/,
      ]);
      await expect(page.locator("[data-ml-prompt]")).toHaveText("Requirements: Recall ≥ 0.88 and Precision ≥ 0.75.");
      await expect(checkButton(page, "Validate Model")).toBeDisabled();
      await page.locator('[data-model="tree"]').click();
      await checkButton(page, "Validate Model").click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Model rejected. Check both constraints.");
      await expect(page.locator("[data-recovery-result]")).toHaveAttribute("data-passed", "false");
      await expect(mlStage(page, 4)).toHaveAttribute("data-state", "current");
      await page.locator('[data-model="logreg"]').click();
      await checkButton(page, "Validate Model").click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Model accepted.");
      await expect(page.locator("[data-recovery-result]")).toHaveAttribute("data-passed", "true");
      await expect(mlStage(page, 5)).toHaveAttribute("data-state", "current");
    });

    await test.step("stage 5: the slider shows live metrics; a wrong threshold is refused, a right one passes", async () => {
      const metric = (name: string) => page.locator(`[data-metric="${name}"]`);
      await expect(page.locator("[data-ml-threshold-value]")).toHaveText("0.50");
      await expect(metric("recall")).toHaveText("0.91");
      await shootBoth(page, "recovery-ml-stage5");
      // The arrow keys move the slider by one step.
      await page.locator("[data-ml-threshold]").focus();
      await page.keyboard.press("ArrowLeft");
      await expect(page.locator("[data-ml-threshold-value]")).toHaveText("0.49");
      await setThreshold(page, "0.60");
      await expect(page.locator("[data-ml-threshold-value]")).toHaveText("0.60");
      await expect(metric("precision")).toHaveText("0.84");
      await expect(metric("recall")).toHaveText("0.88");
      await expect(metric("f1")).toHaveText("0.86");
      await expect(metric("falseNegatives")).toHaveText("2");
      await checkButton(page, "Validate Threshold").click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Not all constraints are satisfied.");
      await expect(mlStage(page, 5)).toHaveAttribute("data-state", "current");
      await setThreshold(page, "0.47");
      await expect(metric("recall")).toHaveText("0.92");
      await checkButton(page, "Validate Threshold").click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Threshold validated.");
      await expect(mlStage(page, 6)).toHaveAttribute("data-state", "current");
    });

    await test.step("stage 6: the four records and the word; passing finishes the level and opens the unlock with the recovered key", async () => {
      await expect(page.locator("[data-ml-records] li")).toHaveText([
        /#22\s*21:24 • NODE-U • 0\.88/,
        /#07\s*21:13 • NODE-C • 0\.94/,
        /#31\s*21:31 • NODE-E • 0\.86/,
        /#14\s*21:17 • NODE-L • 0\.91/,
      ]);
      await expect(checkButton(page, "Submit Evidence")).toBeDisabled();
      await page.getByRole("textbox", { name: "Recovered word", exact: true }).fill("clue");
      await checkButton(page, "Submit Evidence").click();
      await expect(screen(page, "UNLOCK")).toBeVisible();
      await expect(screen(page, "RECOVERY")).toHaveCount(0);
      await expect(status(page)).toHaveText("In final");
    });

    await test.step("the unlock screen shows the path done, the level's token and this team's recovered key", async () => {
      await expect(page.locator("[data-recovery-tokens]")).toContainText("ML RECOVERY TOKEN: ML-8842");
      await expect(page.locator('[data-recovery-strip] [data-step="ml"]')).toHaveAttribute("data-state", "done");
      await expect(page.locator('[data-recovery-strip] [data-step="crypto"]')).toHaveAttribute("data-state", "current");
      const keys = page.locator("[data-recovered-keys]");
      await expect(keys).toContainText("Hardware");
      await expect(keys).toContainText(PRACTICE_KEYS.HARDWARE);
      await expect(keys.locator("li")).toHaveCount(1);
      expect((await recoveringApi!.send<FinalView>("GET", "/event/team/final")).remainingSeconds).toBeLessThan(2400);
      await shootBoth(page, "recovery-unlock");
    });

    await test.step("the recovered team opens the case and reaches the mission", async () => {
      await typeCodes(page, UNLOCK_CODES);
      await page.getByRole("button", { name: "Open the case", exact: true }).click();
      await expect(screen(page, "MISSION")).toBeVisible();
      await expect(page.locator("[data-mission-clues]")).toHaveText("CLUES COLLECTED 4/4");
    });
  } finally {
    await Promise.all([admin.dispose(), recoveringApi?.dispose()]);
    await recovering.page.context().close();
  }
  expect(recovering.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

/** The right answer of each ML stage, as request bodies (the 3-key test plays the same stages through the page). */
const ML_ANSWERS = [
  { features: ["motion_score", "access_attempts", "signal_strength", "device_type"] },
  { order: ["load", "split", "fit_scaler", "transform", "train", "evaluate"] },
  { missing: "median", outliers: "flag" },
  { model: "logreg" },
  { threshold: 0.47 },
  { word: "CLUE" },
];

/** The five line bugs of the shipped program, fixed (line numbers as shown in the editor). */
const FIXED_LINES: ReadonlyArray<readonly [number, string]> = [
  [40, "    authorized = false;"],
  [45, "  if (doorOpen && !authorized) {"],
  [50, "  // if (!doorOpen) {"],
  [51, "  //   alarmActive = false;"],
  [52, "  // }"],
  [56, "  if (motionDetected && doorOpen) {"],
  [75, "  authorized = false;"],
];

function codeLine(page: Page, n: number) {
  return page.locator(`input[data-line="${n}"]`);
}

async function fixBugs(page: Page) {
  for (const [n, text] of FIXED_LINES) await codeLine(page, n).fill(text);
}

/** Picks the missing-behaviour option by a piece of its code: a team sees the four options in its own order. */
async function chooseOption(page: Page, codeFragment: string) {
  const value = await page.locator("[data-arduino-option] option", { hasText: codeFragment }).first().getAttribute("value");
  await page.locator("[data-arduino-option]").selectOption(value!);
}

function runButton(page: Page) {
  return page.locator("[data-arduino-run] button");
}

function testRow(page: Page, id: string) {
  return page.locator(`[data-arduino-tests] [data-test="${id}"]`);
}

/** The ids of the tests whose status is `status`, in order. */
async function testsWith(page: Page, status: string): Promise<string[]> {
  return page.locator(`[data-arduino-tests] [data-status="${status}"]`).evaluateAll((rows) => rows.map((row) => row.getAttribute("data-test")!));
}

test("final: a 0-key team passes ML, fixes the Arduino program (option A fails T7, the right one passes) and reaches the unlock with all four keys", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const team = await openRolePage(browser, "team 05", WIDE);
  const page = team.page;
  let teamApi: EventApiSession | null = null;
  try {
    await prepareFinal(admin, true, undefined, [...PLANS, { number: 5, rooms: [] }]);

    await test.step("Team 05 (0 keys) passes the six ML stages (through the API; the page is covered by the 3-key test)", async () => {
      await signIn(page, accounts.members[4], accounts.seedPassword);
      await page.goto("/event/team/final");
      await expect(status(page)).toHaveText("Recovery");
      await expect(page.locator('[data-recovery-strip] [data-step]')).toHaveText([/ML Recovery/, /Arduino Recovery/, /Crypto/, /Final Investigation/]);
      teamApi = await EventApiSession.fromPage(page);
      for (let stage = 1; stage <= 6; stage++) {
        await page.waitForTimeout(2100);
        const answer = await teamApi.send<{ passed: boolean }>("POST", `/event/team/final/recovery/ml/${stage}`, ML_ANSWERS[stage - 1], keyFor());
        expect(answer.passed, `stage ${stage}`).toBe(true);
      }
      await page.reload();
    });

    await test.step("the Arduino level is current: the path, the ML token, the two cards and the program", async () => {
      const strip = page.locator("[data-recovery-strip]");
      await expect(strip.locator('[data-step="ml"]')).toHaveAttribute("data-state", "done");
      await expect(strip.locator('[data-step="arduino"]')).toHaveAttribute("data-state", "current");
      await expect(strip.locator('[data-step="crypto"]')).toHaveAttribute("data-state", "locked");
      await expect(page.locator('[data-recovery-token="ML"]')).toContainText("ML RECOVERY TOKEN: ML-8842");
      await expect(page.locator("[data-ml-stages]")).toHaveCount(0);
      await expect(page.getByRole("heading", { name: "Security Controller Debugging", exact: true })).toBeVisible();
      const expected = page.locator("[data-arduino-expected] li");
      await expect(expected).toHaveCount(7);
      await expect(expected.nth(0)).toHaveText("Authorization button opens a 5-second safe window.");
      await expect(expected.nth(6)).toHaveText("The alarm stays on until Reset is pressed.");
      await expect(page.locator("[data-arduino-brief]")).toContainText("Multiple logic bugs, incorrect conditions, wrong resets, and one missing behavior.");
      await expect(page.locator("[data-arduino-brief]")).toContainText("Passing syntax is not enough. The hidden behavioral tests must all pass.");
      await expect(page.locator("[data-arduino-file]")).toHaveText("security_controller.ino");
      await expect(page.locator("input[data-line]")).toHaveCount(89);
      await expect(codeLine(page, 1)).toHaveValue("const int doorPin = 2;");
      await expect(codeLine(page, 14)).toHaveValue("bool entered = false;");
      // The missing behaviour sits in loop(), right after line 88: a select with the four options.
      await expect(page.locator('[data-row="88"] + [data-arduino-slot]')).toBeVisible();
      await expect(page.locator("[data-arduino-option] option")).toHaveCount(5);
      await expect(page.locator("[data-arduino-tests] li")).toHaveCount(8);
      expect(await testsWith(page, "NOT RUN")).toHaveLength(8);
      await expect(runButton(page)).toBeDisabled();
      await expect(page.getByText("Choose the missing behaviour in the program before running the tests.")).toBeVisible();
      // Basic syntax colouring: the first line starts with the keyword "const", and the numbers are coloured.
      await expect(page.locator('[data-row="1"] .hl .keyword').first()).toHaveText("const");
      await expect(page.locator('[data-row="1"] .hl .number')).toHaveText("2");
      await expect(page.locator('[data-row="7"] .hl .keyword').first()).toHaveText("bool");
    });

    await test.step("the lines are real inputs: Tab, Shift+Tab, Enter and the arrows move between them; none can be added or removed", async () => {
      await codeLine(page, 1).focus();
      await page.keyboard.press("Tab");
      await expect(codeLine(page, 2)).toBeFocused();
      await page.keyboard.press("Tab");
      await expect(codeLine(page, 3)).toBeFocused();
      await page.keyboard.press("Shift+Tab");
      await expect(codeLine(page, 2)).toBeFocused();
      await page.keyboard.press("ArrowDown");
      await expect(codeLine(page, 3)).toBeFocused();
      await page.keyboard.press("Enter");
      await expect(codeLine(page, 4)).toBeFocused();
      await page.keyboard.press("ArrowUp");
      await expect(codeLine(page, 3)).toBeFocused();
      await expect(page.locator("input[data-line]")).toHaveCount(89);
      await expect(page.locator("[data-arduino-editor] textarea")).toHaveCount(0);
    });

    await test.step("the quick reference opens with the Arduino syntax as code", async () => {
      const reference = page.locator('[data-recovery-reference="ARDUINO"]');
      await expect(page.locator("[data-recovery-reference]")).toHaveCount(1);
      await expect(reference.locator("[data-code-text]").first()).toBeHidden();
      await reference.locator("summary").click();
      await expect(reference).toContainText("int value = digitalRead(sensorPin);");
      await expect(reference).toContainText("if (millis() - startTime >= 5000) {");
      await expect(reference).toContainText('Serial.println(value);');
      await reference.locator("summary").click();
    });

    await test.step("on a phone the editor scrolls inside its card and the page does not", async () => {
      await shootBoth(page, "recovery-arduino");
      await page.setViewportSize(PHONE);
      const scroller = page.locator("[data-arduino-scroller]");
      const sizes = await scroller.evaluate((element) => ({ scroll: element.scrollWidth, client: element.clientWidth }));
      expect(sizes.scroll, "the editor is wider than its card").toBeGreaterThan(sizes.client);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(PHONE.width + 1);
      await page.setViewportSize(WIDE);
    });

    await test.step("Reset code asks first: Cancel keeps the edits, Confirm restores the original program", async () => {
      await codeLine(page, 40).fill("    authorized = false;");
      await chooseOption(page, "entered = true");
      await page.locator("[data-arduino-reset] button").click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toBeVisible();
      await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
      await expect(dialog).toBeHidden();
      await expect(codeLine(page, 40)).toHaveValue("    authorized = false;");
      await page.locator("[data-arduino-reset] button").click();
      await dialog.getByRole("button", { name: "Reset code", exact: true }).click();
      await expect(dialog).toBeHidden();
      await expect(codeLine(page, 40)).toHaveValue("    authorized = true;");
      await expect(page.locator("[data-arduino-option]")).toHaveValue("");
      await expect(runButton(page)).toBeDisabled();
    });

    await test.step("a changed line outside the faulty logic fails every test with the server's message", async () => {
      await fixBugs(page);
      await chooseOption(page, "if (!doorOpen && authorized)");
      await codeLine(page, 31).fill("  if (digitalRead(authButtonPin) == LOW) {");
      await runButton(page).click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("A line outside the faulty logic was changed.");
      await expect(page.locator("[data-recovery-result]")).toHaveAttribute("data-passed", "false");
      expect(await testsWith(page, "FAIL")).toHaveLength(8);
      await expect(page.locator('[data-recovery-strip] [data-step="arduino"]')).toHaveAttribute("data-state", "current");
    });

    await test.step("the run button waits out the server's 3-second limit with a countdown", async () => {
      await expect(page.locator("[data-arduino-run]")).toContainText(/Wait [123] s/);
      await expect(runButton(page)).toBeDisabled();
      await expect(page.locator("[data-arduino-run]")).toContainText("Run Hidden Tests");
    });

    await test.step("option A (ends the window before the door opens) fails T7 only; a refresh keeps the edits and the last results", async () => {
      await codeLine(page, 31).fill("  if (digitalRead(authButtonPin) == HIGH) {");
      await runButton(page).click();
      await expect(page.locator("[data-recovery-result]")).toHaveText("Not all hidden tests pass yet.");
      await expect(testRow(page, "T7")).toHaveAttribute("data-status", "FAIL");
      await expect(testRow(page, "T7")).toContainText("Authorization ends after valid entry");
      expect(await testsWith(page, "PASS")).toEqual(["T1", "T2", "T3", "T4", "T5", "T6", "T8"]);
      await shootBoth(page, "recovery-arduino-run");

      await page.reload();
      await expect(codeLine(page, 40)).toHaveValue("    authorized = false;");
      await expect(codeLine(page, 45)).toHaveValue("  if (doorOpen && !authorized) {");
      await expect(page.locator("[data-arduino-chosen]")).toContainText("if (!doorOpen && authorized)");
      await expect(testRow(page, "T7")).toHaveAttribute("data-status", "FAIL");
      expect(await testsWith(page, "PASS")).toEqual(["T1", "T2", "T3", "T4", "T5", "T6", "T8"]);
    });

    await test.step("the right option passes every test: the token shows and the team moves to the unlock", async () => {
      await chooseOption(page, "entered = true");
      await expect(page.locator("[data-arduino-chosen]")).toContainText("if (!doorOpen && entered) { authorized = false; entered = false; }");
      await runButton(page).click();
      await expect(screen(page, "UNLOCK")).toBeVisible();
      await expect(screen(page, "RECOVERY")).toHaveCount(0);
      await expect(status(page)).toHaveText("In final");
    });

    await test.step("the unlock screen shows the whole path done, both tokens and all four recovered keys", async () => {
      await expect(page.locator("[data-recovery-tokens]")).toContainText("ML RECOVERY TOKEN: ML-8842");
      await expect(page.locator("[data-recovery-tokens]")).toContainText("RECOVERY TOKEN: RCV-7314");
      const strip = page.locator("[data-recovery-strip]");
      await expect(strip.locator('[data-step="ml"]')).toHaveAttribute("data-state", "done");
      await expect(strip.locator('[data-step="arduino"]')).toHaveAttribute("data-state", "done");
      await expect(strip.locator('[data-step="crypto"]')).toHaveAttribute("data-state", "current");
      const keys = page.locator("[data-recovered-keys] li");
      await expect(keys).toHaveCount(4);
      for (const [index, room] of ["AI", "Problem solving", "Linux", "Hardware"].entries()) await expect(keys.nth(index)).toContainText(room);
      for (const key of Object.values(PRACTICE_KEYS)) await expect(page.locator("[data-recovered-keys]")).toContainText(key);
      // The saved draft goes once the level is passed (the app's own session keys stay).
      expect(await page.evaluate(() => Object.keys(sessionStorage).filter((key) => key.startsWith("devfest.event.recovery.arduino")))).toEqual([]);
      await typeCodes(page, UNLOCK_CODES);
      await page.getByRole("button", { name: "Open the case", exact: true }).click();
      await expect(screen(page, "MISSION")).toBeVisible();
    });
  } finally {
    await Promise.all([admin.dispose(), teamApi?.dispose()]);
    await team.page.context().close();
  }
  expect(team.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

test("final: the organizer's final table shows keys, path, level, finish time and crypto window; Recovery solutions opens from Final reports and stays admin only", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const organizer = await openRolePage(browser, "organizer", WIDE);
  const page = organizer.page;
  let member: EventApiSession | null = null;
  try {
    await prepareFinal(admin, true, undefined, [...PLANS, { number: 5, rooms: [] }]);
    member = await EventApiSession.signIn(accounts.members[1], accounts.seedPassword);

    const row = (n: number) => page.locator(`[data-final-table] tr[data-team="${teamName(n)}"]`);

    await test.step("the Final tab lists the keys each team held, its path, its level and its crypto window", async () => {
      await signIn(page, accounts.adminEmail, accounts.adminPassword);
      await page.getByRole("link", { name: "Scores", exact: true }).click();
      await page.getByRole("tab", { name: "Final", exact: true }).click();
      await expect(page.locator("[data-final-table] thead th")).toContainText([
        "Keys held",
        "Recovery path",
        "Recovery level",
        "Recovery finished",
        "Crypto window",
      ]);
      const expected: ReadonlyArray<readonly [number, string, string, string, string]> = [
        // team, keys, path, level, crypto window
        [1, "4", "None", "—", "0:00–15:00"],
        [2, "3", "ML", "ML · stage 1 of 6", "—"],
        [3, "2", "ML + Arduino", "ML · stage 1 of 6", "—"],
        [5, "0", "ML + Arduino", "ML · stage 1 of 6", "—"],
      ];
      for (const [n, keys, path, level, window] of expected) {
        await expect(row(n).locator("[data-keys-held]")).toHaveText(keys);
        await expect(row(n).locator("[data-recovery-path]")).toHaveText(path);
        await expect(row(n).locator("[data-recovery-level]")).toHaveText(level);
        await expect(row(n).locator("[data-crypto-window]")).toHaveText(window);
        await expect(row(n).locator("[data-recovery-finished]")).toHaveText("—");
      }
    });

    await test.step("a team finishing its recovery shows Done, when, and its own window from that moment", async () => {
      for (let stage = 1; stage <= 6; stage++) {
        await page.waitForTimeout(2100);
        await member.send("POST", `/event/team/final/recovery/ml/${stage}`, ML_ANSWERS[stage - 1], keyFor());
      }
      await page.reload();
      await page.getByRole("tab", { name: "Final", exact: true }).click();
      await expect(row(2).locator("[data-recovery-level]")).toHaveText("Done");
      const finished = (await row(2).locator("[data-recovery-finished]").innerText()).trim();
      expect(finished).toMatch(/^\d+:\d\d$/);
      const window = (await row(2).locator("[data-crypto-window]").innerText()).trim();
      const [from, to] = window.split("–").map((part) => {
        const [minutes, seconds] = part.split(":").map(Number);
        return minutes * 60 + seconds;
      });
      const [finishedMinutes, finishedSeconds] = finished.split(":").map(Number);
      expect(from).toBe(finishedMinutes * 60 + finishedSeconds);
      expect(to - from, "the decryption phase is 15 minutes long").toBe(900);
      await expect(row(2).locator("[data-recovery-path]")).toHaveText("ML");
      await expect(row(3).locator("[data-recovery-level]")).toHaveText("ML · stage 1 of 6");
      await shootBoth(page, "admin-recovery-table");
    });

    await test.step("Recovery solutions opens from Final reports and shows every ML answer", async () => {
      await page.getByRole("link", { name: "Final reports", exact: true }).click();
      await page.locator("[data-recovery-solutions-link]").click();
      await expect(page.getByRole("heading", { name: "Recovery solutions", exact: true })).toBeVisible();
      await expect(page.locator("[data-ml-solution]")).toHaveCount(6);
      await expect(page.locator('[data-ml-solution="1"] [data-ml-answer]')).toContainText("Remove: investigation_result");
      await expect(page.locator('[data-ml-solution="2"] [data-ml-answer] li')).toHaveText([
        "1. Load Dataset",
        "2. Split Train/Test",
        "3. Fit Scaler on Training Data",
        "4. Transform Train/Test",
        "5. Train Model",
        "6. Evaluate",
      ]);
      await expect(page.locator('[data-ml-solution="4"] [data-ml-answer]')).toContainText("Choose Logistic Regression");
      await expect(page.locator('[data-ml-solution="5"] [data-ml-answer]')).toContainText("Any threshold from 0.42 to 0.53");
      await expect(page.locator('[data-ml-solution="6"] [data-ml-answer]')).toContainText("Word: CLUE");
    });

    await test.step("the Arduino bug list has the fixed lines, and option B is the right one with the reason A is wrong", async () => {
      await expect(page.locator("[data-bug]")).toHaveText([/B1/, /B2/, /B3/, /B4/, /B5/, /B6/]);
      await expect(page.locator('[data-bug="B1"] [data-original]')).toHaveText("authorized = true;");
      await expect(page.locator('[data-bug="B1"] [data-fix]')).toHaveText("authorized = false;");
      await expect(page.locator('[data-bug="B2"] [data-fix]')).toHaveText("if (doorOpen && !authorized) {");
      await expect(page.locator('[data-bug="B3"] [data-bug-lines] [data-original]')).toHaveText(["if (!doorOpen) {", "alarmActive = false;", "}"]);
      await expect(page.locator('[data-bug="B4"] [data-fix]')).toHaveText("if (motionDetected && doorOpen) {");
      await expect(page.locator('[data-bug="B5"] [data-fix]')).toHaveText("authorized = false;");
      await expect(page.locator('[data-bug="B2"] [data-bug-tests]')).toContainText("T3");
      await expect(page.locator('[data-option="B"]')).toHaveAttribute("data-correct", "true");
      await expect(page.locator('[data-option="B"]')).toContainText("entered = true");
      for (const wrong of ["A", "C", "D"]) await expect(page.locator(`[data-option="${wrong}"]`)).toHaveAttribute("data-correct", "false");
      await expect(page.locator('[data-option="A"] [data-option-why]')).toContainText("before the door was ever opened");
      await expect(page.locator("[data-arduino-tests] li")).toHaveCount(8);
      await shootBoth(page, "admin-recovery-solutions");
    });

    await test.step("a team can neither open the page's data nor see it in its menu", async () => {
      await expect(member!.send("GET", "/event/admin/final/recovery-solutions")).rejects.toThrow(/403/);
      const team = await openRolePage(browser, "team 02", WIDE);
      try {
        await signIn(team.page, accounts.members[1], accounts.seedPassword);
        await expect(team.page.getByRole("link", { name: "Final reports", exact: true })).toHaveCount(0);
        await team.page.goto("/event/admin/final-recovery-solutions");
        await expect(team.page.locator("[data-recovery-solutions]")).toHaveCount(0);
      } finally {
        await team.page.context().close();
      }
    });
  } finally {
    await Promise.all([admin.dispose(), member?.dispose()]);
    await organizer.page.context().close();
  }
  expect(organizer.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

test("final: on the phone the mission and both phases fit 390 px; without a package the terminal says it is not ready", async ({
  browser,
}) => {
  test.setTimeout(600000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const phone = await openRolePage(browser, "team 01 phone", PHONE);
  try {
    await prepareFinal(admin, false, 1);
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
    await expect(phone.page.locator("[data-command-reference]")).toBeVisible();
    await expect(phone.page.locator("[data-send-to-terminal]")).toHaveCount(0);
    await expectFitsPhone(phone.page);
    await saveScreenshot(phone.page, shot("phase1-390-no-package"));
    await expect(phone.page.locator("[data-phase-locked]")).toContainText("Opens at 1:00 · ");
    await expectFitsPhone(phone.page);
    await saveScreenshot(phone.page, shot("phase2-locked-390"));
    await expect(phone.page.getByRole("tab", { name: "Phase 2 — Investigation" })).toHaveAttribute("aria-selected", "true", {
      timeout: 120000,
    });
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

test("final: Phase 2 stays locked during the decryption phase; after it Phase 1 is read-only and the suspect is submitted", async ({
  browser,
}) => {
  test.setTimeout(600000);
  const admin = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const team = await openRolePage(browser, "team 01 phases", WIDE);
  let api: EventApiSession | null = null;
  try {
    await prepareFinal(admin, true, 1);
    await signIn(team.page, accounts.members[0], accounts.seedPassword);
    await team.page.goto("/event/team/final");
    api = await EventApiSession.fromPage(team.page);
    const final = new FinalShortcuts(api, keyFor);
    await final.unlock();
    await final.start();
    await team.page.reload();
    await expect(screen(team.page, "CASE")).toBeVisible();

    await test.step("during decryption: Phase 2 is locked, the suspects are hidden and refused", async () => {
      await expect(team.page.locator("[data-phase-locked]")).toContainText("Opens at 1:00 · ");
      await expect(team.page.getByRole("tab", { name: "Phase 2 — Investigation" })).toHaveAttribute("aria-disabled", "true");
      await expect(team.page.locator('[data-final-phase="2"]')).toHaveCount(0);
      await expect(team.page.locator("[data-final-terminal]")).toBeVisible();
      await shootBoth(team.page, "phase2-locked");
      const view = await final.view();
      expect(view).toMatchObject({ phase: "DECRYPTION", phase1Minutes: 1, suspects: [] });
      await expect(final.suspect(CORRECT_SUSPECT)).rejects.toThrow(/409.*The investigation phase starts at 1:00\./);
      await final.passphrase(1, PASSPHRASES[0]);
      await team.page.reload();
      await expect(clueRow(team.page, 1).locator("[data-clue-status]")).toHaveText("Decrypted", { timeout: 20000 });
    });

    await test.step("the page moves to Phase 2 by itself; Phase 1 is read-only with the terminal gone", async () => {
      await expect(team.page.getByRole("tab", { name: "Phase 2 — Investigation" })).toHaveAttribute("aria-selected", "true", {
        timeout: 120000,
      });
      await expect(team.page.locator("[data-final-phase-label]")).toHaveText("Phase 2 — Investigation");
      await team.page.getByRole("tab", { name: "Phase 1 — Decryption" }).click();
      const one = team.page.locator('[data-final-phase="1"]');
      await expect(one.locator("[data-phase-over]")).toHaveText("The decryption phase is over.");
      await expect(one.locator("[data-final-terminal]")).toHaveCount(0);
      await expect(one.getByRole("textbox")).toHaveCount(0);
      await expect(clueRow(team.page, 1).locator("[data-view-report]")).toBeVisible();
      await expect(clueRow(team.page, 2).locator("[data-clue-status]")).toHaveText("Not decrypted in time");
      await expect(clueRow(team.page, 2).locator("li.step")).toHaveCount(0);
      await shootBoth(team.page, "phase1-over-unsolved");
    });

    await test.step("after it: passphrases are refused (no attempt, no wait), the suspects are sent and S11 is submitted", async () => {
      await expect(final.passphrase(2, PASSPHRASES[1])).rejects.toThrow(/409.*The decryption phase is over\./);
      expect(await api.status("/event/team/final/terminal-bundle")).toBe(409);
      const view = await final.view();
      expect(view).toMatchObject({ phase: "INVESTIGATION", phase1EndsInSeconds: null, terminal: null });
      expect(view.suspects).toHaveLength(SHIPMENT_CONTENT.suspects.length);
      expect(view.clues[1]).toMatchObject({ solved: false, waitSeconds: 0, report: null });
      await team.page.getByRole("tab", { name: "Phase 2 — Investigation" }).click();
      const phase2 = team.page.locator('[data-final-phase="2"]');
      await phase2.locator(`[data-suspect="${CORRECT_SUSPECT}"] button`).click();
      await team.page.locator("[data-suspect-detail]").getByRole("button", { name: "Select as the kidnapper", exact: true }).click();
      await phase2.getByRole("button", { name: "Submit", exact: true }).click();
      await expect(team.page.getByRole("dialog")).toContainText(
        "Your report is empty. Organizers use it to judge your reasoning.",
      );
      await team.page.getByRole("dialog").getByRole("button", { name: "Submit", exact: true }).click();
      await expect(screen(team.page, "SUBMITTED")).toContainText("Your final answer is recorded.");
      await expect(screen(team.page, "SUBMITTED").locator("[data-report-empty]")).toHaveText("No report was submitted.");
    });
  } finally {
    await api?.dispose();
    await admin.dispose();
    await team.page.context().close();
  }
  expect(team.problems.filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});
