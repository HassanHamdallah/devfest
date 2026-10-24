import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  testMatch: "event.spec.ts",
  timeout: 300000,
  workers: 1,
  // Screens refetch when the 10-second revision poll sees a change, so assertions may wait one poll.
  expect: { timeout: 15000 },
  use: {
    baseURL: "http://localhost:4202",
    browserName: "chromium",
    channel: "msedge",
    headless: true,
    trace: "retain-on-failure",
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "../docs/event-browser-results.json" }],
  ],
});
