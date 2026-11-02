import { Browser, Locator, Page, expect } from "@playwright/test";

export const DESKTOP = { width: 1280, height: 900 };
export const PHONE = { width: 390, height: 844 };

export interface RolePage {
  page: Page;
  problems: string[];
}

// One browser context per role keeps each sign-in alive (sessions live in sessionStorage per tab).
export async function openRolePage(
  browser: Browser,
  label: string,
  viewport: { width: number; height: number },
): Promise<RolePage> {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const problems: string[] = [];
  page.on("pageerror", (error) => problems.push(`${label} page error: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") problems.push(`${label} console error: ${message.text()}`);
  });
  return { page, problems };
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
}

export async function clickAndConfirm(page: Page, button: Locator) {
  await button.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "Confirm", exact: true }).click();
  await expect(dialog).toBeHidden();
}

// For actions where the UI contract leaves a confirmation dialog optional.
export async function clickConfirmingIfAsked(page: Page, button: Locator, outcome: Locator) {
  await button.click();
  const confirm = page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true });
  await expect(confirm.or(outcome).first()).toBeVisible();
  if (await confirm.isVisible()) await confirm.click();
  await expect(outcome).toBeVisible();
}

export async function expectFitsPhone(page: Page) {
  const restore = page.viewportSize();
  await page.setViewportSize(PHONE);
  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(scrollWidth, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(PHONE.width + 1);
  if (restore) await page.setViewportSize(restore);
}

export async function screenshotPhone(page: Page, file: string) {
  const restore = page.viewportSize();
  await page.setViewportSize(PHONE);
  await page.screenshot({ path: file, fullPage: true });
  if (restore) await page.setViewportSize(restore);
}

// Option labels may carry extra text (descriptions, minutes), so options are chosen by contained text.
export async function selectOptionContaining(select: Locator, text: string) {
  const option = select.locator("option", { hasText: text });
  const index = await option.evaluate((element) => (element as HTMLOptionElement).index);
  await select.selectOption({ index });
}
