import { defineConfig, devices } from "@playwright/test";

// End-to-end tests against the Docker stack started with `npm run stack:up` (see README.md).
export default defineConfig({
    testDir: "./tests",
    timeout: 60_000,
    expect: { timeout: 10_000 },
    fullyParallel: true,
    // Each worker drives a browser while the same machine usually runs the whole stack: keep it moderate (--workers=N).
    workers: process.env.CI ? 2 : 3,
    retries: process.env.CI ? 1 : 0,
    reporter: [["list"], ["html", { open: "never" }]],
    use: {
        baseURL: process.env.BASE_URL || "http://localhost:8088",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
        video: "retain-on-failure"
    },
    projects: [
        { name: "desktop-chrome", use: { ...devices["Desktop Chrome"] } },
        { name: "mobile-chrome", use: { ...devices["Pixel 7"] } }
    ]
});
