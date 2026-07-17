# Rebubble

A native Android client for the BlueBubbles server — keep your server, replace the app.

Rebubble is an independent, native Android app (Kotlin + Jetpack Compose) that talks to
your existing [BlueBubbles](https://bluebubbles.app/) server. It is not affiliated with
the BlueBubbles project.

## Status: M1

Milestone 1 delivers a usable messaging client against a real BlueBubbles server:

- Onboarding (QR + manual URL/password)
- Chat list + conversation UI (text, photos, group events)
- Optimistic send with failed → tap-to-retry
- Socket foreground receive + reconcile watermark sync
- FCM bootstrap when the server provides `/fcm/client` (otherwise periodic sync + banner)
- Settings, sync status, send-failure notifications
- Fixture server + Maestro golden paths for emulator development without a Mac

## Requirements

- Android Studio (latest stable) or the command line with JDK 17+
- Android SDK with platform 36 and build-tools installed
- An existing BlueBubbles server to connect to — **or** the local fixture server below

## Building

```sh
./gradlew :app:assembleDebug
```

## Running tests

```sh
./gradlew :app:testDebugUnitTest
```

## Development without a Mac

You can develop and run golden-path UI flows against `tools/fixture-server` (Express + socket.io)
instead of a real BlueBubbles Mac server.

### 1. Start the fixture server (host)

```sh
cd tools/fixture-server
npm install
node server.js
```

Default: `http://0.0.0.0:12346`, password `fixture-pass`. See `tools/fixture-server/README.md`.

For the send-fail → retry Maestro flow:

```sh
FAIL_FIRST_SEND=1 node server.js
```

### 2. Point the app at the emulator loopback

On an Android emulator, the host machine is `10.0.2.2`:

- Server address: `http://10.0.2.2:12346`
- Password: `fixture-pass`

### 3. Maestro flows

With an emulator running and the fixture server up:

```sh
maestro test maestro/flow-onboard.yaml
maestro test maestro/flow-receive-reply.yaml   # after onboard; do not clear app state
FAIL_FIRST_SEND=1 node tools/fixture-server/server.js   # restart server with flag first
maestro test maestro/flow-send-fail-retry.yaml          # after onboard
```

`flow-receive-reply.yaml` posts to `http://127.0.0.1:12346/_fixture/receive` from the Maestro
host so the fixture emits a socket `new-message` the app can ingest.

## Manual verification against a real server

Run once against a real BlueBubbles Mac server before tagging an M1 release:

- [ ] QR onboard from the real server
- [ ] FCM cold delivery with the app killed
- [ ] Socket foreground receive
- [ ] Reconcile when FCM is revoked / unavailable
- [ ] Send text + photo
- [ ] Kill Wi-Fi mid-send → failed bubble → tap retry
- [ ] Group rename event renders
- [ ] `encrypt_coms` on → wake-signal fallback works

## Project structure

- `app/` — the single Android application module (`applicationId: app.rebubble.android`)
- `tools/fixture-server/` — BlueBubbles API emulator for local/Maestro use
- `maestro/` — golden-path UI flows
- `gradle/libs.versions.toml` — the Gradle version catalog for dependency versions

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
