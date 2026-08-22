package space.pitchstone.tether.media

import space.pitchstone.tether.proto.gen.Message

/**
 * The displayable facts about an attachment.
 *
 * Deliberately does not carry the media key or CDN address: a caller needs those only to fetch the
 * file, which the SDK does on their behalf. Keeping them out means a host can log or persist a
 * message without also persisting the key that decrypts its contents.
 */
data class MediaInfo(
    val kind: MediaKind,
    val mimetype: String?,
    /**
     * The sender's name for the file, for documents. Already sanitised: the raw value comes from
     * whoever sent the message, so it is never trusted as a path.
     */
    val fileName: String?,
    val fileLength: Long,
    val width: Int,
    val height: Int,
    /**
     * A small JPEG preview carried inside the message itself. Present on most photos and always
     * free — no download, no network — so it can be shown the moment the message arrives.
     */
    val thumbnail: ByteArray?,
    /** True when the full file can be fetched; false for a message that carries only a preview. */
    val downloadable: Boolean,
)

/**
 * Strip a sender-supplied filename down to something safe to write.
 *
 * The name arrives from whoever sent the message, so `../../databases/wa.db` is a filename they
 * are free to choose. Only the last path segment survives, and only characters that cannot
 * traverse or escape a directory.
 */
internal fun sanitizeFileName(raw: String?): String? {
    val base = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return null
    val cleaned = base.filter { it.isLetterOrDigit() || it in ALLOWED_NAME_CHARS }.trim('.', ' ')
    return cleaned.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH)
}

private const val ALLOWED_NAME_CHARS = " ._-()[]&+#@'"
private const val MAX_NAME_LENGTH = 128

/** Pulls the media parts out of a decrypted message, or null when it carries no attachment. */
internal fun mediaOf(message: Message): Pair<MediaInfo, MediaRef>? {
    message.imageMessage?.let { m ->
        return info(
            MediaKind.IMAGE, m.mimetype, m.fileLength, m.width, m.height, m.jpegThumbnail?.toByteArray(),
            m.url, m.directPath, m.mediaKey?.toByteArray(), m.fileEncSha256?.toByteArray(), m.fileSha256?.toByteArray(),
        )
    }
    message.stickerMessage?.let { m ->
        return info(
            MediaKind.STICKER, m.mimetype, m.fileLength, m.width, m.height, null,
            m.url, m.directPath, m.mediaKey?.toByteArray(), m.fileEncSha256?.toByteArray(), m.fileSha256?.toByteArray(),
        )
    }
    message.videoMessage?.let { m ->
        return info(
            MediaKind.VIDEO, m.mimetype, m.fileLength, m.width, m.height, m.jpegThumbnail?.toByteArray(),
            m.url, m.directPath, m.mediaKey?.toByteArray(), m.fileEncSha256?.toByteArray(), m.fileSha256?.toByteArray(),
        )
    }
    message.documentMessage?.let { m ->
        return info(
            MediaKind.DOCUMENT, m.mimetype, m.fileLength, 0, 0, m.jpegThumbnail?.toByteArray(),
            m.url, m.directPath, m.mediaKey?.toByteArray(), m.fileEncSha256?.toByteArray(), m.fileSha256?.toByteArray(),
            // `title` is the display name WhatsApp shows when the sender renamed the attachment;
            // `fileName` is the original. Either is better than none.
            fileName = m.fileName ?: m.title,
        )
    }
    message.audioMessage?.let { m ->
        return info(
            MediaKind.AUDIO, m.mimetype, m.fileLength, 0, 0, null,
            m.url, m.directPath, m.mediaKey?.toByteArray(), m.fileEncSha256?.toByteArray(), m.fileSha256?.toByteArray(),
        )
    }
    return null
}

private fun info(
    kind: MediaKind,
    mimetype: String?,
    fileLength: Long?,
    width: Int?,
    height: Int?,
    thumbnail: ByteArray?,
    url: String?,
    directPath: String?,
    mediaKey: ByteArray?,
    fileEncSha256: ByteArray?,
    fileSha256: ByteArray?,
    fileName: String? = null,
): Pair<MediaInfo, MediaRef> {
    val ref = MediaRef(kind, url, directPath, mediaKey, fileEncSha256, fileSha256)
    val media = MediaInfo(
        kind = kind,
        mimetype = mimetype,
        fileName = sanitizeFileName(fileName),
        fileLength = fileLength ?: 0L,
        width = width ?: 0,
        height = height ?: 0,
        thumbnail = thumbnail?.takeIf { it.isNotEmpty() },
        // A view-once or already-expired message still describes itself but has nothing to fetch.
        downloadable = mediaKey != null && ref.resolvedUrl() != null,
    )
    return media to ref
}
