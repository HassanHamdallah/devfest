import { test, expect, Page } from "@playwright/test";
import fs from "node:fs";
const accounts = JSON.parse(
  fs.readFileSync("../.local/investigation-rehearsal.json", "utf8"),
);
async function login(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
}
async function logout(page: Page) {
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Sign in", exact: true }),
  ).toBeVisible();
}
test("three-role investigation: drafts, ambiguous submission retry, locked final, scoring and publication", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page, accounts.adminEmail, accounts.adminPassword);
  await page
    .getByRole("link", { name: "Rooms and teams", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Organizer preflight", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Confirm these rules", exact: true })
    .click();
  await expect(
    page.getByText("Rules confirmed.", { exact: true }),
  ).toBeVisible();
  await page
    .getByLabel("Account name", { exact: true })
    .fill("Browser rehearsal member");
  await page
    .getByLabel("Account email", { exact: true })
    .fill("browser-extra@investigation.test");
  await page
    .getByLabel("Initial password (12–72 bytes)", { exact: true })
    .fill(accounts.seedPassword);
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await expect(
    page.getByText("Account created.", { exact: true }),
  ).toBeVisible();
  await logout(page);
  await login(page, accounts.managerEmail, accounts.seedPassword);
  await page
    .getByRole("link", { name: "Open room →", exact: true })
    .first()
    .click();
  // Router navigation is asynchronous; capture the URL only after it lands on the room.
  await page.waitForURL(/\/investigation\/rooms\/\d+$/);
  const roomUrl = page.url();
  for (const title of ["Development ANSWER", "Development FINAL"]) {
    const card = page
      .locator("ui-card")
      .filter({ has: page.getByRole("heading", { name: title, exact: true }) });
    await card
      .getByRole("button", { name: "Edit challenge", exact: true })
      .click();
    await page
      .getByLabel("Challenge state", { exact: true })
      .selectOption("OPEN");
    await page
      .getByLabel("Opening time (with timezone)", { exact: true })
      .fill(new Date(Date.now() - 3600000).toISOString());
    await page
      .getByLabel("Closing time (with timezone)", { exact: true })
      .fill(new Date(Date.now() + 3600000).toISOString());
    await page
      .getByRole("button", { name: "Save challenge", exact: true })
      .click();
    await expect(
      page.getByText(
        "Challenge saved. Results are private until republished.",
        { exact: true },
      ),
    ).toBeVisible();
  }
  await logout(page);
  await login(page, accounts.memberEmail, accounts.seedPassword);
  await expect(
    page.getByRole("link", { name: "Open room →", exact: true }),
  ).toHaveCount(1);
  await page.getByRole("link", { name: "Open room →", exact: true }).click();
  await page
    .locator("ui-card")
    .filter({
      has: page.getByRole("heading", {
        name: "Development ANSWER",
        exact: true,
      }),
    })
    .getByRole("link", { name: "Open challenge →", exact: true })
    .click();
  await page
    .getByLabel("Answer", { exact: true })
    .fill("The witness account contradicts the timeline.");
  await expect(
    page.getByRole("status").filter({ hasText: /Saved on server/ }),
  ).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("Answer", { exact: true })).toHaveValue(
    "The witness account contradicts the timeline.",
  );
  let acceptedId = "";
  await page.route(
    "**/investigation/challenges/*/submissions",
    async (route) => {
      const response = await route.fetch();
      expect(response.ok()).toBeTruthy();
      acceptedId = (await response.json()).id;
      await route.abort("failed");
    },
  );
  await page
    .getByRole("button", { name: "Submit answer", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Retry saved submission", exact: true }),
  ).toBeEnabled();
  await page.unroute("**/investigation/challenges/*/submissions");
  await page.reload();
  await page
    .getByRole("button", { name: "Retry saved submission", exact: true })
    .click();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "Submission received: " + acceptedId }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Submit answer", exact: true }),
  ).toBeDisabled();
  await expect(page.locator("ui-card .receipt")).toHaveCount(1);
  await page.getByRole("link", { name: "← Back to room", exact: true }).click();
  await page
    .locator("ui-card")
    .filter({
      has: page.getByRole("heading", {
        name: "Development FINAL",
        exact: true,
      }),
    })
    .getByRole("link", { name: "Open challenge →", exact: true })
    .click();
  await page
    .getByLabel("Conclusion", { exact: true })
    .fill("The original sequence is inconsistent.");
  await page
    .getByLabel("Reasoning", { exact: true })
    .fill("Compare the two timestamps.");
  await page
    .getByLabel("Supporting evidence", { exact: true })
    .fill("Evidence A, page 2.");
  await expect(
    page.getByRole("status").filter({ hasText: /Saved on server/ }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByLabel("Supporting evidence", { exact: true }),
  ).toHaveValue("Evidence A, page 2.");
  await page
    .getByRole("button", { name: "Submit and lock final report", exact: true })
    .click();
  await expect(
    page.getByText(
      "Your final report is submitted and locked. The accepted version is in the history below.",
      { exact: true },
    ),
  ).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("Conclusion", { exact: true })).toBeDisabled();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: "../docs/investigation-member-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await logout(page);
  await login(page, accounts.managerEmail, accounts.seedPassword);
  await page.goto(roomUrl);
  for (const [index, points] of [
    [0, "60"],
    [1, "80"],
  ] as const) {
    await page
      .getByRole("button", { name: "Review submission", exact: true })
      .nth(index)
      .click();
    await page.getByLabel("Awarded points", { exact: true }).fill(points);
    await page
      .getByLabel("Reviewer feedback", { exact: true })
      .fill("Evidence checked.");
    await page.getByRole("button", { name: "Save score", exact: true }).click();
    await expect(
      page.getByText("Score saved. Results are private until republished.", {
        exact: true,
      }),
    ).toBeVisible();
  }
  await expect(
    page.getByRole("cell", { name: "140", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/investigation-manager.png",
    fullPage: true,
  });
  await logout(page);
  await login(page, accounts.memberEmail, accounts.seedPassword);
  await page.goto(roomUrl);
  await expect(
    page.getByText("Results are private until the organizer publishes them.", {
      exact: true,
    }),
  ).toBeVisible();
  await logout(page);
  await login(page, accounts.adminEmail, accounts.adminPassword);
  await page.goto("/investigation");
  await page
    .getByRole("button", { name: "Publish results", exact: true })
    .click();
  await expect(
    page.getByText("Publication updated.", { exact: true }),
  ).toBeVisible();
  await logout(page);
  await login(page, accounts.memberEmail, accounts.seedPassword);
  await page.goto(roomUrl);
  await expect(
    page.getByRole("cell", { name: "140", exact: true }),
  ).toBeVisible();
  expect(errors).toEqual([]);
  fs.writeFileSync(
    "../.local/investigation-receipt.json",
    JSON.stringify({ id: acceptedId, roomUrl }),
  );
});
