import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  // Each spec resets the rehearsal backend before every test (resetRehearsal), so order and repeats don't matter.
  testMatch: [
    "event-clues.spec.ts",
    "event-live.spec.ts",
    "event-overrides.spec.ts",
    "event-teams.spec.ts",
    "event.spec.ts",
  ],
  timeout: 300000,
  workers: 1,
  // Screens refetch when the pushed revision changes (polling every 3 s if the stream is down); the live spec
  // asserts the push latency with its own 1.5-second timeouts.
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
