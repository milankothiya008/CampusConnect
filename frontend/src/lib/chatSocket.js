import { expireSession, getAccessToken, refreshSession } from "../api/client";

// The live chat connection: STOMP over a native WebSocket (docs/REALTIME.md), behind the small Socket.IO-like
// surface the app is written against — on/off/emit, `connected`, connect()/disconnect() and the pseudo-events
// "connect", "disconnect" and "connect_error". The server pushes every event on one subscription as
// { event, payload } frames with the same names and payloads the Socket.IO events had.
//
// The socket lives on the page's own origin (/ws, proxied by Vite in development and Nginx in production);
// VITE_WS_URL overrides it.

const EVENTS = "/user/queue/events";
const DESTINATIONS = {
    typing: "/app/typing",
    delivered: "/app/delivered",
    viewing: "/app/viewing",
    "presence:watch": "/app/presence.watch"
};
const HEARTBEAT_MS = 10000;
const RECONNECT_MIN_MS = 1000;
const RECONNECT_MAX_MS = 8000;
const ACK_TIMEOUT_MS = 5000;
// Frames sent while the connection is down wait for it (like Socket.IO's buffer), up to this many.
const QUEUE_LIMIT = 50;
const WATCH_LIMIT = 500;

/** ws(s)://<this page's host>/ws, or VITE_WS_URL — a path, or a full ws(s):// or http(s):// address. */
export const chatUrl = (configured = import.meta.env.VITE_WS_URL) => {
    const url = new URL(configured || "/ws", window.location.href);
    url.protocol = url.protocol === "https:" || url.protocol === "wss:" ? "wss:" : "ws:";
    return url.href;
};

const reportError = (error) =>
    queueMicrotask(() => {
        throw error;
    });

const chatError = (message, code = null) => Object.assign(new Error(message), { data: { code } });

