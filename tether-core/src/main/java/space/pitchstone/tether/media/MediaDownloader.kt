package space.pitchstone.tether.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import space.pitchstone.tether.net.OkHttpFrameTransport

/**
 * Fetches an attachment from WhatsApp's CDN and decrypts it.
 *
 * Separate from the socket entirely: media is plain HTTPS to `mmg.whatsapp.net`, not stanzas over
 * the Noise connection, so a download can never stall the read loop.
 */
internal class MediaDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) {
    /**
     * @return the decrypted bytes, or null if there is nothing to fetch (no key, no location).
     * @throws IllegalStateException if the download fails integrity checks — never silently
     *   returns bytes that did not verify.
     */
    suspend fun download(ref: MediaRef): ByteArray? = withContext(Dispatchers.IO) {
        val mediaKey = ref.mediaKey ?: return@withContext null
        val url = ref.resolvedUrl() ?: return@withContext null

        // The CDN is the web client's, and it checks who is asking: an unadorned request can come
        // back 403 even with a perfectly good URL and key.
        val request = Request.Builder()
            .url(url)
            .header("Origin", OkHttpFrameTransport.DEFAULT_ORIGIN)
            .header("Referer", "${OkHttpFrameTransport.DEFAULT_ORIGIN}/")
            .build()

        val body = client.newCall(request).execute().use { response ->
            // `use` matters: an un-consumed, un-closed OkHttp body leaks the connection, and this
            // runs once per attachment.
            check(response.isSuccessful) { "media download failed: HTTP ${response.code}" }
            response.body?.bytes() ?: error("media download returned no body")
        }

        MediaCrypto.decrypt(body, MediaCrypto.keys(mediaKey, ref.kind), ref.fileEncSha256, ref.fileSha256)
    }
}

/**
 * Everything needed to fetch one attachment later: where it lives and the key that opens it.
 *
 * Held by the SDK rather than handed to callers — it is the message's decryption key, and a host
 * that only wants to show a picture has no reason to hold one.
 */
internal data class MediaRef(
    val kind: MediaKind,
    val url: String?,
    val directPath: String?,
    val mediaKey: ByteArray?,
    val fileEncSha256: ByteArray?,
    val fileSha256: ByteArray?,
) {
    /**
     * `url` is the complete CDN address when present. `directPath` is the fallback — a path on
     * the media host, which WhatsApp sends instead on some messages.
     */
    fun resolvedUrl(): String? = url?.takeIf { it.isNotBlank() }
        ?: directPath?.takeIf { it.isNotBlank() }?.let { "$MEDIA_HOST$it" }

    private companion object {
        const val MEDIA_HOST = "https://mmg.whatsapp.net"
    }
}
