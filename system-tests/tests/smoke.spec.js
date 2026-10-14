import { expect, test } from "@playwright/test";

// The edge (Nginx) in front of the API replicas: routing, caching and security headers.
test.describe("edge", () => {
    test("the app shell is served with security headers and no long-term cache", async ({ request }) => {
        const response = await request.get("/");
        expect(response.status()).toBe(200);
        const headers = response.headers();
        expect(headers["content-security-policy"]).toContain("default-src 'self'");
        // No inline scripts at all (the theme bootstrap is /theme.js): scripts only from the app's own origin.
        expect(/script-src ([^;]*)/.exec(headers["content-security-policy"])[1].trim()).toBe("'self'");
        expect(headers["x-frame-options"]).toBe("DENY");
        expect(headers["x-content-type-options"]).toBe("nosniff");
        expect(headers["cache-control"]).toBe("no-cache");
    });

    test("fingerprinted assets are cached for a year and compressed", async ({ request }) => {
        const html = await (await request.get("/")).text();
        const asset = /src="(\/assets\/[^"]+\.js)"/.exec(html)[1];
        const response = await request.get(asset, { headers: { "Accept-Encoding": "gzip" } });
        expect(response.status()).toBe(200);
        expect(response.headers()["cache-control"]).toContain("immutable");
        expect(response.headers()["content-encoding"]).toBe("gzip");
    });

    test("client-side routes fall back to the app, unknown API routes get the API's 404", async ({ request }) => {
        expect((await request.get("/clubs/some/deep/link")).status()).toBe(200);
        const api = await request.get("/api/does-not-exist");
        expect(api.status()).toBe(404);
        expect((await api.json()).message).toBe("Cannot find GET /api/does-not-exist on this server");
    });

    test("the API answers through the proxy and internals stay hidden", async ({ request }) => {
        const health = await request.get("/api/health");
        expect(health.status()).toBe(200);
        expect((await health.json()).data.emailDelivery).toBe("smtp");
        expect((await request.get("/actuator/health")).status()).toBe(404);
        expect((await request.get("/.csp-hashes")).status()).toBe(403);
    });
});
