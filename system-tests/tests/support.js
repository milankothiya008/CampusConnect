// Helpers shared by the system tests: unique accounts, verification codes from Mailpit, API shortcuts.
import { expect } from "@playwright/test";

export const MAILPIT = process.env.MAILPIT_URL || "http://localhost:8025";
export const DOMAIN = process.env.UNIVERSITY_DOMAIN || "ddu.ac.in";
export const PASSWORD = "Secret#2026";
export const ADMIN = { email: process.env.ADMIN_EMAIL || `admin@${DOMAIN}`, password: process.env.ADMIN_PASSWORD || "SystemTest#2026" };

const letters = (count) => Array.from({ length: count }, () => String.fromCharCode(97 + Math.floor(Math.random() * 26))).join("");
const digits = (count) => Array.from({ length: count }, () => Math.floor(Math.random() * 10)).join("");

/** A fresh student address in the university format (batch 24, CE). */
export const studentEmail = () => `24ce${letters(3)}${digits(3)}@${DOMAIN}`;
/** A fresh faculty address (first name . department). */
export const facultyEmail = () => `${letters(8)}.ce@${DOMAIN}`;

/** The 6-digit code of the newest CampusConnect e-mail to this address (waits for it to arrive). */
export async function codeFor(request, email, { after = 0 } = {}) {
    let code = null;
    await expect
        .poll(
            async () => {
                const response = await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`);
                const { messages = [] } = await response.json();
                const fresh = messages.filter((message) => new Date(message.Created).getTime() >= after);
                const match = fresh.map((message) => /^(\d{6}) is your CampusConnect/.exec(message.Subject)).find(Boolean);
                code = match ? match[1] : null;
                return code;
            },
            { timeout: 30_000, intervals: [250, 500, 1000] }
        )
        .not.toBeNull();
    return code;
}

/** Registers and verifies an account through the API (fast set-up for tests about other features). */
export async function createAccount(request, { name = "Test User", email = studentEmail(), accountType = "STUDENT" } = {}) {
    const started = Date.now() - 1000;
    const registered = await request.post("/api/auth/register", { data: { name, email, password: PASSWORD, accountType, phone: "98765 43210" } });
    expect(registered.status(), await registered.text()).toBe(201);
    const code = await codeFor(request, email, { after: started });
    const verified = await request.post("/api/auth/verify-email", { data: { email, code } });
    expect(verified.status(), await verified.text()).toBe(200);
    return { name, email, password: PASSWORD };
}

/** Signs in through the API; returns the access token. */
export async function tokenFor(request, account) {
    const response = await request.post("/api/auth/login", { data: { email: account.email, password: account.password } });
    expect(response.status(), await response.text()).toBe(200);
    return (await response.json()).data.accessToken;
}

/** Signs a browser page in through the UI. */
export async function signIn(page, account) {
    await page.goto("/login");
    await page.getByLabel("University email").fill(account.email);
    await page.getByLabel("Password").fill(account.password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/feed/);
}
