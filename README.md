# Rebubble

A native Android client for the BlueBubbles server — keep your server, replace the app.

Rebubble is an independent, native Android app (Kotlin + Jetpack Compose) that talks to
your existing [BlueBubbles](https://bluebubbles.app/) server. It is not affiliated with
the BlueBubbles project.

## Requirements

- Android Studio (latest stable) or the command line with JDK 17+
- Android SDK with platform 36 and build-tools installed
- An existing BlueBubbles server to connect to

## Building

```sh
./gradlew :app:assembleDebug
```

## Running tests

```sh
./gradlew :app:testDebugUnitTest
```

## Project structure

- `app/` — the single Android application module (`applicationId: app.rebubble.android`)
- `gradle/libs.versions.toml` — the Gradle version catalog for dependency versions

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
