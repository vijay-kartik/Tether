package space.pitchstone.tether.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import space.pitchstone.tether.binary.ChatType
import space.pitchstone.tether.media.MediaInfo
import space.pitchstone.tether.media.MediaKind
import space.pitchstone.tether.session.WhatsAppManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The WhatsApp account space: QR pairing (also usable as a first-run onboarding gate via
 * [onSkip]), and — once linked — account status plus a running list of what has been observed on
 * the connection, with log out.
 *
 * A host app supplies its own [manager] instance (there is exactly one connection per process);
 * this composable has no dependency on any particular app's DI container or pipeline. A
 * [WhatsAppManager.Received.annotation], if the host app attaches one via
 * [WhatsAppManager.annotate], is rendered under the message it belongs to.
 */
@Composable
fun WhatsAppScreen(manager: WhatsAppManager, modifier: Modifier = Modifier, onSkip: (() -> Unit)? = null) {
    val state by manager.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClearMessages by remember { mutableStateOf(false) }

    // The server sends several refs at once; each is only valid for ~20s, so rotate through them
    // like whatsmeow does. Showing just the first means a slightly slow scan fails with no visible
    // reason.
    val codes = state.qrCodes
    var index by remember(codes) { mutableIntStateOf(0) }
    LaunchedEffect(codes) {
        while (index < codes.size - 1) {
            delay(20_000)
            index++
        }
    }
    val qr = codes.getOrNull(index)
    val linked = (state.connected || state.alreadyLinked || state.paired) && qr == null

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out of WhatsApp?") },
            text = {
                Text(
                    "This unlinks the device and erases its WhatsApp keys and message sessions. " +
                        "You'll need to scan a new QR code to link again."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; manager.logout() }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }

    if (confirmClearMessages) {
        AlertDialog(
            onDismissRequest = { confirmClearMessages = false },
            title = { Text("Clear all conversations?") },
            text = {
                Text(
                    "This will permanently delete all stored messages and conversations. " +
                        "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClearMessages = false; manager.clearAllMessages() }) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClearMessages = false }) { Text("Cancel") } },
        )
    }

    if (linked) {
        LinkedAccount(
            modifier = modifier,
            status = state.status,
            deviceJid = state.deviceJid,
            recent = state.recent,
            connected = state.connected,
            onReconnect = manager::reconnect,
            onLogout = { confirmLogout = true },
            onClearMessages = { confirmClearMessages = true },
            onDownload = manager::downloadMedia,
            onOpen = manager::openableMedia,
        )
    } else {
        Pairing(modifier = modifier, status = state.status, qr = qr, paired = state.paired, onSkip = onSkip, onConnect = manager::connect)
    }
}

/** Linked: account header plus the running list of what has actually come in. */
@Composable
private fun LinkedAccount(
    modifier: Modifier,
    status: String,
    deviceJid: String?,
    recent: List<WhatsAppManager.Received>,
    connected: Boolean,
    onReconnect: () -> Unit,
    onLogout: () -> Unit,
    onClearMessages: () -> Unit,
    onDownload: suspend (String) -> ByteArray?,
    onOpen: suspend (String, MediaInfo) -> Uri?,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("WhatsApp", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                deviceJid?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!connected) TextButton(onClick = onReconnect) { Text("Reconnect") }
            TextButton(onClick = onClearMessages) { Text("Clear") }
            TextButton(onClick = onLogout) { Text("Log out") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        if (recent.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing yet.\nMessages appear here as they arrive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            // Re-assemble the conversations each message was already assigned to at ingestion.
            val conversations = WhatsAppManager.groupIntoConversations(recent.reversed())
            val (personalConversations, groupConversations) = conversations.partition {
                it.chatType == ChatType.DIRECT
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A section with nothing in it would be a heading over blank space, so each
                // appears only once it has something to show.
                if (personalConversations.isNotEmpty()) {
                    item { SectionHeader("Personal") }
                    items(personalConversations) { conversation ->
                        ConversationGroup(conversation, onDownload, onOpen)
                    }
                }
                if (groupConversations.isNotEmpty()) {
                    item { SectionHeader("Groups") }
                    items(groupConversations) { conversation ->
                        ConversationGroup(conversation, onDownload, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/**
 * A conversation: a run of messages from the same chat with less than 10 minutes between
 * consecutive ones. Rendered as a single card so the grouping is visible at a glance, with the
 * chat name and time span shown once at the top rather than repeated on every message inside.
 */
@Composable
private fun ConversationGroup(
    conversation: WhatsAppManager.Conversation,
    onDownload: suspend (String) -> ByteArray?,
    onOpen: suspend (String, MediaInfo) -> Uri?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val chatLabel = conversationLabel(conversation)
                if (chatLabel != null) {
                    Text(
                        chatLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    conversationTimeRange(conversation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))

            // Consecutive messages from the same side (same sender, us-or-them) read as one turn
            // in the exchange, so only the first of a run repeats who is speaking.
            conversation.messages.forEachIndexed { index, message ->
                val previous = conversation.messages.getOrNull(index - 1)
                val newSpeaker = previous == null || previous.senderJid != message.senderJid || previous.fromMe != message.fromMe
                ReceivedRow(message, showSenderInfo = newSpeaker, onDownload = onDownload, onOpen = onOpen)
                if (index != conversation.messages.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** "14:02" for a one-message conversation, "14:02–14:09" once it spans more than one. */
private fun conversationTimeRange(conversation: WhatsAppManager.Conversation): String {
    val start = clockTime(conversation.startTime)
    val end = clockTime(conversation.endTime)
    return if (start == end) start else "$start–$end"
}

/**
 * One message's bubble, offset and tinted by [Received.fromMe] like a chat bubble so which side
 * sent it reads at a glance.
 *
 * [showSenderInfo] is false for a message that continues the same speaker's previous turn inside
 * a [ConversationGroup] — the name is not worth repeating, but the per-message time still is.
 */
@Composable
private fun ReceivedRow(
    message: WhatsAppManager.Received,
    showSenderInfo: Boolean,
    onDownload: suspend (String) -> ByteArray?,
    onOpen: suspend (String, MediaInfo) -> Uri?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.fromMe) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(Modifier.padding(10.dp)) {
                if (showSenderInfo) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            senderLabel(message),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(clockTime(message.timestampMillis), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    // Still worth knowing exactly when this one landed, even mid-turn.
                    Text(
                        clockTime(message.timestampMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                }
                message.media?.let { media -> MediaBlock(message.id, media, onDownload, onOpen) }

                // A caption is the message's text, so an attachment with one shows it here and
                // needs no placeholder. Only a genuinely wordless message falls back to its kind.
                val body = message.text ?: if (message.media == null) "(${message.kind})" else null
                body?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = if (showSenderInfo) 4.dp else 0.dp),
                    )
                }
                message.annotation?.let { annotation ->
                    Text(
                        annotation.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (annotation.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clockTime(millis: Long): String =
    CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** Not linked: the QR onboarding flow, centred. */
@Composable
private fun Pairing(
    modifier: Modifier,
    status: String,
    qr: String?,
    paired: Boolean,
    onSkip: (() -> Unit)?,
    onConnect: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("WhatsApp", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)

        when {
            qr != null -> {
                val bitmap = remember(qr) { qrBitmap(qr, 640) }
                bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = "WhatsApp pairing QR", modifier = Modifier.size(280.dp))
                }
                Text(
                    "Open WhatsApp → Settings → Linked devices → Link a device, then scan.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Scanned: pairing done on the phone, finishing the companion login. Keep this open.
            paired -> {
                CircularProgressIndicator()
                Text("Finishing sign-in… keep this screen open.", style = MaterialTheme.typography.bodySmall)
            }
            else -> Button(onClick = onConnect) { Text("Connect WhatsApp") }
        }

        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("Skip for now") }
        }
    }
}

private fun qrBitmap(content: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}.getOrNull()

/**
 * Who sent this: their WhatsApp name where they have announced one, their number otherwise, and
 * both when they differ — a name is what you recognise, a number is what you can act on.
 */
private fun senderLabel(message: WhatsAppManager.Received): String {
    if (message.fromMe) return message.phone?.let { "You → $it" } ?: "You"
    val name = message.senderName?.takeIf { it.isNotBlank() }
    val phone = message.phone?.takeIf { it.isNotBlank() }
    return when {
        name != null && phone != null -> "$name · $phone"
        name != null -> name
        phone != null -> phone
        else -> "Unknown sender"
    }
}

/**
 * Which conversation this row belongs to. Null for a direct chat, where the section and the sender
 * already say everything. Falls back to the group's id while its subject is still being fetched, so
 * a row is never left unattributed.
 */
private fun conversationLabel(conversation: WhatsAppManager.Conversation): String? = when (conversation.chatType) {
    ChatType.GROUP -> conversation.chatName ?: conversation.chatJid.substringBefore('@')
    ChatType.BROADCAST -> "Status / broadcast"
    ChatType.NEWSLETTER -> conversation.chatName ?: "Channel"
    ChatType.OTHER -> conversation.chatJid.substringBefore('@')
    ChatType.DIRECT -> null
}

/**
 * An attachment, rendered according to what it is.
 *
 * Whatever the kind, any thumbnail comes free inside the message, so something is on screen the
 * moment it arrives — a download is a network round-trip and should never be the difference
 * between showing something and showing nothing.
 */
@Composable
private fun MediaBlock(
    messageId: String,
    media: MediaInfo,
    onDownload: suspend (String) -> ByteArray?,
    onOpen: suspend (String, MediaInfo) -> Uri?,
) {
    // Exhaustive on purpose: a `when` over the enum forces a decision for every kind, so a media
    // type added later cannot quietly fall through to the image path and fail there.
    when (media.kind) {
        MediaKind.IMAGE, MediaKind.STICKER -> ImageAttachment(messageId, media, onDownload)
        // A document, a video or a voice note is a file. The useful thing to do with one is hand
        // it to an app that understands it, not try to decode it as a bitmap.
        MediaKind.DOCUMENT, MediaKind.VIDEO, MediaKind.AUDIO -> FileAttachment(messageId, media, onOpen)
    }
}

/** A picture: its inline preview straight away, and the full-size file on demand. */
@Composable
private fun ImageAttachment(
    messageId: String,
    media: MediaInfo,
    onDownload: suspend (String) -> ByteArray?,
) {
    var full by remember(messageId) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(messageId) { mutableStateOf(false) }
    var error by remember(messageId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val preview = remember(messageId) { media.thumbnail?.let(::decodeImage) }
    val shown = full ?: preview

    val fetch = {
        loading = true
        error = null
        scope.launch {
            runCatching { onDownload(messageId) }
                .onSuccess { bytes ->
                    full = bytes?.let(::decodeImage)
                    // Bytes that are not decodable are not a failure to download — say which.
                    if (full == null) error = "Could not show this image"
                }
                .onFailure { error = it.message ?: "Download failed" }
            loading = false
        }
        Unit
    }

    Column(Modifier.padding(top = 6.dp)) {
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = media.kind.name.lowercase(),
                modifier = Modifier
                    .fillMaxWidth()
                    // Height is capped rather than free: a tall photo would otherwise push every
                    // other message off the screen.
                    .heightIn(max = 240.dp)
                    .clip(MaterialTheme.shapes.small)
                    .then(if (full == null && media.downloadable && !loading) Modifier.clickable(onClick = fetch) else Modifier),
            )
        }

        val caption = when {
            loading -> "Loading…"
            error != null -> error
            // Without a preview there is nothing on screen at all, so say what is here and that it
            // can be fetched.
            shown == null && media.downloadable -> "${media.kind.name.lowercase()} · ${sizeLabel(media.fileLength)} · tap to load"
            shown == null -> "${media.kind.name.lowercase()} · unavailable"
            full == null && media.downloadable -> "Preview · tap for full size"
            else -> null
        }
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .then(if (shown == null && media.downloadable && !loading) Modifier.clickable(onClick = fetch) else Modifier),
            )
        }
    }
}

/** Decodes to null rather than throwing: a video or document's bytes are not an image. */
private fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0L -> "unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * A non-image attachment: what it is, and a way to open it in whatever app handles that type.
 *
 * The bytes are fetched and cached by the SDK; this only asks for a URI and hands it on. Any
 * inline preview the message carried (a document's first page, a video's poster frame) is shown
 * above, because it costs nothing and says more than a filename does.
 */
@Composable
private fun FileAttachment(
    messageId: String,
    media: MediaInfo,
    onOpen: suspend (String, MediaInfo) -> Uri?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(messageId) { mutableStateOf(false) }
    var error by remember(messageId) { mutableStateOf<String?>(null) }
    val poster = remember(messageId) { media.thumbnail?.let(::decodeImage) }

    val label = media.fileName ?: media.kind.name.lowercase()
    val detail = listOfNullOrBlank(sizeLabel(media.fileLength), media.mimetype).joinToString(" · ")

    Column(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(
                if (media.downloadable && !busy) {
                    Modifier.clickable {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { onOpen(messageId, media) }
                                .onSuccess { uri ->
                                    if (uri == null) error = "Nothing to open"
                                    else if (!openWith(context, uri, media.mimetype)) {
                                        // The file downloaded fine; there is simply nothing
                                        // installed that opens it. Saying so beats a bare failure.
                                        error = "No app can open this file"
                                    }
                                }
                                .onFailure { error = it.message ?: "Could not open" }
                            busy = false
                        }
                    }
                } else Modifier
            ),
    ) {
        poster?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                busy -> "Opening…"
                error != null -> error!!
                media.downloadable -> "$detail · tap to open"
                else -> "$detail · unavailable"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hands [uri] to whatever app claims the type.
 *
 * @return false when nothing on the device does — the caller can say so, rather than the tap
 *   appearing to do nothing at all.
 */
private fun openWith(context: Context, uri: Uri, mimetype: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimetype?.takeIf { it.isNotBlank() } ?: "*/*")
        // Without this the receiving app gets a URI it is not allowed to read.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .isSuccess
}

private fun listOfNullOrBlank(vararg parts: String?): List<String> =
    parts.filterNotNull().filter { it.isNotBlank() }
