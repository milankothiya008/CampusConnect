// A stand-in for @stomp/stompjs, for tests: vi.mock("@stomp/stompjs", () => import("../test/fakeStomp")).
// The test plays the server: open() completes a (re)connect the way the library does (beforeConnect first),
// push() delivers a frame on the latest subscription, drop() and refuse() end the connection.

export const clients = [];

export const ReconnectionTimeMode = { LINEAR: 0, EXPONENTIAL: 1 };

export class Client {
    constructor(config) {
        Object.assign(this, config);
        this.connected = false;
        this.active = false;
        this.published = [];
        this.subscriptions = [];
        clients.push(this);
    }

    activate() {
        this.active = true;
    }

    deactivate() {
        this.active = false;
        this.connected = false;
        return Promise.resolve();
    }

    publish(frame) {
        if (!this.connected) {
            throw new TypeError("There is no underlying STOMP connection");
        }
        this.published.push({ ...frame, body: JSON.parse(frame.body) });
    }

    subscribe(destination, callback) {
        this.subscriptions.push({ destination, callback });
        return { id: `sub-${this.subscriptions.length}`, unsubscribe: () => {} };
    }

    async open() {
        await this.beforeConnect(this);
        this.connected = true;
        this.onConnect({ command: "CONNECTED", headers: {} });
    }

    push(body) {
        this.subscriptions.at(-1).callback({ body: typeof body === "string" ? body : JSON.stringify(body) });
    }

    drop() {
        this.connected = false;
        this.onWebSocketClose({ code: 1006 });
    }

    refuse(message) {
        this.onStompError({ command: "ERROR", headers: { message }, body: "" });
        this.connected = false;
        this.onWebSocketClose({ code: 1002 });
    }
}
