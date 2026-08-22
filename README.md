# Tether

A headless **WhatsApp multi-device companion client for Android**, packaged as an SDK. Your app
pairs as a linked device by QR scan — exactly like WhatsApp Web — then observes decrypted
messages on that connection. The phone stays the primary device.

The protocol layers are a Kotlin port of what [whatsmeow](https://github.com/tulir/whatsmeow) (Go)
and Baileys (JS) do. See **[docs/WA_MODULE_OVERVIEW.md](docs/WA_MODULE_OVERVIEW.md)** for the
layer-by-layer breakdown and roadmap, and **[docs/whatsapp-native.md](docs/whatsapp-native.md)**
for protocol notes.

## Modules

| Module          | Artifact                      | What it is |
| --------------- | ----------------------------- | ---------- |
| `:tether-core`  | `space.pitchstone:tether-core` | The SDK. Transport, Noise handshake, binary XML codec, Signal decryption, pairing, session and persistence. No UI. |
| `:tether-ui`    | `space.pitchstone:tether-ui`   | Optional Compose surface: QR pairing screen, connection status, observed-message list. Depends on `:tether-core`. |
| `:app`          | —                              | Sample app. Hosts `WhatsAppScreen` in `MainActivity`; not published. |

Package root is `space.pitchstone.tether`.

## Using it

```kotlin
dependencies {
    implementation("space.pitchstone:tether-core:0.1.0")
    // Optional — only if you want the ready-made Compose screen.
    implementation("space.pitchstone:tether-ui:0.1.0")
}
```

Create one `WhatsAppManager` per process, `start()` it, and either drive it yourself from
`manager.state` or hand it to `WhatsAppScreen`:

```kotlin
val manager = WhatsAppManager(
    context = application,
    onMessages = { received -> /* your pipeline */ },
).apply { start() }

setContent { WhatsAppScreen(manager) }
```

`WhatsAppManager.Config` carries the host-tunable knobs — `databaseName`, `logTag`,
`notificationTitle`, and `deviceName`, the name the user sees for this device in WhatsApp's
**Linked Devices** list (default `"Tether"`). `deviceName` travels in the pairing payload only,
so it is fixed when the device is linked; changing it takes effect on the next pairing.

`:tether-core` contributes its own `INTERNET`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS` permissions plus the foreground service
via manifest merging — a consumer declares nothing. On API 33+ the host app still has to *request*
`POST_NOTIFICATIONS` at runtime; `MainActivity` in `:app` shows how.

`minSdk` is 26 for both artifacts.

## Building

```
./gradlew :tether-core:assembleRelease :tether-ui:assembleRelease   # the AARs
./gradlew :app:assembleDebug                                        # the sample app
./gradlew test                                                      # unit tests
```

Needs JDK 21 and Android SDK platform 37 (`sdkmanager "platforms;android-37.0"`).

### Build setup note

Wire (protobuf codegen) and KSP (Room) both require the standalone Kotlin Gradle plugin, which
AGP 9's built-in Kotlin support does not satisfy — and that plugin in turn refuses to load against
AGP 9's new DSL. So `gradle.properties` sets `android.builtInKotlin=false` and
`android.newDsl=false`, and every module applies `kotlin-android` explicitly with the classic
`android { }` DSL. Revisit once Wire and KSP support built-in Kotlin.

## Publishing

Both artifacts publish to GitHub Packages, versioned by `tether.version` in `gradle.properties`
so they never drift apart. Put credentials in `~/.gradle/gradle.properties` (`gpr.user` /
`gpr.key`, token needs `write:packages`) or set `GITHUB_ACTOR` / `GITHUB_TOKEN`:

```
./gradlew publishAllPublicationsToGitHubPackagesRepository
```

> **Licence:** the POMs declare Apache-2.0 but the repo carries no `LICENSE` file yet. Add one (or
> change the POM declarations) before publishing.
