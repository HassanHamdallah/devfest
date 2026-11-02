import { test, expect, Page, Locator } from "@playwright/test";
import path from "node:path";
import { EventApiSession, RehearsalAccounts, readRehearsalAccounts } from "./event-api-helper";
import { DESKTOP, PHONE, openRolePage, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const docs = path.resolve("..", "docs");
const PREFIX = "Squad";
const ANOTHER_DEVICE = "Your team signed in on another device. Only one device per team can be signed in.";
const ORGANIZER = "An organizer signed your team out.";
// A signed-out device notices at its next request: the revision poll runs every 10 s.
const NEXT_POLL = { timeout: 25000 };

interface Credential {
  teamName: string;
  email: string;
  password: string;
}

function button(scope: Page | Locator, name: string) {
  return scope.getByRole("button", { name, exact: true });
}

function spinbutton(scope: Page | Locator, name: string) {
  return scope.getByRole("spinbutton", { name, exact: true });
}

async function activeTeamCount(admin: EventApiSession) {
  const org = await admin.send<{ groups: { activeTeams: number }[] }>("GET", "/event/admin/organization");
  return org.groups.reduce((sum, group) => sum + group.activeTeams, 0);
}

async function setPlannedTeams(page: Page, planned: number) {
  await page.goto("/event/admin/teams");
  const form = page.getByRole("form", { name: "Planned teams" });
  await spinbutton(form, "Planned teams").fill(String(planned));
  await expect(form.getByText(`Max per group: ${Math.ceil(planned / 4)}`)).toBeVisible();
  await button(form, "Save").click();
  await expect(form.getByText(`Planned teams set to ${planned}.`)).toBeVisible();
}

async function generateTwoTeams(page: Page): Promise<Credential> {
  const form = page.getByRole("form", { name: "Generate teams" });
  await spinbutton(form, "Number of teams").fill("2");
  await form.getByRole("textbox", { name: "Name prefix", exact: true }).fill(PREFIX);
  await button(form, "Generate teams").click();
  const confirm = page.getByRole("dialog");
  await expect(confirm).toContainText('Generate 2 teams named "Squad 01"');
  await button(confirm, "Confirm").click();

  const result = page.getByRole("dialog", { name: "Team sign-ins" });
  await expect(result).toBeVisible();
  await expect(result.getByText("These passwords are shown only once.", { exact: false })).toBeVisible();
  const rows = result.locator("tbody tr");
  await expect(rows).toHaveCount(2);
  await expect(button(result, "Copy all")).toBeVisible();
  await expect(button(result, "Download CSV")).toBeVisible();
  const cells = rows.first().locator("th, td");
  const credential = {
    teamName: (await cells.nth(0).innerText()).trim(),
    email: (await cells.nth(2).innerText()).trim(),
    password: (await cells.nth(3).innerText()).trim(),
  };
  expect(credential.password).toMatch(/^[A-Za-z0-9]{16}$/);
  await button(result, "Close").click();
  await expect(result).toBeHidden();
  await expect(page.locator(`[data-team="${credential.teamName}"]`)).toBeVisible();
  return credential;
}

async function expectSentToLogin(page: Page, notice: string) {
  await expect(page).toHaveURL(/\/login$/, NEXT_POLL);
  await expect(page.getByRole("alert")).toHaveText(notice);
}

async function signOutTeam(page: Page, teamName: string) {
  await page.reload();
  const row = page.locator(`[data-team="${teamName}"]`);
  await expect(row.getByText("Signed in", { exact: true })).toBeVisible();
  await expect(row.getByText(/last seen/)).toBeVisible();
  await button(row, "Sign out team").click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(`Sign out ${teamName}?`);
  await button(dialog, "Confirm").click();
  await expect(page.getByText(`${teamName} was signed out.`)).toBeVisible();
  await expect(row.getByText("Not signed in", { exact: true })).toBeVisible();
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test("teams: planned count, generated sign-ins, one device per team and organizer sign-out", async ({ browser }) => {
  const admin = await openRolePage(browser, "admin", DESKTOP);
  const deviceA = await openRolePage(browser, "device A", PHONE);
  const deviceB = await openRolePage(browser, "device B", PHONE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  try {
    const planned = (await activeTeamCount(adminApi)) + 2;
    await signIn(admin.page, accounts.adminEmail, accounts.adminPassword);
    await test.step("admin sets the planned team count", () => setPlannedTeams(admin.page, planned));
    const team = await test.step("admin generates two teams and sees the one-time sign-ins", () =>
      generateTwoTeams(admin.page),
    );
    await test.step("the team signs in on device A", async () => {
      await signIn(deviceA.page, team.email, team.password);
      await expect(deviceA.page.getByRole("heading", { level: 1, name: team.teamName })).toBeVisible();
    });
    await test.step("signing in on device B sends device A to the login page", async () => {
      await signIn(deviceB.page, team.email, team.password);
      await expectSentToLogin(deviceA.page, ANOTHER_DEVICE);
      await deviceA.page.screenshot({ path: path.join(docs, "event-another-device.png") });
    });
    await test.step("admin sees the team signed in and signs it out", () => signOutTeam(admin.page, team.teamName));
    await test.step("device B is sent to the login page with the organizer message", () =>
      expectSentToLogin(deviceB.page, ORGANIZER),
    );
  } finally {
    await adminApi.dispose();
    await Promise.all([admin, deviceA, deviceB].map((role) => role.page.context().close()));
  }
  // The rejected requests of the signed-out devices are expected 401 responses, not page problems.
  const unexpected = [...admin.problems, ...deviceA.problems, ...deviceB.problems].filter(
    (problem) => !problem.includes("status of 401"),
  );
  expect(unexpected).toEqual([]);
});