const createChat = () => {
    const handlers = new Map();
    const acks = new Map();
    // Everyone whose presence was asked for: asked again after a reconnect.
    const watched = new Set();
    // Request ids are unique across tabs: the server may answer every tab of the same person.
    const prefix = Math.random().toString(36).slice(2, 10);
    let counter = 0;
    let queue = [];
    let client = null;
    let loading = null;
    let retryTimer = null;
    let wanted = false;
    let connected = false;
    let everConnected = false;
    let refreshing = null;

    const dispatch = (event, payload) => {
        // A copy: handlers may remove themselves while running.
        [...(handlers.get(event) || [])].forEach((handler) => {
            try {
                handler(payload);
            } catch (error) {
                // One broken screen must not stop the others from hearing the event.
                reportError(error);
            }
        });
    };

    const nextRequestId = () => `${prefix}-${++counter}`;

    const publish = (destination, payload) => {
        const body = payload ?? {};
        if (connected && client?.connected) {
            try {
                client.publish({ destination, body: JSON.stringify(body), headers: { "content-type": "application/json" } });
                return;
            } catch {
                // The connection dropped just now: keep the frame for the next one.
            }
        }
        queue.push({ destination, body });
        if (queue.length > QUEUE_LIMIT) {
            queue.shift();
        }
    };

    const flush = () => {
        const waiting = queue;
        queue = [];
        waiting.forEach(({ destination, body }) => publish(destination, body));
    };

    const settle = (requestId, states) => {
        const pending = acks.get(requestId);
        if (!pending) {
            return false;
        }
        acks.delete(requestId);
        clearTimeout(pending.timer);
        pending.ack(Array.isArray(states) ? states : []);
        return true;
    };

    const onFrame = (message) => {
        let frame;
        try {
            frame = JSON.parse(message.body);
        } catch {
            return;
        }
        if (!frame || typeof frame.event !== "string") {
            return;
        }
        const { event, payload } = frame;
        if (event === "presence:snapshot" && !settle(payload?.requestId, payload?.states)) {
            // An answer that came after its wait (or a re-watch after reconnecting) still says who's online.
            (Array.isArray(payload?.states) ? payload.states : []).forEach((state) => dispatch("presence", state));
        }
        dispatch(event, payload);
    };

    // The server refused the token (it expired): get a new one; the next attempt waits for it.
    const renewSession = () => {
        if (refreshing) {
            return;
        }
        refreshing = refreshSession()
            .catch((error) => {
                if (error?.status === 401 || error?.status === 403) {
                    // Signed out: stop retrying with a token that can't work; the app shows the sign-in page.
                    adapter.disconnect();
                    expireSession();
                }
                // Otherwise (offline, server busy) the usual back-off tries again.
            })
            .finally(() => {
                refreshing = null;
            });
    };

    const start = (Client, ReconnectionTimeMode) => {
        let attemptError = null;
        const stomp = new Client({
            brokerURL: chatUrl(),
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            reconnectDelay: RECONNECT_MIN_MS,
            maxReconnectDelay: RECONNECT_MAX_MS,
            reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
            // Read on every (re)connect: access tokens are short-lived.
            beforeConnect: async () => {
                if (refreshing) {
                    await refreshing;
                }
                const token = getAccessToken();
                stomp.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
            },
            onConnect: () => {
                if (client !== stomp) {
                    return;
                }
                stomp.subscribe(EVENTS, onFrame);
                connected = true;
                if (everConnected && watched.size) {
                    // Back after a drop: ask again who's online.
                    publish(DESTINATIONS["presence:watch"], { ids: [...watched], requestId: nextRequestId() });
                }
                everConnected = true;
                flush();
                dispatch("connect");
            },
            onStompError: (frame) => {
                if (client !== stomp) {
                    return;
                }
                const code = frame?.headers?.message || null;
                if (code === "UNAUTHORIZED") {
                    renewSession();
                }
                if (!connected) {
                    attemptError = chatError(code === "UNAUTHORIZED" ? "Authentication failed" : code || "Chat connection refused", code);
                }
            },
            onWebSocketClose: () => {
                if (client !== stomp) {
                    return;
                }
                if (connected) {
                    connected = false;
                    dispatch("disconnect", "transport close");
                } else {
                    dispatch("connect_error", attemptError || chatError("Can't reach the chat server"));
                }
                attemptError = null;
            }
        });
        client = stomp;
        stomp.activate();
    };

    // The STOMP library loads with the first connection, keeping it out of the app's first download.
    const open = () => {
        if (loading) {
            return;
        }
        loading = import("@stomp/stompjs")
            .then(({ Client, ReconnectionTimeMode }) => {
                if (wanted && !client) {
                    start(Client, ReconnectionTimeMode);
                }
            })
            .catch((error) => {
                dispatch("connect_error", chatError(error?.message || "Can't load the chat client"));
                retryTimer = setTimeout(() => wanted && !client && open(), RECONNECT_MAX_MS);
            })
            .finally(() => {
                loading = null;
            });
    };

    const adapter = {
        get connected() {
            return connected;
        },
        on(event, handler) {
            if (!handlers.has(event)) {
                handlers.set(event, new Set());
            }
            handlers.get(event).add(handler);
            return adapter;
        },
        off(event, handler) {
            if (handler) {
                handlers.get(event)?.delete(handler);
            } else {
                handlers.delete(event);
            }
            return adapter;
        },
        /** Sends a client event (see DESTINATIONS). presence:watch takes the ids and an ack(states) callback. */
        emit(event, payload, ack) {
            const destination = DESTINATIONS[event];
            if (!destination) {
                if (import.meta.env.DEV) {
                    console.warn(`chatSocket: no STOMP destination for "${event}"`);
                }
                return adapter;
            }
            if (event !== "presence:watch") {
                publish(destination, payload);
                return adapter;
            }
            const ids = Array.isArray(payload) ? payload : [];
            ids.forEach((id) => watched.add(id));
            [...watched].slice(0, Math.max(0, watched.size - WATCH_LIMIT)).forEach((id) => watched.delete(id));
            const requestId = nextRequestId();
            if (typeof ack === "function") {
                acks.set(requestId, { ack, timer: setTimeout(() => settle(requestId, []), ACK_TIMEOUT_MS) });
            }
            publish(destination, { ids, requestId });
            return adapter;
        },
        connect() {
            wanted = true;
            if (client) {
                client.activate();
            } else {
                open();
            }
            return adapter;
        },
        disconnect() {
            wanted = false;
            clearTimeout(retryTimer);
            queue = [];
            acks.forEach(({ timer }) => clearTimeout(timer));
            acks.clear();
            const stomp = client;
            const wasConnected = connected;
            client = null;
            connected = false;
            if (stomp) {
                void stomp.deactivate();
            }
            if (wasConnected) {
                dispatch("disconnect", "io client disconnect");
            }
            return adapter;
        }
    };
    return adapter;
};

let chat = null;

/** The shared chat connection for the signed-in user (created and connected on first use). */
export const connectChat = () => {
    if (!chat) {
        chat = createChat().connect();
    }
    return chat;
};

export const disconnectChat = () => {
    chat?.disconnect();
    chat = null;
};

export const chatSocket = () => chat;
