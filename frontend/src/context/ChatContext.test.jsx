import { useEffect, useState } from "react";
import { act, screen, waitFor } from "@testing-library/react";
import { authValue, renderWithRouter } from "../test/renderWithProviders";
import { ChatProvider, useChat } from "./ChatContext";
import { useAuth } from "./AuthContext";
import { chatApi } from "../api/endpoints";
import { setAccessToken } from "../api/client";
import { clients } from "../test/fakeStomp";

// The real chat adapter (lib/chatSocket) over a fake STOMP client: the provider is unchanged from the
// Socket.IO days and must keep working on the new connection.
vi.mock("./AuthContext", () => ({ useAuth: vi.fn() }));
vi.mock("../api/endpoints", () => ({ chatApi: { unread: vi.fn(), settings: vi.fn(), updateSettings: vi.fn() } }));
vi.mock("@stomp/stompjs", () => import("../test/fakeStomp"));

const Status = () => {
    const { connected, presence, watchPresence } = useChat();
    useEffect(() => watchPresence(["u9"]), [watchPresence]);
    return (
        <p>
            {connected ? "Live" : "Offline"} · {presence.u9 ? (presence.u9.online ? "Dr Mentor is online" : "Dr Mentor is away") : "No presence yet"}
        </p>
    );
};

// Pages are lazy: they mount after the shell (and the provider's connection) — before the socket is up.
const Page = ({ children }) => {
    const [ready, setReady] = useState(false);
    useEffect(() => setReady(true), []);
    return ready ? children : null;
};

const sent = (client, destination) => client.published.filter((frame) => frame.destination === destination).map((frame) => frame.body);

describe("ChatProvider on STOMP", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        clients.length = 0;
        setAccessToken("token-1");
        useAuth.mockReturnValue(authValue({ user: { _id: "u1", name: "Asha Patel" } }));
        chatApi.unread.mockResolvedValue({ data: { chats: 0, messages: 0 } });
        chatApi.settings.mockResolvedValue({ data: { showActivityStatus: true, chatNotifications: true } });
    });

    afterEach(() => setAccessToken(null));

    test("reports what's on screen, confirms delivery, shows banners and presence, and follows the connection", async () => {
        renderWithRouter(
            <ChatProvider>
                <Page>
                    <Status />
                </Page>
            </ChatProvider>,
            { route: "/feed", path: "/feed" }
        );
        await waitFor(() => expect(clients).toHaveLength(1));
        const client = clients[0];
        expect(screen.getByText(/Offline · No presence yet/)).toBeInTheDocument();

        await act(() => client.open());
        expect(client.connectHeaders).toEqual({ Authorization: "Bearer token-1" });
        expect(await screen.findByText(/^Live/)).toBeInTheDocument();
        // The presence question asked before the connection was up went out once it was.
        const [watch] = sent(client, "/app/presence.watch");
        expect(watch).toEqual({ ids: ["u9"], requestId: expect.any(String) });
        expect(sent(client, "/app/viewing")).toContainEqual({ conversationId: null, visible: true });

        act(() =>
            client.push({ event: "presence:snapshot", payload: { requestId: watch.requestId, states: [{ userId: "u9", online: true, lastSeenAt: null }] } })
        );
        expect(await screen.findByText(/Dr Mentor is online/)).toBeInTheDocument();
        act(() => client.push({ event: "presence", payload: { userId: "u9", online: false, lastSeenAt: new Date().toISOString() } }));
        expect(await screen.findByText(/Dr Mentor is away/)).toBeInTheDocument();

        act(() =>
            client.push({
                event: "message:new",
                payload: {
                    conversationId: "c1",
                    message: { _id: "m1", type: "TEXT", text: "See you at 5", sender: { _id: "u9", name: "Dr Mentor" }, attachments: [] },
                    conversation: { _id: "c1", type: "DIRECT", title: "Dr Mentor" },
                    mutedFor: []
                }
            })
        );
        expect(await screen.findByText("See you at 5")).toBeInTheDocument();
        expect(sent(client, "/app/delivered")).toEqual([{ conversationId: "c1" }]);

        act(() => client.drop());
        expect(await screen.findByText(/^Offline/)).toBeInTheDocument();
    });
});
