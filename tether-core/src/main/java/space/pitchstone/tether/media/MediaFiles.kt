package space.pitchstone.tether.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where downloaded attachments land on disk, and how another app is given sight of one.
 *
 * Decrypted media has to become a real file before anything outside this process can open it —
 * a PDF viewer takes a `content://` URI, not a byte array. Files live under `cacheDir`, so the
 * system can reclaim them under storage pressure and they never outlive an uninstall.
 */
internal class MediaFiles(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.cacheDir, DIRECTORY)

    /**
     * The file for this message, whether or not it exists yet.
     *
     * Keyed by message id rather than by name: two people can send `invoice.pdf`, and the id is
     * what makes them distinct. The name is kept only as the leaf, so a viewer shows something
     * recognisable and the extension still drives which app opens it.
     */
    fun fileFor(messageId: String, media: MediaInfo): File {
        val name = media.fileName
            ?: "${media.kind.name.lowercase()}${extensionFor(media.mimetype)}"
        return File(File(root, sanitizeFileName(messageId) ?: "message"), name)
    }

    /** Writes [bytes] to this message's file, replacing anything already there. */
    fun write(messageId: String, media: MediaInfo, bytes: ByteArray): File {
        val file = fileFor(messageId, media)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /**
     * A URI another app can read, via the provider this SDK declares in its own manifest.
     *
     * A `file://` URI would throw FileUriExposedException on anything since Nougat, and the
     * consumer of this SDK should not have to stand up a provider of their own to open a
     * download the SDK produced.
     */
    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}$AUTHORITY_SUFFIX", file)

    /** Everything downloaded so far, dropped on logout along with the rest of the account state. */
    fun clear() {
        runCatching { root.deleteRecursively() }
    }

    private fun extensionFor(mimetype: String?): String = when {
        mimetype.isNullOrBlank() -> ""
        mimetype.startsWith("image/") -> "." + mimetype.substringAfter('/').substringBefore(';')
        mimetype == "application/pdf" -> ".pdf"
        else -> ""
    }

    private companion object {
        const val DIRECTORY = "tether-media"
        /** Must match the authority in the library manifest. */
        const val AUTHORITY_SUFFIX = ".tether.fileprovider"
    }
}
