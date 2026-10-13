import { chatSocket, chatUrl, connectChat, disconnectChat } from "./chatSocket";
import { expireSession, getAccessToken, refreshSession } from "../api/client";
import { clients } from "../test/fakeStomp";

vi.mock("../api/client", () => ({ getAccessToken: vi.fn(), refreshSession: vi.fn(), expireSession: vi.fn() }));

vi.mock("@stomp/stompjs", () => import("../test/fakeStomp"));

// The STOMP library is loaded on first use.
const stomp = async () => {
    await vi.waitFor(() => expect(clients.length).toBeGreaterThan(0));
    return clients.at(-1);
};

const connected = async () => {
    const socket = connectChat();
    const client = await stomp();
    await client.open();
    return { socket, client };
};

describe("chat socket (STOMP)", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        clients.length = 0;
        getAccessToken.mockReturnValue("token-1");
        refreshSession.mockResolvedValue({ accessToken: "token-2" });
    });

    afterEach(() => {
        disconnectChat();
        vi.useRealTimers();
    });

    test("connects to /ws on this origin with a fresh token every time, 10 s heart-beats and a 1–8 s back-off", async () => {
        const { socket, client } = await connected();

        expect(client.brokerURL).toBe(`ws://${window.location.host}/ws`);
        expect(client).toMatchObject({
            heartbeatIncoming: 10000,
            heartbeatOutgoing: 10000,
            reconnectDelay: 1000,
            maxReconnectDelay: 8000,
            reconnectTimeMode: 1
        });
        expect(client.active).toBe(true);
        expect(client.connectHeaders).toEqual({ Authorization: "Bearer token-1" });
        expect(client.subscriptions.map((sub) => sub.destination)).toEqual(["/user/queue/events"]);
        expect(socket.connected).toBe(true);

        // The library reconnects by itself; each attempt reads the token again and subscribes again.
        client.drop();
        expect(socket.connected).toBe(false);
        getAccessToken.mockReturnValue("token-9");
        await client.open();
        expect(client.connectHeaders).toEqual({ Authorization: "Bearer token-9" });
        expect(client.subscriptions).toHaveLength(2);
    });

    test("server events reach their handlers, with connect and disconnect as pseudo-events", async () => {
        const socket = connectChat();
        const onConnect = vi.fn();
        const onDisconnect = vi.fn();
        const onMessage = vi.fn();
        const other = vi.fn();
        socket.on("connect", onConnect).on("disconnect", onDisconnect).on("message:new", onMessage).on("typing", other);

        const client = await stomp();
        await client.open();
        expect(onConnect).toHaveBeenCalledTimes(1);

        const payload = { conversationId: "c1", message: { _id: "m1", text: "Hi" }, mutedFor: [] };
        client.push({ event: "message:new", payload });
        expect(onMessage).toHaveBeenCalledWith(payload);
        expect(other).not.toHaveBeenCalled();

        // Junk is ignored.
        client.push("not json");
        client.push({ payload: {} });

        socket.off("message:new", onMessage);
        client.push({ event: "message:new", payload });
        expect(onMessage).toHaveBeenCalledTimes(1);

        client.drop();
        expect(onDisconnect).toHaveBeenCalledWith("transport close");
    });

    test("a handler that throws doesn't stop the others", async () => {
        const { socket, client } = await connected();
        const broken = vi.fn(() => {
            throw new Error("boom");
        });
        const fine = vi.fn();
        const thrown = [];
        const original = globalThis.queueMicrotask;
        globalThis.queueMicrotask = (run) => {
            try {
                run();
            } catch (error) {
                thrown.push(error.message);
            }
        };
        try {
            socket.on("receipt", broken).on("receipt", fine);
            client.push({ event: "receipt", payload: { conversationId: "c1" } });
        } finally {
            globalThis.queueMicrotask = original;
        }
        expect(fine).toHaveBeenCalledTimes(1);
        expect(thrown).toEqual(["boom"]);
    });

    test("emit sends each event to its destination; what is sent while offline waits for the connection", async () => {
        const socket = connectChat();
        socket.emit("typing", { conversationId: "c1", recording: false });
        const client = await stomp();
        expect(client.published).toEqual([]);

        await client.open();
        socket.emit("delivered", { conversationId: "c1" });
        socket.emit("viewing", { conversationId: null, visible: true });
        socket.emit("unknown:event", { x: 1 });

        expect(client.published.map(({ destination, body }) => [destination, body])).toEqual([
            ["/app/typing", { conversationId: "c1", recording: false }],
            ["/app/delivered", { conversationId: "c1" }],
            ["/app/viewing", { conversationId: null, visible: true }]
        ]);
        expect(client.published[0].headers).toEqual({ "content-type": "application/json" });
    });

    test("presence:watch sends the ids with a request id and acks with the matching snapshot", async () => {
        const { socket, client } = await connected();
        const ack = vi.fn();
        socket.emit("presence:watch", ["u1", "u2"], ack);

        const [{ destination, body }] = client.published;
        expect(destination).toBe("/app/presence.watch");
        expect(body).toEqual({ ids: ["u1", "u2"], requestId: expect.any(String) });

        const states = [
            { userId: "u1", online: true, lastSeenAt: null },
            { userId: "u2", hidden: true }
        ];
        client.push({ event: "presence:snapshot", payload: { requestId: "someone-else", states: [] } });
        expect(ack).not.toHaveBeenCalled();
        client.push({ event: "presence:snapshot", payload: { requestId: body.requestId, states } });
        expect(ack).toHaveBeenCalledWith(states);

        // Answered once.
        client.push({ event: "presence:snapshot", payload: { requestId: body.requestId, states } });
        expect(ack).toHaveBeenCalledTimes(1);
    });

    test("presence:watch acks [] after 5 s without an answer; a late answer still updates presence", async () => {
        const { socket, client } = await connected();
        vi.useFakeTimers();
        const ack = vi.fn();
        const presence = vi.fn();
        socket.on("presence", presence);
        socket.emit("presence:watch", ["u1"], ack);

        vi.advanceTimersByTime(4999);
        expect(ack).not.toHaveBeenCalled();
        vi.advanceTimersByTime(1);
        expect(ack).toHaveBeenCalledWith([]);

        const state = { userId: "u1", online: false, lastSeenAt: "2026-10-13T10:00:00.000Z" };
        client.push({ event: "presence:snapshot", payload: { requestId: client.published[0].body.requestId, states: [state] } });
        expect(ack).toHaveBeenCalledTimes(1);
        expect(presence).toHaveBeenCalledWith(state);
    });

    test("after a reconnect it asks again who is online", async () => {
        const { socket, client } = await connected();
        socket.emit("presence:watch", ["u1", "u2"], () => {});
        client.drop();
        await client.open();
        expect(client.published.at(-1)).toMatchObject({ destination: "/app/presence.watch", body: { ids: ["u1", "u2"] } });
    });

    test("an UNAUTHORIZED refusal renews the session, and the next attempt waits for the new token", async () => {
        let renew;
        refreshSession.mockImplementation(() => new Promise((resolve) => (renew = resolve)));
        const socket = connectChat();
        const failed = vi.fn();
        socket.on("connect_error", failed);
        const client = await stomp();
        getAccessToken.mockReturnValue("expired");

        client.refuse("UNAUTHORIZED");
        expect(refreshSession).toHaveBeenCalledTimes(1);
        expect(failed).toHaveBeenCalledTimes(1);
        expect(failed.mock.calls[0][0].data.code).toBe("UNAUTHORIZED");
        expect(socket.connected).toBe(false);

        // The library's scheduled reconnect starts before the refresh is back: it waits for it.
        const attempt = client.open();
        await Promise.resolve();
        expect(client.connected).toBe(false);
        getAccessToken.mockReturnValue("token-2");
        renew({ accessToken: "token-2" });
        await attempt;

        expect(client.connectHeaders).toEqual({ Authorization: "Bearer token-2" });
        expect(socket.connected).toBe(true);
        expect(expireSession).not.toHaveBeenCalled();
    });

    test("when the session can't be renewed it stops reconnecting and signs out", async () => {
        refreshSession.mockRejectedValue(Object.assign(new Error("Refresh token has been revoked"), { status: 401 }));
        connectChat();
        const client = await stomp();

        client.refuse("UNAUTHORIZED");
        await vi.waitFor(() => expect(expireSession).toHaveBeenCalledTimes(1));
        expect(client.active).toBe(false);
    });

    test("other refusals and unreachable servers are reported as connect_error without a refresh", async () => {
        const socket = connectChat();
        const failed = vi.fn();
        socket.on("connect_error", failed);
        const client = await stomp();

        client.refuse("Broker unavailable");
        client.drop();
        expect(failed.mock.calls.map(([error]) => error.message)).toEqual(["Broker unavailable", "Can't reach the chat server"]);
        expect(refreshSession).not.toHaveBeenCalled();
    });

    test("connectChat() shares one connection; disconnectChat() closes it and the next call starts afresh", async () => {
        const { socket, client } = await connected();
        const onDisconnect = vi.fn();
        socket.on("disconnect", onDisconnect);

        expect(connectChat()).toBe(socket);
        expect(chatSocket()).toBe(socket);

        disconnectChat();
        expect(client.active).toBe(false);
        expect(onDisconnect).toHaveBeenCalledWith("io client disconnect");
        expect(socket.connected).toBe(false);
        expect(chatSocket()).toBeNull();

        const next = connectChat();
        expect(next).not.toBe(socket);
        await vi.waitFor(() => expect(clients).toHaveLength(2));
        expect(clients[1].active).toBe(true);
    });

    test("VITE_WS_URL may be a path or a full address", () => {
        const host = window.location.host;
        expect(chatUrl("")).toBe(`ws://${host}/ws`);
        expect(chatUrl("/realtime")).toBe(`ws://${host}/realtime`);
        expect(chatUrl("https://chat.example.edu/ws")).toBe("wss://chat.example.edu/ws");
        expect(chatUrl("http://localhost:8080/ws")).toBe("ws://localhost:8080/ws");
        expect(chatUrl("wss://chat.example.edu/ws")).toBe("wss://chat.example.edu/ws");
    });
});
