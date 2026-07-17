# Rebubble — Native Android Client for the BlueBubbles Server

**Date:** 2026-07-16
**Status:** Approved design

## Problem

The official BlueBubbles Android app is a cross-platform Flutter codebase (Android, Windows, Linux, macOS, Web from one ~93% Dart tree). This produces two chronic problems:

1. **UI**: A lowest-common-denominator interface with iOS/Material "skin" toggles that never feels like a first-class Android app — no real Material You, janky transitions, dated interaction patterns.
2. **Reliability**: Documented history of high CPU, typing lag, and multi-GB memory use in media-heavy chats (upstream issues #2766, #2972); notification delivery chained through Firebase relay + socket.io with silent-miss failure modes; slow release cadence (~213 open issues, last major release Nov 2024).

The macOS **server** component is the solid part of the ecosystem: it exposes a REST API (password auth via query param) plus webhooks and socket.io events. The client is the weak part.

## Decision

Build **Rebubble** (name chosen 2026-07-16; applicationId `app.rebubble.android`): a clean-room **Kotlin + Jetpack Compose** Android app that talks to an **unmodified BlueBubbles macOS server**. Not a fork of the Flutter code; not based on OpenBubbles/rustpush (which removes the Mac relay but inherits the Flutter UI and carries Apple-impersonation breakage/ban risk).

- **Audience:** public open-source project from day one (Apache-2.0, matching upstream). Pitch: *"Keep your server, replace the app."*
- **Push strategy:** FCM (user's own Firebase project, exactly as the server supports today) + reconciliation sync fallback so no message is ever silently missed.

## Design language

Material 3 / Material You **foundation** — dynamic color, canonical Android navigation, predictive back, responsive layouts (phone, foldable, tablet panes). This is **not** an iOS clone: iOS elements are sprinkled in deliberately where they aid iMessage familiarity (e.g., blue/green bubble semantics, tapback visual vocabulary, message-effect renditions), but structure, motion, and controls are Android-native. The app should have its own identity: responsive, fast, beautiful. Performance is a design feature — 60fps scrolling in media-heavy chats is a hard requirement, not an aspiration.

## Architecture

Standard modern-Android layered architecture, offline-first:

### Data layer
- **Room** database is the single source of truth: chats, messages, handles, attachments.
- The UI reads **only** from Room (Flow-based queries → Compose state). The network never feeds the UI directly. This decoupling is the core fix for upstream's jank, which couples network state to UI state.
- Attachments cached on disk with size-bounded eviction; media decoded via Coil with downsampling (the fix for upstream's multi-GB memory blowups).

### Network layer
- **Retrofit/OkHttp** for the BlueBubbles REST API (auth: `?guid=<password>`, per upstream docs and Postman collection).
- **socket.io client** for realtime events while the app is foregrounded.

### Sync engine (the heart of the app)
Three inbound paths, all converging on Room:
1. **FCM push** — user's own Firebase project, as the server already supports.
2. **socket.io events** — while the app is open.
3. **Reconciliation sync** — on app open, on socket reconnect, and on a periodic WorkManager tick, query REST for messages newer than the last-known row per chat. Guarantees eventual delivery even when FCM drops. This is the fix for the #1 reliability complaint.

Idempotent ingestion keyed on message GUID so the three paths never duplicate rows.

### Outbox (sending)
- Optimistic insert with a temp GUID → background send with WorkManager retry → GUID swap on server ack.
- Failed sends are visible (failed-state bubble, tap-to-retry). No "did it send?" ambiguity.

## Feature phasing

- **M1 — Core:** onboarding (server URL + password + QR-scan setup, as the existing ecosystem uses), conversation list, chat view, send/receive text + attachments, FCM + reconciliation notifications with inline reply and messaging-style notifications.
- **M2 — Feels like iMessage:** Private API features — tapbacks, replies/threads, typing indicators, read receipts, message effects, edit/unsend — plus group management (rename, add/remove participants, group icons, mentions).
- **M3 — Rich:** link previews, per-chat media gallery, full-text search (Room FTS), GIFs/stickers.
- **M4 — Extras:** FaceTime notifications/links, scheduled messages, Find My, Wear OS companion.

Each milestone is shippable on its own.

## Error handling & compatibility

- Server version detection at connect; capability-gate features (Private API surfaces hidden when the server lacks them).
- Every network failure surfaces in a visible sync-status indicator; no silent failure states.
- Structured, exportable logs for community bug reports (public-project requirement).

## Testing

- **Unit tests** on the sync engine and outbox — the correctness-critical core (idempotency, reconciliation windows, retry/backoff).
- **Room migration tests** from v1 of the schema onward.
- **API contract tests** against documented server responses (upstream Postman collection as fixture source).
- **Maestro UI flows** for golden paths: onboard → receive → reply; send-failure → retry.

## Non-goals (v1)

- No SMS/RCS handling (iMessage relay only).
- No desktop/web client.
- No rustpush/Mac-less mode — revisit only if the community demands it and legal/breakage risk is acceptable.

## References

- Upstream app: https://github.com/BlueBubblesApp/bluebubbles-app
- Server API docs: https://docs.bluebubbles.app/server/developer-guides/rest-api-and-webhooks
- OpenBubbles (considered, rejected as base): https://github.com/OpenBubbles/openbubbles-app
- Upstream performance issues: bluebubbles-app#2766, #2972
