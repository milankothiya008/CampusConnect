# Real-time protocol (STOMP over WebSocket)

Replaces the original Socket.IO channel. Event names and payloads are **identical** to the original
Socket.IO events, so React components that call `on(event, handler)` / `emit(event, payload)` keep working.

## Connection

* URL: `ws(s)://<host>/ws` — native WebSocket, STOMP 1.2 (`@stomp/stompjs` on the client).
  Nginx proxies `/ws` to the API replicas; Vite proxies `/ws` (with `ws: true`) in development.
  `VITE_WS_URL` overrides the URL.
* `CONNECT` headers: `Authorization: Bearer <access token>`. A fresh token is read on every (re)connect.
* Heart-beats: 10 000 ms both ways. Client reconnect delay: 1–8 s back-off.
* Authentication failure: the server answers with an `ERROR` frame whose `message` header is
  `UNAUTHORIZED`. The client then calls `refreshSession()` and reconnects; if the refresh fails the user is
  signed out (the app already redirects to the login page).

## Server → client

The client subscribes **once** to `/user/queue/events`. Every frame body is JSON:

```json
{ "event": "message:new", "payload": { "...": "..." } }
```

| event | payload (same as the original Socket.IO event) |
|---|---|
| `message:new` | `{ conversationId, message, conversation?, mutedFor? }` |
| `message:updated` | `{ conversationId, messageId, patch }` |
| `receipt` | `{ conversationId, userId, readAt?, deliveredAt? }` |
| `typing` | `{ conversationId, user: { _id, name }, recording }` |
| `presence` | `{ userId, online, lastSeenAt }` or `{ userId, hidden: true }` |
| `presence:snapshot` | `{ requestId, states: [presence…] }` — reply to `presence:watch` |
| `conversation:read` | `{ conversationId }` |
| `conversation:removed` | `{ conversationId }` |
| `conversation:muted` | `{ conversationId, mutedUntil }` |
| `conversation:updated` | `{ conversationId }` |
| `election:updated` | `{ electionId }` |

## Client → server (`SEND`)

| destination | body | replaces Socket.IO |
|---|---|---|
| `/app/typing` | `{ conversationId, recording? }` | `emit("typing")` |
| `/app/delivered` | `{ conversationId }` | `emit("delivered")` |
| `/app/viewing` | `{ conversationId \| null, visible }` | `emit("viewing")` |
| `/app/presence.watch` | `{ ids: [userId…], requestId }` | `emit("presence:watch", ids, ack)` — the server replies with a `presence:snapshot` event carrying the same `requestId`, and afterwards pushes `presence` events for the watched users |

## Client adapter contract (`frontend/src/lib/chatSocket.js`)

`connectChat()` returns a singleton object with the Socket.IO-like surface the app already uses:

* `on(event, handler)`, `off(event, handler)` — includes the pseudo-events `connect`, `disconnect`,
  `connect_error`.
* `emit(event, payload, ack?)` — maps the event name to the destination table above; for
  `presence:watch` the `ack` callback receives the `states` array from the matching `presence:snapshot`
  (or `[]` after 5 s).
* `connected` (boolean), `connect()`, `disconnect()`.

`disconnectChat()` and `chatSocket()` keep their current meaning.

## Delivery guarantees

Events go only to users with a live connection; nothing is queued for later. A client therefore treats every
`connect` (the first one included) as "something may have been missed": the open chat reloads its latest page
of messages and merges it by `_id`, the chat list reloads, and the unread badge is refetched. Handlers
registered with `useChat().on()` are kept by `ChatProvider` and attached to whichever socket exists, so a
component that subscribes before the socket is created (child effects run before the provider's) still
receives events.

## Scaling

The API uses Spring's STOMP **broker relay** to RabbitMQ (`rabbitmq_stomp` plugin), so a message for user
X is delivered by whichever replica holds X's connection (`userDestinationBroadcast` /
`userRegistryBroadcast`). Presence counts (open tabs per user), presence watchers and "who is viewing which
chat" are kept in Redis with TTLs, so they survive replica restarts and are shared by all replicas.
Tests and single-node development may use the in-memory simple broker (`app.realtime.broker=simple`).
