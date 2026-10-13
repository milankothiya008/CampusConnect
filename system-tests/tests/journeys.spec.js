import { expect, test } from "@playwright/test";
import { ADMIN, createAccount, signIn, tokenFor } from "./support.js";

const bearer = (token) => ({ Authorization: `Bearer ${token}` });

test.describe("journeys", () => {
    test("the admin's dashboard loads its overview", async ({ page }) => {
        await signIn(page, ADMIN);
        await page.goto("/dashboard");
        await expect(page.getByText("Awaiting final approval")).toBeVisible();
        await expect(page.getByText("Recently created clubs")).toBeVisible();
    });

    test("a direct message arrives live, whichever API replica handles it", async ({ page, request }) => {
        const sender = await createAccount(request, { name: "Sender Shah" });
        const receiver = await createAccount(request, { name: "Receiver Rao" });
        const senderToken = await tokenFor(request, sender);
        const login = await request.post("/api/auth/login", { data: { email: receiver.email, password: receiver.password } });
        const receiverId = (await login.json()).data.user._id;

        const opened = await request.post("/api/chat/direct", { data: { userId: receiverId }, headers: bearer(senderToken) });
        expect(opened.status(), await opened.text()).toBe(200);
        const conversationId = (await opened.json()).data._id;

        await signIn(page, receiver);
        await page.goto(`/messages/${conversationId}`);
        await expect(page.getByRole("textbox", { name: "Message" })).toBeVisible();

        // Nginx balances by least connections, so these sends usually land on the replica the receiver's socket
        // isn't on, and can only reach it through the RabbitMQ relay.
        for (const text of ["Hi from the first replica", "And hi from the second"]) {
            const sent = await request.post(`/api/chat/conversations/${conversationId}/messages`, { data: { text }, headers: bearer(senderToken) });
            expect(sent.status(), await sent.text()).toBe(200);
            await expect(page.locator(".chat-thread").getByText(text)).toBeVisible();
        }

        // The reply goes the other way through the UI.
        await page.getByRole("textbox", { name: "Message" }).fill("Got both, thanks!");
        await page.getByRole("button", { name: "Send", exact: true }).click();
        await expect
            .poll(async () => {
                const thread = await request.get(`/api/chat/conversations/${conversationId}/messages`, { headers: bearer(senderToken) });
                return (await thread.json()).data.items.map((message) => message.text);
            })
            .toContain("Got both, thanks!");
    });
});
