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

### What a message tells you

`Received` names the conversation as well as the message. `chatType` is a `ChatType` —
`DIRECT`, `GROUP`, `BROADCAST`, `NEWSLETTER` or `OTHER` — rather than an `isGroup` flag, so a
status update does not present as a one-to-one message. `chatName` carries a group's subject,
`senderName` the sender's WhatsApp push name, and `phone` their number:

```kotlin
when (received.chatType) {
    ChatType.GROUP  -> "${received.senderName ?: received.phone} in ${received.chatName}"
    ChatType.DIRECT -> received.senderName ?: received.phone
    else            -> received.chatJid
}
```

Both names are best-effort. A push name only exists once that person has announced one, and a
group's subject is fetched in the background rather than blocking delivery — so the first message
from a new group arrives with `chatName` null and is filled in, in `state.recent`, once the answer
lands. A subject *changed* after we learned it is not yet picked up.

### Attachments

A message with an attachment carries a `MediaInfo` — kind, mimetype, size, dimensions, and a
`thumbnail`: a small JPEG that rides *inside* the message, so a photo can be shown the moment it
arrives with no network at all. Captions come through as the message's `text`.

The full file is a separate HTTPS fetch from WhatsApp's CDN, decrypted on the way in:

```kotlin
val bytes = manager.downloadMedia(received.id)   // suspends; null if nothing to fetch
```

The media key never leaves the SDK — a host that wants to show a picture has no reason to hold the
key that decrypts it — so downloads go by message id. Refs are kept for the same window as
`state.recent`, so anything still on screen is still fetchable. Integrity is checked before the
bytes are returned (encrypted-blob SHA-256, a truncated HMAC over `iv || ciphertext`, then the
plaintext SHA-256); a file that does not verify throws rather than returning.

Images and stickers render in the sample UI. Video, audio and documents are described and can be
downloaded, but are not displayed.

### Staying connected

A companion socket does not stay up: once it goes quiet, the server, a NAT or the carrier reaps
it, which surfaces as a TLS `close_notify`. The SDK treats that as routine and redials — 1s
doubling to a 60s cap, reset whenever a session was actually established, and woken early when
connectivity returns. Nothing is required of the host; `state.status` reports what it is doing,
and `manager.reconnect()` skips the remaining backoff if you want a manual control (the Compose
screen shows one when disconnected).

Two ends stop the loop rather than retrying: `logout()`, and the server reporting the device is
no longer linked (`401` — someone removed it from Linked Devices), where retrying could only spin.

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

## Licensing

Tether is licensed under the **GNU General Public License v3** (`LICENSE`).

That follows from what it links rather than from a preference. `tether-core` depends on
`org.whispersystems:signal-protocol-java` and `curve25519-java`, both GPLv3. They are not in this
tree, but they are linked into any application built against Tether, so the distributed
combination carries GPLv3 obligations regardless of what this project called itself. Upgrading
does not avoid it — the modern `org.signal:libsignal-client` is AGPLv3, which is stricter.

**This is not LGPL.** An app that links Tether must itself be distributable under GPLv3. If that
does not suit your use, the blocker is the Signal libraries, not this project, and it would need a
Signal implementation under different terms.

The protocol layers are a port of [whatsmeow](https://github.com/tulir/whatsmeow) (MPL-2.0), and
`WAProto.proto` is vendored from it. MPL-2.0 § 3.3 allows a Larger Work to be distributed under a
Secondary License, and § 1.12 names the GNU GPL among them, so the ported code may be conveyed
under GPLv3 as long as the MPL's terms are still met for those files. `NOTICE` carries the
attribution that does so.
