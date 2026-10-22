import { test, expect } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
const root = path.resolve("..");
const account = JSON.parse(
  fs.readFileSync(path.join(root, ".local/verification-account.json"), "utf8"),
);
const config = Object.fromEntries(
  fs
    .readFileSync(path.join(root, "backend/.env"), "utf8")
    .split(/\r?\n/)
    .filter((x) => x.includes("=") && !x.startsWith("#"))
    .map((x) => {
      const i = x.indexOf("=");
      return [x.slice(0, i), x.slice(i + 1)];
    }),
);
async function code(page: import("@playwright/test").Page, source: string) {
  await page.locator(".monaco-editor").click();
  await page.keyboard.press("Control+a");
  await page.keyboard.insertText(source);
}
async function source(page: import("@playwright/test").Page) {
  return page.evaluate(() => {
    const m = (
      window as unknown as {
        monaco: { editor: { getModels(): { getValue(): string }[] } };
      }
    ).monaco;
    return m?.editor?.getModels()[0]?.getValue() ?? null;
  });
}
test("participant: login, Monaco, refresh/offline/conflict recovery, languages, real Run and Submit", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByLabel("Email", { exact: true }).fill(account.email);
  await page.getByLabel("Password", { exact: true }).fill(account.password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", {
      name: "DevFest Programming Contest",
      exact: true,
    }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/participant-contest.png",
    fullPage: true,
  });
  await page.getByRole("link", { name: "Explore problems" }).click();
  await page
    .getByRole("link", { name: "A · Two Numbers", exact: true })
    .click();
  await expect(page.locator(".monaco-editor")).toBeVisible();
  const saved =
    "# browser recovery " +
    Date.now() +
    "\nprint(sum(map(int,input().split())))\n";
  await code(page, saved);
  await expect(
    page.getByRole("status").filter({ hasText: /^Saved$/ }),
  ).toBeVisible();
  await page.reload();
  await expect.poll(() => source(page)).toBe(saved);
  const offline = saved + "# offline edit\n";
  await page.route("**/api/v1/drafts/**", async (route) => {
    if (route.request().method() === "PUT") await route.abort("failed");
    else await route.continue();
  });
  await code(page, offline);
  await expect(
    page.getByText("Save failed. Local changes kept; retrying…"),
  ).toBeVisible();
  await page.reload();
  await expect.poll(() => source(page)).toBe(offline);
  await page.unroute("**/api/v1/drafts/**");
  await expect(
    page.getByRole("status").filter({ hasText: /^Saved$/ }),
  ).toBeVisible({ timeout: 15000 });
  // A second device updates the same draft, then the browser must ask rather than overwrite it.
  const token = await page.evaluate(() =>
    sessionStorage.getItem("devfest.access"),
  );
  const headers = { Authorization: "Bearer " + token };
  const d = await (
    await page.request.get("/api/v1/drafts/1/PYTHON", { headers })
  ).json();
  await page.request.put("/api/v1/drafts/1/PYTHON", {
    headers,
    data: { source: "# second device\n", version: d.version },
  });
  await code(page, offline + "# local conflict\n");
  await expect(
    page.getByText(
      "A different server draft exists. Choose which version to keep.",
    ),
  ).toBeVisible();
  await page.getByRole("button", { name: "Save my local version" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: /^Saved$/ }),
  ).toBeVisible();
  const python = await source(page);
  await page.getByLabel("Language", { exact: true }).selectOption("CPP");
  await expect.poll(() => source(page)).not.toBe(python);
  await page.getByLabel("Language", { exact: true }).selectOption("PYTHON");
  await expect.poll(() => source(page)).toBe(python);
  await code(page, "print(sum(map(int,input().split())))\n");
  await page.getByLabel("Custom input", { exact: true }).fill("10 20\n");
  await page.getByRole("button", { name: "▷ Run", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Run completed" }),
  ).toBeVisible({ timeout: 90000 });
  await expect(page.locator(".result pre").first()).toHaveText("30\n");
  await page
    .getByRole("button", { name: "Submit solution →", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "ACCEPTED", exact: true }),
  ).toBeVisible({ timeout: 120000 });
  await page.screenshot({
    path: "../docs/participant-workspace.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../docs/participant-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Sign in", exact: true }),
  ).toBeVisible();
});
test("administrator navigation and forms", async ({ page }) => {
  await page.goto("/login");
  await page
    .getByLabel("Email", { exact: true })
    .fill(config.DEVFEST_ADMIN_EMAIL);
  await page
    .getByLabel("Password", { exact: true })
    .fill(config.DEVFEST_ADMIN_PASSWORD);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page
    .getByRole("navigation", { name: "Admin navigation" })
    .getByRole("link", { name: "Participants", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Create participant" }),
  ).toBeVisible();
  await page
    .getByRole("navigation", { name: "Admin navigation" })
    .getByRole("link", { name: "Problems", exact: true })
    .click();
  await page.getByRole("button", { name: "Edit A · Two Numbers" }).click();
  await expect(
    page.getByLabel("Hidden tests (JSON array, up to 20)"),
  ).toHaveValue(/2 3/);
  await page.screenshot({ path: "../docs/admin-problem.png", fullPage: true });
  await page
    .getByRole("navigation", { name: "Admin navigation" })
    .getByRole("link", { name: "Contest Settings", exact: true })
    .click();
  await expect(page.getByLabel("Contest title", { exact: true })).toHaveValue(
    "DevFest Programming Contest",
  );
});
