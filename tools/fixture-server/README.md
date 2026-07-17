# Rebubble fixture server

Minimal BlueBubbles REST + socket.io stand-in for local Android development and Maestro E2E without a Mac.

## Run

```sh
cd tools/fixture-server
npm install
node server.js
```

Listens on **port 12346** (`PORT` env overrides). Password defaults to `fixture-pass` (`FIXTURE_PASSWORD`).

## Emulator networking

The Android emulator reaches the host machine at `10.0.2.2`. In onboarding, use:

- Server address: `http://10.0.2.2:12346`
- Password: `fixture-pass`

## Scenario flags

| Env | Effect |
|---|---|
| `FAIL_FIRST_SEND=1` | First outbound text send fails with HTTP 500 enough times to exhaust the app's WorkManager auto-retries (4), then succeeds — so the UI shows **Not sent — tap to retry**, and a manual retry works. |
| `FIXTURE_PASSWORD=…` | Auth password for `?guid=` and socket handshake. |
| `PORT=…` | Listen port (default `12346`). |

## Test hooks

- `POST /_fixture/receive` body `{ "chatGuid"?: string, "text"?: string }` — stores an incoming message and emits socket `new-message`.
- `POST /_fixture/reset` — reseeds chats/messages and resets fail counters.

Default DM chat guid: `iMessage;-;+15550100001`  
Group chat: `iMessage;+;chatfixturefriends` (display name **Fixture Friends**).

## Smoke

```sh
curl -s "http://127.0.0.1:12346/api/v1/server/info?guid=fixture-pass"
curl -s "http://127.0.0.1:12346/api/v1/server/info"   # expect 401
curl -s -X POST "http://127.0.0.1:12346/api/v1/chat/query?guid=fixture-pass" -H 'Content-Type: application/json' -d '{}'
curl -s -X POST "http://127.0.0.1:12346/_fixture/receive" -H 'Content-Type: application/json' -d '{"text":"hi"}'
```
