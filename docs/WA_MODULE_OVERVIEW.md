# Tether Modules (`:tether-core`, `:tether-ui`) — Overview & Roadmap

A native WhatsApp Web client written in Kotlin — the same thing libraries like
[whatsmeow](https://github.com/tulir/whatsmeow) (Go) or Baileys (JS) do; the protocol
layers here are largely a faithful port of whatsmeow. The phone stays the "primary"
device; this app pairs as a **linked device** via QR scan, exactly like WhatsApp Web
in a browser.

## What's implemented (layers, bottom to top)

Each layer below names the files that hold it and the invariants that are easy to break.

### 1. Transport — `net/`

`OkHttpFrameTransport` is a port of whatsmeow's `FrameSocket`. Frames are a 3-byte
big-endian length prefix plus payload, sent as binary WebSocket messages to
`wss://web.whatsapp.com/ws/chat`. The 4-byte `WA_CONN_HEADER` (`'W','A',6,3`) is
prepended exactly once, before the first frame — guarded by an `AtomicBoolean`, because
sending it twice desynchronises the server's parser.

`processData` reassembles: one WebSocket message may carry several frames, one frame may
span several messages, and a length prefix itself may be split across messages (hence
`partialHeader`). Read timeout is 0 — this connection is meant to stay open.

`FrameTransport` is an interface so the handshake can be driven over a fake in tests.

### 2. Noise handshake — `noise/`, `crypto/`

WhatsApp encrypts the *connection* separately from messages, using
`Noise_XX_25519_AESGCM_SHA256`. `NoiseHandshakeState` is the symmetric state (running
hash `h`, chaining salt `ck`, AEAD key, nonce counter); `NoiseHandshake.perform` drives
the XX pattern:

1. send `ClientHello(e)`
2. receive `ServerHello(e, encrypted static, encrypted cert)`
3. mix `DH(e_c, e_s)` → decrypt server static → mix `DH(e_c, s_s)` → decrypt and **verify** cert
4. encrypt client static → mix `DH(s_c, e_s)` → encrypt the `ClientPayload`
5. send `ClientFinish` → `split()` into independent read/write transport keys

Ordering here is load-bearing and unforgiving: every encrypt/decrypt uses the *current*
hash as AEAD associated data and then mixes its own ciphertext back into that hash, so a
single reordered step diverges the transcript and the server simply drops you.
`mixIntoKey` resets the nonce counter to 0; `NoiseTransport` (post-handshake) uses two
independent monotonic counters and no associated data.

The nonce is 12 bytes, all zero but for a big-endian uint32 counter in the last 4
(`generateIv`). `NOISE_START_PATTERN` is the pattern string plus 4 NUL bytes — exactly 32
bytes, used directly as the initial hash rather than being hashed.

### 3. Binary XML codec — `binary/`

WhatsApp sends XML-like `Node`s (tag, attrs, content) compressed against token
dictionaries — `WaTokens.SINGLE` plus four `DOUBLE` dictionaries, ~1200 lines generated
verbatim from whatsmeow. `BinaryEncoder`/`BinaryDecoder` handle the tag codes in `Tags`:
list sizes, 8/20/32-bit binary, dictionary references, four JID encodings (pair, AD, FB,
interop), and nibble/hex packing for numeric strings.

`WaBinary.unmarshal` strips the leading flags byte and zlib-inflates when bit 1 is set;
`marshal` emits that byte itself, so its output is ready to encrypt and send.

`Jid` carries `user`, `server`, `device`, `agent`, `integrator`. The `isLid` distinction
matters everywhere downstream: a LID is an opaque privacy id, **not** a phone number.
`readAdJid` derives the server from the agent byte for exactly this reason — hardcoding
`s.whatsapp.net` made LIDs read as callable phone numbers.

### 4. QR pairing and auth — `auth/`, `client/`

`DeviceCredentials.generate()` mints the long-lived identity once: a Noise static key, a
device identity key, a signed pre-key (XEdDSA signature over `0x05 || pub`), a 14-bit
registration id, and a 32-byte ADV secret.

`ClientPayloadFactory` builds the payload encrypted in handshake step 4 — a *registration*
payload when unpaired (`passive=false`, carries the device keys) or a *login* payload once
`deviceJid` is known (`passive=true`, `pull=true`). `WA_VERSION` is version-sensitive: if
the server starts refusing logins, bump it to the current web client version first.

Pairing proper lives in `WAClient`: `handlePairDevice` turns the server's refs into QR
strings (`ref,noisePub,identityPub,advSecret`, rotated as each expires), and
`handlePairSuccess` → `handlePair` runs the ADV cryptography in `AdvSignatures` —
verify the HMAC over the details with the shared ADV secret, verify the *primary
device's* signature over `{6,0} || details || ourIdentityPub`, then add our own device
signature over `{6,1} || details || ourIdentityPub || accountKey`, ACK, and persist.
Skipping the verification steps would mean accepting an identity the user's phone never
signed.

### 5. Signal end-to-end encryption — `signal/`

Message bodies are Signal-encrypted; `MessageDecryptor` handles `pkmsg` (X3DH session
setup), `msg` (Double Ratchet) and `skmsg` (group SenderKey) via libsignal, with
`WaSignalStore` persisting sessions and keys through `KeyValueStore`. `SignalKeys` adapts
raw Curve25519 keys into libsignal's types (public keys carry the leading `0x05`).

Two subtleties worth keeping in mind:

- **Ordering.** A sender-key distribution message rides inside the DM-level `pkmsg`/`msg`
  of the same `<message>` node as the group `skmsg` it unlocks. The non-group parts are
  therefore decrypted *first*; get this backwards and every group message fails.
- **LID resolution.** `LidDirectory` records every (LID, phone) pair seen on any stanza,
  because the server supplies the mapping inconsistently — as a `*_pn` attribute on
  whichever stanza happens to mention that party, not necessarily the one you need. Our
  own account is seeded explicitly at login, since no single stanza ever pairs our LID
  with our own number.

Decryption failures are collected rather than thrown, so `WAClient` can send a retry
receipt instead of losing the message.

### 6. Session and lifecycle — `session/`, `client/`

`WAClient` (~730 lines) is the orchestrator: connect, Noise handshake, then a read loop
that unmarshals each decrypted frame and routes by tag — `iq`, `success`, `message`,
`receipt`, `notification`, `call`, `failure`, `stream:error`. It also owns keepalive
pings, delivery and retry receipts, pre-key upload and top-up (a
`<notification type="encrypt">` means the pool is running low), and logout.

`WhatsAppManager` is the public API: a `StateFlow<State>` for the UI, an `onMessages`
suspend callback for the host, `start()` / `connect()` / `logout()` / `annotate()`, a Room
database for keys and credentials, and `WaForegroundService` so Android does not kill the
socket. Incoming batches go through a buffered `Channel` consumed by a single worker, so
`onMessages` is called sequentially and in order.

The SDK never interprets message meaning — that stays in the host app, behind the
callback.

### 7. `:tether-ui`

A single Compose screen: QR display with ref rotation, connection status, observed-message
list, and logout. It takes a `WhatsAppManager` and nothing else — no DI container, no host
app types.

## How hard was this?

Genuinely hard — one of the harder client-protocol projects you can attempt. Nothing
here is documented by WhatsApp; it's all reverse-engineered knowledge (via whatsmeow).
Getting it wrong at *any* layer — one wrong byte in the handshake, one mis-ordered
decrypt — means silent failures. The trickiest parts are already solved: the Noise
handshake, the pairing cryptography, and group decryption ordering (the sender-key
distribution message must be installed before the group `skmsg` in the same packet
is attempted).

## Next tasks

Roughly in priority order:

- [ ] **Sending messages** — the biggest gap: the client is currently receive-only.
      Needs the encrypt side of Signal: fetch recipients' pre-keys, encrypt per-device,
      sender-key distribution for groups.
- [x] **Server cert verification** — *done.* `WaCertVerifier` implements the full
      whatsmeow check (pinned-key signature on the intermediate, issuer serial, leaf
      signature, leaf key vs. the decrypted server static) and `WAClient` passes it to the
      handshake. Only `NoiseHandshake`'s default parameter is still `CertVerifier.Noop`,
      and nothing in the SDK uses that default.
- [ ] **Media** — download + decrypt of images, voice notes, documents (media has its
      own encryption keys plus a CDN download flow).
- [ ] **History sync** — currently only messages arriving while connected are seen;
      no import of existing chats.
- [ ] **Smaller items** — presence/read receipts beyond delivery acks, contact and
      group metadata, app-state sync (archived, pinned, muted, etc.).
