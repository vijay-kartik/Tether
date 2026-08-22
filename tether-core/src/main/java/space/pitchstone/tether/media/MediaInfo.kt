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
): Pair<MediaInfo, MediaRef> {
    val ref = MediaRef(kind, url, directPath, mediaKey, fileEncSha256, fileSha256)
    val media = MediaInfo(
        kind = kind,
        mimetype = mimetype,
        fileLength = fileLength ?: 0L,
        width = width ?: 0,
        height = height ?: 0,
        thumbnail = thumbnail?.takeIf { it.isNotEmpty() },
        // A view-once or already-expired message still describes itself but has nothing to fetch.
        downloadable = mediaKey != null && ref.resolvedUrl() != null,
    )
    return media to ref
}
