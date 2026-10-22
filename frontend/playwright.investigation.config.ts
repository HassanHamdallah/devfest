import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  testMatch: "investigation.spec.ts",
  timeout: 120000,
  workers: 1,
  use: {
    baseURL: "http://localhost:4202",
    browserName: "chromium",
    channel: "msedge",
    headless: true,
    trace: "retain-on-failure",
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "../docs/investigation-browser-results.json" }],
  ],
});
