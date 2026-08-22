package space.pitchstone.tether.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.Room
import space.pitchstone.tether.WaLog
import space.pitchstone.tether.binary.ChatType
import space.pitchstone.tether.client.WAClient
import space.pitchstone.tether.media.MediaDownloader
import space.pitchstone.tether.media.MediaInfo
import space.pitchstone.tether.media.MediaRef
import space.pitchstone.tether.signal.MessageDecryptor
import space.pitchstone.tether.store.ChatNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Owner of the native WhatsApp connection: builds the Room-backed stores, runs the [WAClient] on
 * a background scope (via [WaForegroundService]), tracks what has been observed, and exposes
 * connection state (incl. QR codes) for the UI.
 *
 * This is the whole "login and message observing" surface — QR pairing, connect/reconnect,
 * logout, and a running log of messages seen on the connection — with no dependency on any
 * particular app's pipeline. A host app hands [onMessages] a callback to do something with
 * decrypted messages (feed them into its own agent/pipeline, store them, whatever); it can later
 * call [annotate] to attach a note to a message already shown (e.g. what its pipeline decided),
 * without this module needing to know what that pipeline is.
 */
class WhatsAppManager(
    context: Context,
    private val config: Config = Config(),
    private val onMessages: suspend (List<Received>) -> Unit = {},
) : AutoCloseable {

    /**
     * Host-tunable knobs. Defaults suit a single-WhatsApp app; a host that already owns a file
     * named `wa.db`, or that wants its own log tag in a shared logcat, overrides them here.
     */
    data class Config(
        val databaseName: String = "wa.db",
        val logTag: String = "WhatsApp",
        /** Title on the ongoing foreground-service notification. */
        val notificationTitle: String = "WhatsApp Connection",
        /**
         * What the user sees for this device in WhatsApp's Linked Devices list. Sent only in the
         * pairing payload, so it is fixed when the device is linked: changing it later takes
         * effect on the next pairing, not the next connect.
         */
        val deviceName: String = DEFAULT_DEVICE_NAME,
    )

    // Never hold the caller's Context: a host passing an Activity would have it pinned for the
    // process lifetime by the `active` reference below.
    private val appContext = context.applicationContext

    /**
     * One message seen on this connection.
     *
     * Deliberately a plain data class rather than the decrypted protobuf: the generated WAProto
     * types are an implementation detail of this module, and handing them to callers would put
     * 200+ generated classes and the Wire runtime into every consumer's compile classpath.
     */
    data class Received(
        val id: String,
        /** The other party in the chat: who sent it, or who we sent it to. */
        val phone: String?,
        /** Message body, or null when the payload carries no text (media, reactions, …). */
        val text: String?,
        /** Which `Message` field carried the payload, e.g. `conversation`, `imageMessage`. */
        val kind: String,
        val timestampMillis: Long,
        /** True for messages we sent from another device, mirrored here. */
        val fromMe: Boolean,
        /** Sender JID, as text — for logging and correlation with the wire trace. */
        val senderJid: String,
        /** Chat JID, as text: the peer for a DM, the group for a group message. */
        val chatJid: String,
        /** Whether this arrived in a one-to-one chat, a group, a broadcast or a channel. */
        val chatType: ChatType,
        /**
         * The conversation's display name: a group's subject, or null for a direct chat, where
         * [senderName] and [phone] already name the other party.
         *
         * Also null on the first message from a group — the subject is fetched in the background
         * rather than blocking delivery, so it is filled in on this message in [recent] once the
         * answer lands, and carried by later messages from the same group.
         */
        val chatName: String?,
        /**
         * The sender's WhatsApp display name (their push name), when the server has told us one.
         * Null for our own messages, and for anyone who has never announced a name.
         */
        val senderName: String?,
        /**
         * The attachment on this message, if any: what it is, how big, and a free inline preview.
         * Fetch the full file with [downloadMedia] — the key that decrypts it stays inside the SDK.
         */
        val media: MediaInfo? = null,
        /** A host app's note about this message, attached later via [annotate]. */
        val annotation: Annotation? = null,
    )

    /** A host app's opaque note on a [Received] message — this module attaches no meaning to it. */
    data class Annotation(val label: String, val isError: Boolean = false)

    data class State(
        val status: String = "Not connected",
        val qrCodes: List<String> = emptyList(),
        val paired: Boolean = false,
        val connected: Boolean = false,
        /** True until we've checked persisted creds — lets the UI avoid flashing onboarding. */
        val initializing: Boolean = true,
        /** Persisted creds existed at launch (returning user) — skip onboarding straight away. */
        val alreadyLinked: Boolean = false,
        /** The linked device JID, shown on the WhatsApp screen so you know which account this is. */
        val deviceJid: String? = null,
        /**
         * One-shot: the first-run gate has been satisfied (linked or skipped). Latched, so logging
         * out from the WhatsApp tab leaves you in the app instead of bouncing to full-screen
         * onboarding mid-session.
         */
        val onboardingDone: Boolean = false,
        /**
         * Messages seen this session, newest first, capped at [MAX_RECENT]. In memory only — a
         * host app that wants durable storage persists what it needs from [onMessages] itself.
         */
        val recent: List<Received> = emptyList(),
    )

    // Lazy so constructing the manager stays free of I/O — the database is not touched until
    // something actually needs it, which keeps construction cheap and testable.
    private val dbDelegate = lazy {
        Room.databaseBuilder(appContext, WaDatabase::class.java, config.databaseName).build()
    }
    private val db by dbDelegate
    private val keyValueStore by lazy { RoomKeyValueStore(db.kvDao()) }
    private val names by lazy { ChatNames(keyValueStore) }
    private val credentialStore by lazy { RoomCredentialStore(db.credentialsDao()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val deliveries = Channel<List<Received>>(capacity = 64)

    @Volatile private var started = false

    init {
        scope.launch {
            for (batch in deliveries) {
                runCatching {
                    onMessages(batch)
                }.onFailure { Log.w(config.logTag, "host onMessages threw", it) }
            }
        }
    }

    /**
     * Bring the session up: read persisted credentials and, if this device is already linked,
     * reconnect. Idempotent, so a host can call it from `Application.onCreate` without guarding.
     *
     * Separate from construction on purpose. Doing this in `init` meant merely creating the
     * manager opened a database, launched a coroutine and published `this` to a global — before
     * the caller had asked for anything, and with `this` escaping mid-construction.
     */
    fun start() {
        if (started) return
        started = true
        WaLog.tag = config.logTag
        active = this
        // Resolve first-run vs. returning user: if a device JID was persisted at pairing, the
        // onboarding gate skips straight to the app and we bring the connection back up. A fresh
        // pairing (this session) is tracked via `paired`/`connected` so the onboarding screen can
        // stay visible until login actually completes.
        scope.launch {
            val jid = runCatching { credentialStore.load()?.deviceJid }.getOrNull()
            _state.update {
                it.copy(
                    initializing = false,
                    alreadyLinked = jid != null,
                    deviceJid = jid,
                    onboardingDone = it.onboardingDone || jid != null,
                    status = if (jid != null) "Linked" else it.status,
                )
            }
            if (jid != null) runCatching { connect() }
        }
    }

    /**
     * Release everything without touching the linked account — the session can be brought back up
     * with a fresh manager and [start]. This is the counterpart to [start]; [logout] is a
     * different operation that erases credentials.
     *
     * Without it the `active` reference kept the manager (and its database) alive for the process
     * lifetime with no way for a host to let go.
     */
    override fun close() {
        if (!started) return
        started = false
        stopService()
        deliveries.close()
        scope.cancel()
        // Only if something actually opened it — touching `db` here would build a database purely
        // in order to close it, which is exactly the construction-time I/O this change removed.
        if (dbDelegate.isInitialized()) runCatching { if (db.isOpen) db.close() }
        // Never clear a slot another instance has since claimed.
        if (active === this) active = null
    }

    private var client: WAClient? = null
    private var service: WaForegroundService? = null

    @Volatile private var running = false
    private val network = NetworkMonitor(appContext)

    /**
     * A nudge to stop waiting and redial now. Conflated: several taps of Reconnect are one retry,
     * and a nudge that arrives while we are already connecting is not worth queueing.
     */
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    private val mediaDownloader by lazy { MediaDownloader() }

    /**
     * How to fetch each recent message's attachment, by message id.
     *
     * Kept here instead of on [Received] because a ref carries the media's decryption key, and a
     * host that wants to show a photo has no business holding one. Bounded to the same window as
     * [State.recent], so anything still on screen is still downloadable and nothing accumulates.
     */
    private val mediaRefs = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, MediaRef>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaRef>) = size > MAX_RECENT
        }
    )

    fun connect() {
        if (running) return
        _state.update { it.copy(status = "Connecting…") }
        appContext.startForegroundService(Intent(appContext, WaForegroundService::class.java))
    }

    /**
     * Try again now rather than when the backoff expires — what a "Reconnect" control calls. Starts
     * the connection if the loop has stopped altogether.
     */
    fun reconnect() {
        if (running) retryNow.trySend(Unit) else connect()
    }

    /** Mark the first-run gate satisfied without linking ("Skip for now"). */
    fun skipOnboarding() {
        _state.update { it.copy(onboardingDone = true) }
    }

    /**
     * Fetch and decrypt the attachment on the message with this [id].
     *
     * Suspends for the length of an HTTPS round-trip; runs off the socket entirely, so it can never
     * stall message delivery. Returns null when there is nothing to fetch — no attachment, nothing
     * downloadable, or the message has aged out of the recent window. Throws only if a download
     * arrives and fails its integrity checks: bytes that did not verify are never returned.
     */
    suspend fun downloadMedia(id: String): ByteArray? {
        val ref = mediaRefs[id] ?: return null
        return runCatching { mediaDownloader.download(ref) }
            .onFailure { Log.w(config.logTag, "media download failed for id=$id", it) }
            .getOrThrow()
    }

    /** Attach [annotation] to the message [id], e.g. what a host app's pipeline decided about it. */
    fun annotate(id: String, annotation: Annotation) {
        _state.update { st -> st.copy(recent = st.recent.map { if (it.id == id) it.copy(annotation = annotation) else it }) }
    }

    /**
     * Unlink this device and erase its WhatsApp identity. Destructive and irreversible without a
     * fresh QR scan: the Signal store goes too, because a re-link generates new identity keys and
     * any surviving session would make incoming messages permanently undecryptable.
     */
    fun logout() {
        scope.launch {
            Log.i(config.logTag, "logout requested")
            _state.update { it.copy(status = "Logging out…") }
            runCatching { client?.logout() }.onFailure { Log.w(config.logTag, "client logout failed", it) }
            client = null
            stopService()

            runCatching {
                credentialStore.clear()
                keyValueStore.clearAll()
            }.onFailure { Log.w(config.logTag, "credential wipe failed", it) }

            Log.i(config.logTag, "logout complete; local WhatsApp state erased")
            // Keep `onboardingDone` latched so we stay in the app rather than reverting to the gate.
            _state.value = State(initializing = false, onboardingDone = true)
        }
    }

    /** Exposed for [WaForegroundService], which builds its notification outside this class. */
    internal val notificationTitle: String get() = config.notificationTitle

    private fun stopService() {
        service?.stopSelf()
        service = null
        runCatching { appContext.stopService(Intent(appContext, WaForegroundService::class.java)) }
    }

    internal fun attachService(s: WaForegroundService) {
        service = s
    }

    internal fun detachService() {
        service = null
    }

    /**
     * Connect, and keep reconnecting until told to stop.
     *
     * A long-lived companion socket does not stay up: the server, a NAT, or the carrier reaps it
     * once it goes quiet, which arrives as a TLS close_notify. whatsmeow treats that as routine and
     * simply redials, and so do we — no keepalive survives Doze, so the answer is to come back fast
     * rather than to try never to drop.
     */
    internal suspend fun runClient() {
        if (running) return
        running = true
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                val c = WAClient(credentialStore, keyValueStore, listener, config.deviceName)
                client = c
                // A throw here is a failure to get a socket at all (no route, DNS, refused
                // handshake) — the same thing as a drop, minus ever having been connected.
                val ended = runCatching { c.connect() }
                    .getOrElse { WAClient.Ended.Dropped(it, wasConnected = false) }
                client = null

                when (ended) {
                    WAClient.Ended.Closed -> return
                    WAClient.Ended.Unlinked -> {
                        // The credentials are dead, so retrying is pointless. They are left in
                        // place rather than wiped on the server's say-so; the user re-links.
                        Log.w(config.logTag, "device is no longer linked; stopping")
                        _state.update {
                            it.copy(connected = false, status = "Not linked — log out and scan again")
                        }
                        stopService()
                        return
                    }
                    WAClient.Ended.Reconnect -> attempt = 0
                    is WAClient.Ended.Dropped -> {
                        // A session that was up and then dropped starts the backoff over: far more
                        // likely a reaped idle socket than a server refusing us.
                        if (ended.wasConnected) attempt = 0
                        attempt++
                        Log.i(config.logTag, "disconnected (attempt $attempt): ${ended.cause?.message}")
                        _state.update { it.copy(connected = false, status = "Disconnected") }
                        waitBeforeRetry(attempt)
                    }
                }
            }
        } finally {
            running = false
            client = null
        }
    }

    /**
     * Hold off before redialling — but stop early if the host asks for a retry, or if the network
     * comes back while we were waiting out a backoff started with the radio off.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitBeforeRetry(attempt: Int) = coroutineScope {
        val online = network.isOnline()
        val waitMs = if (online) backoffMs(attempt) else NO_NETWORK_WAIT_MS
        _state.update {
            it.copy(status = if (online) "Reconnecting in ${waitMs / 1000}s…" else "Waiting for network…")
        }
        service?.updateNotification(if (online) "Reconnecting…" else "Waiting for network…")

        val networkBack = async { if (online) awaitCancellation() else network.awaitOnline() }
        try {
            select {
                onTimeout(waitMs) {}
                retryNow.onReceive {}
                networkBack.onAwait {}
            }
        } finally {
            networkBack.cancel()
        }
    }

    /** Exponential, capped. [attempt] is clamped so the shift cannot run away. */
    private fun backoffMs(attempt: Int): Long =
        minOf(RETRY_BASE_MS shl (attempt.coerceIn(1, RETRY_MAX_SHIFT) - 1), RETRY_CAP_MS)

    /**
     * Map a decrypted result to the public [Received] shape, dropping device-to-device plumbing.
     * Returns null for anything a caller should never see, so the protocol-traffic rule lives in
     * one place instead of being re-implemented by every consumer.
     */
    private fun toReceived(result: MessageDecryptor.Result): Received? {
        if (MessageDecryptor.isProtocolTraffic(result)) {
            Log.i(config.logTag, "id=${result.id} is protocol traffic (category=${result.category}), not surfaced")
            return null
        }
        result.mediaRef?.let { mediaRefs[result.id] = it }
        return Received(
            id = result.id,
            phone = if (result.fromMe) result.recipientPhone else result.senderPhone,
            chatType = result.chatType,
            media = result.media,
            chatName = if (result.chatType == ChatType.GROUP) names.groupSubject(result.chat) else null,
            senderName = result.senderName,
            text = MessageDecryptor.textOf(result.message),
            kind = MessageDecryptor.kindOf(result.message),
            timestampMillis = result.timestampMillis,
            fromMe = result.fromMe,
            senderJid = result.sender.toString(),
            chatJid = result.chat.toString(),
        )
    }

    private val listener = object : WAClient.Listener {
        override fun onQr(codes: List<String>) {
            _state.update { it.copy(qrCodes = codes, status = "Scan the QR in WhatsApp → Linked devices") }
            service?.updateNotification("Scan QR in WhatsApp Settings → Linked devices")
        }

        override fun onPaired(jid: String) {
            _state.update { it.copy(paired = true, qrCodes = emptyList(), deviceJid = jid, status = "Paired ($jid)") }
            service?.updateNotification("Paired: $jid")
        }

        override fun onLoggedIn() {
            _state.update { it.copy(connected = true, onboardingDone = true, status = "Connected") }
            service?.updateNotification("Connected")
        }

        override fun onGroupSubject(chatJid: String, subject: String) {
            // Messages from this group that were already delivered show no name; now that one is
            // known, correct them in place rather than leaving a mix of named and unnamed rows.
            _state.update { st ->
                st.copy(recent = st.recent.map { if (it.chatJid == chatJid) it.copy(chatName = subject) else it })
            }
        }

        override suspend fun onMessage(messages: List<MessageDecryptor.Result>) {
            val received = messages.mapNotNull(::toReceived)
            if (received.isEmpty()) return
            _state.update { it.copy(recent = (received.asReversed() + it.recent).take(MAX_RECENT)) }
            deliveries.trySend(received)
        }

    }

    companion object {
        /** Default Linked Devices name. Public so a host can build on it rather than retype it. */
        const val DEFAULT_DEVICE_NAME = "Tether"

        private const val MAX_RECENT = 50

        /** First retry delay; doubles per consecutive failure up to [RETRY_CAP_MS]. */
        private const val RETRY_BASE_MS = 1_000L
        private const val RETRY_CAP_MS = 60_000L
        private const val RETRY_MAX_SHIFT = 7
        /** With no network there is nothing to back off from — this is just a safety net if the
         *  callback never fires. */
        private const val NO_NETWORK_WAIT_MS = 60_000L

        /**
         * The process's active manager, so [WaForegroundService] — which Android instantiates via
         * the manifest, not a caller — can reach it without depending on a host app's DI container.
         * There is exactly one WhatsApp connection per process, so a single slot is sufficient.
         */
        @Volatile private var active: WhatsAppManager? = null

        internal fun current(): WhatsAppManager? = active
    }
}
