import { expect, test } from "@playwright/test";
import { PASSWORD, codeFor, createAccount, signIn, studentEmail } from "./support.js";

test.describe("accounts", () => {
    test("a student signs up, confirms the e-mailed code and signs in", async ({ page, request }) => {
        const email = studentEmail();
        const started = Date.now() - 1000;

        // The three wizard steps: role, details, then password and review.
        await page.goto("/register");
        await page.getByRole("radio", { name: /Student/ }).click();
        await page.getByRole("button", { name: "Continue" }).click();
        await page.getByLabel("Full name").fill("Asha Patel");
        await page.getByLabel("Student email").fill(email);
        await page.getByLabel("Mobile number").fill("98765 43210");
        await page.getByRole("button", { name: "Continue" }).click();
        await page.getByRole("textbox", { name: /^Password\*?$/ }).fill(PASSWORD);
        await page.getByRole("textbox", { name: /^Confirm password\*?$/ }).fill(PASSWORD);
        await page.getByRole("button", { name: /Create student account/ }).click();

        // The code comes by e-mail (Mailpit catches it); it is never shown by the API.
        const code = await codeFor(request, email, { after: started });
        await page.getByLabel("Digit 1 of 6").fill(code[0]);
        for (let i = 1; i < 6; i += 1) {
            await page.getByLabel(`Digit ${i + 1} of 6`).fill(code[i]);
        }
        await expect(page.getByRole("heading", { name: "Email verified" })).toBeVisible();

        await signIn(page, { email, password: PASSWORD });
    });

    test("a wrong password is refused with the generic message", async ({ page, request }) => {
        const account = await createAccount(request);
        await page.goto("/login");
        await page.getByLabel("University email").fill(account.email);
        await page.getByLabel("Password").fill("Wrong#2026");
        await page.getByRole("button", { name: "Sign in" }).click();
        await expect(page.getByText("Invalid email or password")).toBeVisible();
    });

    test("a session survives a reload through the refresh cookie", async ({ page, request }) => {
        const account = await createAccount(request);
        await signIn(page, account);
        await page.reload();
        await expect(page).toHaveURL(/\/feed/);
        await expect(page.getByRole("button", { name: "Sign in" })).toHaveCount(0);
    });
});
