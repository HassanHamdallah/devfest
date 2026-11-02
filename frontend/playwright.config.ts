import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  // The event specs need the event rehearsal stack; they run via playwright.event.config.ts.
  testIgnore: ["event.spec.ts", "event-teams.spec.ts"],
  timeout: 180000,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:4201",
    browserName: "chromium",
    channel: "msedge",
    headless: true,
    trace: "off",
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "../docs/browser-results.json" }],
  ],
});
